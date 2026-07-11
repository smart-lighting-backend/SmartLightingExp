package com.experiment.smartlightingexp.controller;

import com.experiment.smartlightingexp.config.MqttProperties;
import com.experiment.smartlightingexp.entity.DeviceCredential;
import com.experiment.smartlightingexp.mapper.DeviceCredentialMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.experiment.smartlightingexp.util.AesUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EMQX HTTP 认证/ACL 回调接口。
 * 设备连接 MQTT 时 EMQX 将 username/password 原样 POST 到此接口，
 * 后端解密 device_credential 表中的出厂编号和识别码，拼接后明文比对。
 */
@Slf4j
@RestController
@RequestMapping("/api/mqtt")
@RequiredArgsConstructor
public class MqttAuthController {

    private final DeviceCredentialMapper credentialMapper;
    private final AesUtil aesUtil;
    private final MqttProperties mqttProperties;

    private static final String DEFAULT_ID_CODE = "123456";

    /**
     * EMQX HTTP 认证回调。
     * EMQX 把设备 CONNECT 报文中的 username/password POST 过来。
     * 返回 200 {"result": "allow"} 放行，{"result": "deny"} 拒绝。
     */
    @PostMapping("/auth")
    public Map<String, Object> auth(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.isBlank()) {
            return deny();
        }

        // 1. 服务账号 backend — 与配置中的密码比对
        if ("backend".equals(username)) {
            boolean ok = mqttProperties.getPassword().equals(password);
            return ok ? allow() : deny();
        }

        // 2. Dashboard 账号
        if ("dashboard".equals(username)) {
            // dashboard 仅允许订阅，使用与 backend 相同的密码
            boolean ok = mqttProperties.getPassword().equals(password);
            return ok ? allow() : deny();
        }

        // 3. 设备账号 — 解密 device_credential 比对
        try {
            DeviceCredential cred = credentialMapper.selectOne(
                    new LambdaQueryWrapper<DeviceCredential>()
                            .eq(DeviceCredential::getUsername, username));
            if (cred == null) {
                log.debug("[MQTT Auth] 未知用户: {}", username);
                return deny();
            }

            String factorySerial = aesUtil.decrypt(cred.getFactorySerialEncrypted());
            String idCode = aesUtil.decrypt(cred.getDeviceIdCodeEncrypted());
            String expectedPassword = factorySerial + idCode;

            boolean match = expectedPassword.equals(password);
            if (match) {
                return allow();
            } else {
                log.debug("[MQTT Auth] 密码不匹配: {}", username);
                return deny();
            }
        } catch (Exception e) {
            log.warn("[MQTT Auth] 验证异常: {} — {}", username, e.getMessage());
            return deny();
        }
    }

    /**
     * EMQX HTTP ACL 回调。
     * 限制设备只能操作自己命名空间下的 topic。
     */
    @PostMapping("/acl")
    public Map<String, Object> acl(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String topic = body.get("topic");
        String action = body.get("action"); // publish / subscribe

        if (username == null || topic == null) {
            return deny();
        }

        // backend / dashboard — 全权限
        if ("backend".equals(username) || "dashboard".equals(username)) {
            return allow();
        }

        // 设备账号 — topic 中的 deviceId 必须等于 username
        String[] parts = topic.split("/");
        if (parts.length >= 2 && "streetlight".equals(parts[0])) {
            String topicDeviceId = parts[1];
            if (username.equals(topicDeviceId)) {
                return allow();
            }
        }

        log.debug("[MQTT ACL] 拒绝: {} {} {}", username, action, topic);
        return deny();
    }

    private Map<String, Object> allow() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "allow");
        return result;
    }

    private Map<String, Object> deny() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "deny");
        return result;
    }
}
