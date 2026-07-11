package com.experiment.smartlightingexp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.experiment.smartlightingexp.entity.DeviceCredential;
import com.experiment.smartlightingexp.mapper.DeviceCredentialMapper;
import com.experiment.smartlightingexp.util.AesUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 设备 MQTT 凭证服务。
 * 创建设备时自动生成凭证：用户名=deviceId，密码=出厂编号+识别码（BCrypt哈希存储）。
 * 出厂编号和设备识别码以 AES-256-CBC 可逆加密存储，用于运维查看原始密码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceCredentialService {

    private final DeviceCredentialMapper credentialMapper;
    private final AesUtil aesUtil;
    private final PasswordEncoder passwordEncoder;

    /** 设备识别码默认值 */
    private static final String DEFAULT_ID_CODE = "123456";

    /**
     * 为设备生成 MQTT 鉴权凭证。
     *
     * @param deviceId           设备ID（如 SL_001）
     * @param factorySerialPlain 出厂编号（明文，如 AAA-FACTORY-001）
     * @return 创建的凭证记录
     */
    public DeviceCredential createCredential(String deviceId, String factorySerialPlain) {
        String idCode = DEFAULT_ID_CODE;
        String plainPassword = factorySerialPlain + idCode;

        DeviceCredential cred = new DeviceCredential();
        cred.setDeviceId(deviceId);
        cred.setUsername(deviceId);
        cred.setPasswordHash(passwordEncoder.encode(plainPassword));
        cred.setFactorySerialEncrypted(aesUtil.encrypt(factorySerialPlain));
        cred.setDeviceIdCodeEncrypted(aesUtil.encrypt(idCode));

        credentialMapper.insert(cred);
        syncToEmqx(deviceId, plainPassword);
        log.info("[设备凭证] 已生成并同步EMQX: deviceId={}, username={}", deviceId, deviceId);
        return cred;
    }

    /**
     * 获取设备原始 MQTT 密码（明文）。
     * 仅供运维人员在设备烧录时查看。
     */
    public String getPlainPassword(String deviceId) {
        DeviceCredential cred = getByDeviceId(deviceId);
        if (cred == null) return null;
        String factorySerial = aesUtil.decrypt(cred.getFactorySerialEncrypted());
        String idCode = aesUtil.decrypt(cred.getDeviceIdCodeEncrypted());
        return factorySerial + idCode;
    }

    /**
     * 获取设备 MQTT 凭证信息（用于运维查看和烧录）。
     * 返回：broker 地址、端口、用户名、密码等完整连接信息。
     */
    public java.util.Map<String, String> getConnectionInfo(String deviceId) {
        DeviceCredential cred = getByDeviceId(deviceId);
        if (cred == null) return null;
        java.util.Map<String, String> info = new java.util.LinkedHashMap<>();
        info.put("deviceId", deviceId);
        info.put("username", cred.getUsername());
        info.put("password", getPlainPassword(deviceId));
        info.put("broker", "47.96.27.141");
        info.put("port", "8883");
        info.put("protocol", "mqtts");
        info.put("topicPrefix", "streetlight/" + deviceId);
        return info;
    }

    /**
     * 删除设备的凭证记录（设备删除时调用，同步清理 EMQX built_in 用户）。
     */
    public void deleteByDeviceId(String deviceId) {
        credentialMapper.delete(
                new LambdaQueryWrapper<DeviceCredential>()
                        .eq(DeviceCredential::getDeviceId, deviceId));
        deleteFromEmqx(deviceId);
        log.info("[设备凭证] 已删除(含EMQX): deviceId={}", deviceId);
    }

    /**
     * 从 EMQX built_in 数据库中删除用户。
     */
    private void deleteFromEmqx(String deviceId) {
        try {
            String loginBody = "{\"username\":\"admin\",\"password\":\"" + emqxAdminPassword + "\"}";
            String loginResp = restTemplate.postForObject(
                    EMQX_API + "/login",
                    new org.springframework.http.HttpEntity<>(loginBody,
                            new org.springframework.http.HttpHeaders() {{
                                setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                            }}), String.class);
            String token = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(loginResp).get("token").asText();

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setBearerAuth(token);
            restTemplate.exchange(EMQX_API + "/authentication/password_based:built_in_database/users/"
                    + deviceId, org.springframework.http.HttpMethod.DELETE,
                    new org.springframework.http.HttpEntity<>(headers), String.class);

            log.info("[EMQX同步] {} 用户已删除", deviceId);
        } catch (Exception e) {
            log.warn("[EMQX同步] 删除 {} 失败: {}", deviceId, e.getMessage());
        }
    }

    /**
     * 按 deviceId 查询凭证记录。
     */
    public DeviceCredential getByDeviceId(String deviceId) {
        return credentialMapper.selectOne(
                new LambdaQueryWrapper<DeviceCredential>()
                        .eq(DeviceCredential::getDeviceId, deviceId));
    }

    /**
     * 更新设备识别码（同时更新 MySQL BCrypt 哈希和 EMQX built_in 密码）。
     *
     * @param deviceId    设备ID
     * @param newIdCode   新识别码（明文）
     * @return 新的明文密码
     */
    public String updateIdCode(String deviceId, String newIdCode) {
        DeviceCredential cred = getByDeviceId(deviceId);
        if (cred == null) {
            throw new IllegalArgumentException("设备凭证不存在: " + deviceId);
        }
        String factorySerial = aesUtil.decrypt(cred.getFactorySerialEncrypted());
        String newPassword = factorySerial + newIdCode;

        cred.setPasswordHash(passwordEncoder.encode(newPassword));
        cred.setDeviceIdCodeEncrypted(aesUtil.encrypt(newIdCode));
        credentialMapper.updateById(cred);

        // 同步到 EMQX built_in
        syncToEmqx(deviceId, newPassword);

        log.info("[设备凭证] 识别码已更新: deviceId={}, 密码已同步到 EMQX", deviceId);
        return newPassword;
    }

    // ─────────── EMQX API 同步 ───────────

    private static final String EMQX_API = "http://47.96.27.141:18083/api/v5";

    /** EMQX Dashboard 管理员密码（用于调用 API 同步用户密码） */
    @org.springframework.beans.factory.annotation.Value("${emqx.admin-password:xyx246824XYX@}")
    private String emqxAdminPassword;

    private final org.springframework.web.client.RestTemplate restTemplate =
            new org.springframework.web.client.RestTemplate();

    /**
     * 将设备密码同步到 EMQX built_in 数据库（不存在则创建，存在则更新）。
     */
    private void syncToEmqx(String deviceId, String newPassword) {
        try {
            String loginBody = "{\"username\":\"admin\",\"password\":\"" + emqxAdminPassword + "\"}";
            String loginResp = restTemplate.postForObject(
                    EMQX_API + "/login", new org.springframework.http.HttpEntity<>(loginBody,
                            new org.springframework.http.HttpHeaders() {{
                                setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                            }}), String.class);
            String token = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(loginResp).get("token").asText();

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            // 先尝试更新（PUT 只需要 password，不需要 user_id）
            String putBody = "{\"password\":\"" + newPassword + "\"}";
            String postBody = "{\"user_id\":\"" + deviceId + "\",\"password\":\"" + newPassword + "\"}";

            try {
                restTemplate.put(EMQX_API + "/authentication/password_based:built_in_database/users/"
                        + deviceId, new org.springframework.http.HttpEntity<>(putBody, headers));
                log.info("[EMQX同步] {} 密码已更新", deviceId);
            } catch (Exception putEx) {
                // 用户不存在，POST 创建
                try {
                    restTemplate.postForObject(EMQX_API + "/authentication/password_based:built_in_database/users",
                            new org.springframework.http.HttpEntity<>(postBody, headers), String.class);
                    log.info("[EMQX同步] {} 用户已创建", deviceId);
                } catch (Exception postEx) {
                    // 用户可能已存在（409），强制 PUT 重试
                    restTemplate.put(EMQX_API + "/authentication/password_based:built_in_database/users/"
                            + deviceId, new org.springframework.http.HttpEntity<>(putBody, headers));
                    log.info("[EMQX同步] {} 密码已强制更新", deviceId);
                }
            }
        } catch (Exception e) {
            log.warn("[EMQX同步] {} 失败: {}", deviceId, e.getMessage());
        }
    }
}
