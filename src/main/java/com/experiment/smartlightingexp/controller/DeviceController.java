package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.dto.*;
import com.experiment.smartlightingexp.entity.*;
import com.experiment.smartlightingexp.mapper.*;
import com.experiment.smartlightingexp.mqtt.MqttPublisher;
import com.experiment.smartlightingexp.service.*;
import com.experiment.smartlightingexp.tdengine.TelemetryDao;
import com.experiment.smartlightingexp.tdengine.VisionEventDao;
import com.experiment.smartlightingexp.tdengine.VoiceEventDao;
import com.experiment.smartlightingexp.util.EventTextNormalizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 设备管理控制器 — 设备增删改查 + 组合条件分页查询。
 * 写操作记录审计日志，满足 IR-11 安全可信控制的可追溯要求。
 */
@Slf4j
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final AuditLogMapper auditLogMapper;
    private final DeviceAreaMapper deviceAreaMapper;
    private final MqttPublisher mqttPublisher;

    /**
     * 组合条件分页查询设备列表。
     */
    @RequirePermission("device:read")
    @PostMapping("/list")
    public Result<IPage<Device>> list(@RequestBody DeviceQueryRequest request) {
        LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<>();

        // 默认只查未删除的设备
        wrapper.eq(Device::getDeleted, false);

        if (request.getDeviceId() != null && !request.getDeviceId().isBlank()) {
            wrapper.eq(Device::getDeviceId, request.getDeviceId());
        }
        if (request.getName() != null && !request.getName().isBlank()) {
            wrapper.like(Device::getName, request.getName());
        }
        if (request.getArea() != null && !request.getArea().isBlank()) {
            wrapper.eq(Device::getArea, request.getArea());
        }
        if (request.getAreaId() != null) {
            wrapper.eq(Device::getAreaId, request.getAreaId());
        }
        if (request.getLocation() != null && !request.getLocation().isBlank()) {
            wrapper.like(Device::getLocation, request.getLocation());
        }
        if (request.getStatus() != null) {
            wrapper.eq(Device::getStatus, request.getStatus());
        }
        if (request.getEnabled() != null) {
            wrapper.eq(Device::getEnabled, request.getEnabled());
        }
        if (request.getHealthScoreMin() != null) {
            wrapper.ge(Device::getHealthScore, request.getHealthScoreMin());
        }
        if (request.getHealthScoreMax() != null) {
            wrapper.le(Device::getHealthScore, request.getHealthScoreMax());
        }

        wrapper.orderByDesc(Device::getCreateTime);

        Page<Device> page = new Page<>(request.getPage(), request.getSize());
        IPage<Device> result = deviceService.page(page, wrapper);

        log.info("[设备查询] 条件: deviceId={}, name={}, area={}, status={}, 结果数={}",
                request.getDeviceId(), request.getName(), request.getArea(),
                request.getStatus(), result.getRecords().size());

        return Result.success(result);
    }

    /**
     * 分页查询设备列表（GET 方式，URL 查询参数）。
     * 支持关键词搜索（匹配 deviceId / name / location）+ 区域 / 状态 / 启用状态筛选。
     */
    @RequirePermission("device:read")
    @GetMapping("/page")
    public Result<IPage<Device>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) Long areaId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean enabled) {

        // 参数校验
        if (pageNum < 1) pageNum = 1;
        if (pageSize < 1) pageSize = 10;
        if (pageSize > 100) pageSize = 100;

        LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Device::getDeleted, false);

        // 关键词模糊匹配 deviceId / name / location
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w
                    .like(Device::getDeviceId, keyword)
                    .or().like(Device::getName, keyword)
                    .or().like(Device::getLocation, keyword));
        }
        if (area != null && !area.isBlank()) {
            wrapper.eq(Device::getArea, area);
        }
        if (areaId != null) {
            wrapper.eq(Device::getAreaId, areaId);
        }
        if (status != null) {
            wrapper.eq(Device::getStatus, status);
        }
        if (enabled != null) {
            wrapper.eq(Device::getEnabled, enabled);
        }

        // 排序：区域升序 → 设备编号升序
        wrapper.orderByAsc(Device::getArea).orderByAsc(Device::getDeviceId);

        Page<Device> page = new Page<>(pageNum, pageSize);
        IPage<Device> result = deviceService.page(page, wrapper);

        log.info("[设备分页] keyword={}, area={}, status={}, enabled={}, 结果数={}",
                keyword, area, status, enabled, result.getRecords().size());
        return Result.success(result);
    }

    /**
     * 查询单个设备详情。
     */
    @RequirePermission("device:read")
    @GetMapping("/{deviceId}")
    public Result<Device> getByDeviceId(@PathVariable String deviceId) {
        Device device = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getDeleted, false)
                .one();
        if (device == null) {
            return Result.error("设备不存在");
        }
        return Result.success(device);
    }

    /**
     * 新增设备。
     */
    @RequirePermission("device:create")
    @PostMapping
    public Result<Device> create(@RequestBody Device device,
                                 HttpServletRequest httpRequest) {
        if (device.getDeviceId() == null || device.getDeviceId().isBlank()) {
            return Result.error(400, "设备编号不能为空");
        }

        // 检查 deviceId 唯一性（包含已删除的数据，因为唯一索引仍占用）
        Device exist = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, device.getDeviceId())
                .one();
        if (exist != null) {
            return Result.error(409, "设备编号已存在");
        }

        if (device.getStatus() == null) device.setStatus(1);
        if (device.getEnabled() == null) device.setEnabled(true);
        if (device.getHealthScore() == null) device.setHealthScore(new BigDecimal("100.00"));
        if (device.getTopicPrefix() == null || device.getTopicPrefix().isBlank()) device.setTopicPrefix("streetlight");
        // areaId 优先：自动填充区域名
        if (device.getAreaId() != null) {
            DeviceArea area = deviceAreaMapper.selectById(device.getAreaId());
            if (area != null) {
                device.setAreaId(area.getId());
                device.setArea(area.getName());
            }
        }
        device.setDeleted(false);
        deviceService.save(device);

        saveAuditLog("DEVICE_CREATE", "DEVICE", device.getDeviceId(),
                "新增设备-" + device.getName(), "SUCCESS", httpRequest);
        log.info("[设备] 新增: deviceId={}, name={}", device.getDeviceId(), device.getName());
        return Result.success(device);
    }

    /**
     * 批量新增设备。
     */
    @RequirePermission("device:create")
    @PostMapping("/batch")
    public Result<Map<String, Object>> batchCreate(@RequestBody List<Device> devices,
                                                    HttpServletRequest httpRequest) {
        if (devices == null || devices.isEmpty()) {
            return Result.error(400, "设备列表不能为空");
        }

        List<Map<String, Object>> failed = new ArrayList<>();
        List<Device> toSave = new ArrayList<>();

        // 获取已有 deviceId 集合
        Set<String> existingIds = new HashSet<>(deviceService.lambdaQuery()
                .select(Device::getDeviceId)
                .list()
                .stream()
                .map(Device::getDeviceId)
                .toList());
        Set<String> batchIds = new HashSet<>();

        // 预加载 area name → areaId 映射
        Map<String, Long> areaNameToId = new LinkedHashMap<>();
        List<String> areaNames = devices.stream()
                .map(Device::getArea)
                .filter(a -> a != null && !a.isBlank())
                .distinct()
                .toList();
        if (!areaNames.isEmpty()) {
            deviceAreaMapper.selectList(
                    new LambdaQueryWrapper<DeviceArea>()
                            .in(DeviceArea::getName, areaNames))
                    .forEach(a -> areaNameToId.put(a.getName(), a.getId()));
        }

        for (int i = 0; i < devices.size(); i++) {
            Device d = devices.get(i);
            int row = i + 1;

            if (d.getDeviceId() == null || d.getDeviceId().isBlank()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("row", row);
                err.put("deviceId", d.getDeviceId());
                err.put("reason", "设备编号不能为空");
                failed.add(err);
                continue;
            }

            // 重复检测：已有设备 + 本批次内重复
            if (existingIds.contains(d.getDeviceId()) || batchIds.contains(d.getDeviceId())) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("row", row);
                err.put("deviceId", d.getDeviceId());
                err.put("reason", "设备编号重复");
                failed.add(err);
                continue;
            }

            batchIds.add(d.getDeviceId());

            // 设置默认值
            if (d.getStatus() == null) d.setStatus(1);
            if (d.getEnabled() == null) d.setEnabled(true);
            if (d.getHealthScore() == null) d.setHealthScore(new BigDecimal("100.00"));
            if (d.getTopicPrefix() == null || d.getTopicPrefix().isBlank()) d.setTopicPrefix("streetlight");
            // area → areaId 映射
            if (d.getArea() != null && !d.getArea().isBlank()) {
                Long areaId = areaNameToId.get(d.getArea());
                if (areaId != null) d.setAreaId(areaId);
            }
            d.setDeleted(false);
            toSave.add(d);
        }

        // 批量写入
        if (!toSave.isEmpty()) {
            deviceService.saveBatch(toSave);
            for (Device d : toSave) {
                saveAuditLog("DEVICE_CREATE", "DEVICE", d.getDeviceId(),
                        "批量新增-" + d.getName(), "SUCCESS", httpRequest);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", devices.size());
        result.put("success", toSave.size());
        result.put("failed", failed.size());
        result.put("failedDetails", failed);

        log.info("[设备] 批量新增: 总数={}, 成功={}, 失败={}", devices.size(), toSave.size(), failed.size());
        return Result.success(result);
    }

    /**
     * 批量导入设备 — 上传 Excel / CSV 文件解析并创建。
     * <p>
     * 支持列名自动匹配（中文/英文），与前端 BatchImport 解析逻辑一致。
     * 复用与 /batch 相同的校验、去重、area→areaId 映射逻辑。
     */
    @RequirePermission("device:create")
    @PostMapping("/batch/import")
    public Result<Map<String, Object>> batchImport(@RequestParam("file") MultipartFile file,
                                                   HttpServletRequest httpRequest) {
        if (file.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }

        String filename = file.getOriginalFilename();
        String ext = filename != null ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!"xlsx".equals(ext) && !"csv".equals(ext)) {
            return Result.error(400, "仅支持 .xlsx、.csv 格式，当前: " + ext);
        }

        List<String[]> rows;
        try {
            if ("csv".equals(ext)) {
                rows = parseCsvFile(file);
            } else {
                rows = parseXlsxFile(file);
            }
        } catch (Exception e) {
            log.error("[设备] 文件解析失败: {}", e.getMessage(), e);
            return Result.error(400, "文件解析失败: " + e.getMessage());
        }

        if (rows.size() < 2) {
            return Result.error(400, "文件为空或只有表头");
        }

        // 解析表头 → 列索引映射（与前端 excelTemplate.js 一致）
        Map<String, Integer> colMap = buildColumnMap(rows.get(0));

        // 行数据 → Device 对象
        List<Device> devices = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String deviceId = getCell(row, colMap, "deviceId");
            if (deviceId == null || deviceId.isBlank()) continue;

            Device d = new Device();
            d.setDeviceId(deviceId.trim());
            d.setName(getCell(row, colMap, "name"));
            d.setArea(getCell(row, colMap, "area"));
            String lng = getCell(row, colMap, "longitude");
            String lat = getCell(row, colMap, "latitude");
            if (lng != null && !lng.isBlank() && lat != null && !lat.isBlank()) {
                d.setLocation(lng.trim() + "," + lat.trim());
            }
            String power = getCell(row, colMap, "ratedPower");
            if (power != null && !power.isBlank()) {
                try { d.setRatedPower(new BigDecimal(power.trim())); } catch (Exception ignored) {}
            }
            devices.add(d);
        }

        if (devices.isEmpty()) {
            return Result.error(400, "文件中未解析到有效设备数据");
        }

        // 以下逻辑与 batchCreate 完全一致
        List<Map<String, Object>> failed = new ArrayList<>();
        List<Device> toSave = new ArrayList<>();

        Set<String> existingIds = new HashSet<>(deviceService.lambdaQuery()
                .select(Device::getDeviceId).list().stream()
                .map(Device::getDeviceId).toList());
        Set<String> batchIds = new HashSet<>();

        Map<String, Long> areaNameToId = new LinkedHashMap<>();
        List<String> areaNames = devices.stream()
                .map(Device::getArea).filter(a -> a != null && !a.isBlank()).distinct().toList();
        if (!areaNames.isEmpty()) {
            deviceAreaMapper.selectList(
                    new LambdaQueryWrapper<DeviceArea>().in(DeviceArea::getName, areaNames))
                    .forEach(a -> areaNameToId.put(a.getName(), a.getId()));
        }

        for (int i = 0; i < devices.size(); i++) {
            Device d = devices.get(i);
            int rowNum = i + 2; // 第 1 行是表头

            if (d.getDeviceId() == null || d.getDeviceId().isBlank()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("row", rowNum);
                err.put("deviceId", d.getDeviceId());
                err.put("reason", "设备编号不能为空");
                failed.add(err);
                continue;
            }

            if (existingIds.contains(d.getDeviceId()) || batchIds.contains(d.getDeviceId())) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("row", rowNum);
                err.put("deviceId", d.getDeviceId());
                err.put("reason", "设备编号重复");
                failed.add(err);
                continue;
            }

            batchIds.add(d.getDeviceId());
            if (d.getStatus() == null) d.setStatus(1);
            if (d.getEnabled() == null) d.setEnabled(true);
            if (d.getHealthScore() == null) d.setHealthScore(new BigDecimal("100.00"));
            if (d.getTopicPrefix() == null || d.getTopicPrefix().isBlank()) d.setTopicPrefix("streetlight");
            if (d.getArea() != null && !d.getArea().isBlank()) {
                Long areaId = areaNameToId.get(d.getArea());
                if (areaId != null) d.setAreaId(areaId);
            }
            d.setDeleted(false);
            toSave.add(d);
        }

        if (!toSave.isEmpty()) {
            deviceService.saveBatch(toSave);
            for (Device d : toSave) {
                saveAuditLog("DEVICE_CREATE", "DEVICE", d.getDeviceId(),
                        "文件导入-" + d.getName(), "SUCCESS", httpRequest);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", devices.size());
        result.put("success", toSave.size());
        result.put("failed", failed.size());
        result.put("failedDetails", failed);
        log.info("[设备] 文件导入: 总数={}, 成功={}, 失败={}", devices.size(), toSave.size(), failed.size());
        return Result.success(result);
    }

    // ───────────── 文件解析工具方法 ─────────────

    private static final String[][] IMPORT_FIELD_ALIASES = {
        {"deviceId", "设备编号", "设备ID", "设备编码", "编号", "deviceId", "device_id", "id"},
        {"name", "设备名称", "名称", "设备名", "name", "deviceName", "device_name"},
        {"area", "所属区域", "区域", "分区", "area", "region"},
        {"longitude", "经度", "longitude", "lng"},
        {"latitude", "纬度", "latitude", "lat"},
        {"ratedPower", "额定功率(W)", "额定功率", "功率", "ratedPower", "rated_power", "power"},
    };

    private Map<String, Integer> buildColumnMap(String[] headerRow) {
        Map<String, Integer> map = new LinkedHashMap<>();
        List<String> headers = new ArrayList<>();
        for (String h : headerRow) {
            headers.add(h == null ? "" : h.trim().toLowerCase().replaceAll("[\\s()（）]", ""));
        }
        for (String[] field : IMPORT_FIELD_ALIASES) {
            String key = field[0];
            int idx = -1;
            for (int i = 0; i < headers.size(); i++) {
                String h = headers.get(i);
                for (int j = 1; j < field.length; j++) {
                    if (h.equals(field[j].toLowerCase().replaceAll("[\\s()（）]", ""))) {
                        idx = i;
                        break;
                    }
                }
                if (idx >= 0) break;
            }
            map.put(key, idx >= 0 ? idx : 0);
        }
        return map;
    }

    private String getCell(String[] row, Map<String, Integer> colMap, String key) {
        Integer idx = colMap.get(key);
        if (idx == null || idx >= row.length) return "";
        String value = row[idx];
        return value == null ? "" : value.replace("﻿", "").trim();
    }

    private List<String[]> parseCsvFile(MultipartFile file) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 简单 CSV 解析：处理引号包裹
                rows.add(parseCsvLine(line));
            }
        }
        return rows;
    }

    private String[] parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells.toArray(new String[0]);
    }

    private List<String[]> parseXlsxFile(MultipartFile file) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int rowIdx = 0; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (row == null) continue;
                int lastCol = Math.max(row.getLastCellNum(), 6);
                String[] cells = new String[lastCol];
                boolean hasValue = false;
                for (int colIdx = 0; colIdx < lastCol; colIdx++) {
                    Cell cell = row.getCell(colIdx);
                    String val = cell == null ? "" : readCellValue(cell);
                    cells[colIdx] = val;
                    if (!val.isEmpty()) hasValue = true;
                }
                if (hasValue) rows.add(cells);
            }
        }
        return rows;
    }

    private String readCellValue(Cell cell) {
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC: {
                double v = cell.getNumericCellValue();
                // 整数不显示小数点
                if (v == Math.floor(v) && !Double.isInfinite(v)) {
                    return String.valueOf((long) v);
                }
                return String.valueOf(v);
            }
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try { return cell.getStringCellValue().trim(); } catch (Exception e) { return ""; }
            default: return "";
        }
    }

    // ───────────── 文件解析工具方法结束 ─────────────

    /**
     * 更新设备信息。
     */
    @RequirePermission("device:update")
    @PutMapping("/{deviceId}")
    public Result<Device> update(@PathVariable String deviceId,
                                 @RequestBody Device device,
                                 HttpServletRequest httpRequest) {
        Device existing = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getDeleted, false)
                .one();
        if (existing == null) {
            saveAuditLog("DEVICE_UPDATE", "DEVICE", deviceId,
                    "设备不存在-更新失败", "FAIL", httpRequest);
            return Result.error(404, "设备不存在");
        }

        // 只更新允许修改的字段
        if (device.getName() != null) existing.setName(device.getName());
        if (device.getArea() != null) existing.setArea(device.getArea());
        if (device.getAreaId() != null) {
            DeviceArea area = deviceAreaMapper.selectById(device.getAreaId());
            if (area != null) {
                existing.setAreaId(area.getId());
                existing.setArea(area.getName());
            }
        }
        if (device.getLocation() != null) existing.setLocation(device.getLocation());
        if (device.getStatus() != null) existing.setStatus(device.getStatus());
        if (device.getHealthScore() != null) existing.setHealthScore(device.getHealthScore());
        if (device.getTopicPrefix() != null) {
            existing.setTopicPrefix(device.getTopicPrefix().isBlank() ? "streetlight" : device.getTopicPrefix());
        }
        if (device.getEnabled() != null) existing.setEnabled(device.getEnabled());
        deviceService.updateById(existing);

        // 重新查询返回最新数据
        Device updated = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getDeleted, false)
                .one();

        saveAuditLog("DEVICE_UPDATE", "DEVICE", deviceId,
                "更新设备-" + existing.getName(), "SUCCESS", httpRequest);
        log.info("[设备] 更新: deviceId={}, name={}", deviceId, existing.getName());
        return Result.success(updated);
    }

    /**
     * 批量分配设备区域。
     */
    @RequirePermission("device:update")
    @PutMapping("/batch-area")
    public Result<Void> batchUpdateArea(@RequestBody @Valid BatchAreaRequest request,
                                        HttpServletRequest httpRequest) {
        // 解析设备ID列表：优先使用 deviceCodes（移动端），否则使用 deviceIds（前端）
        List<Long> ids;
        if (request.getDeviceCodes() != null && !request.getDeviceCodes().isEmpty()) {
            ids = deviceService.lambdaQuery()
                    .select(Device::getId)
                    .in(Device::getDeviceId, request.getDeviceCodes())
                    .eq(Device::getDeleted, false)
                    .list()
                    .stream()
                    .map(Device::getId)
                    .collect(Collectors.toList());
        } else if (request.getDeviceIds() != null && !request.getDeviceIds().isEmpty()) {
            ids = request.getDeviceIds().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
        } else {
            return Result.error(400, "deviceIds 或 deviceCodes 至少需要提供一个");
        }

        if (ids.isEmpty()) {
            return Result.error(404, "未找到匹配的设备");
        }

        // 解析区域名
        String areaName = null;
        if (request.getAreaId() != null) {
            DeviceArea area = deviceAreaMapper.selectById(request.getAreaId());
            if (area == null) {
                return Result.error(404, "区域不存在");
            }
            areaName = area.getName();
        }

        deviceService.batchUpdateArea(ids, request.getAreaId(), areaName);

        saveAuditLog("DEVICE_BATCH_AREA", "DEVICE", ids.toString(),
                "批量分配区域" + (areaName != null ? "-" + areaName : "-清除"), "SUCCESS", httpRequest);
        log.info("[设备] 批量分配区域: 设备数={}, areaId={}", ids.size(), request.getAreaId());
        return Result.success();
    }

    /**
     * 批量停用设备。
     */
    @RequirePermission("device:update")
    @PutMapping("/batch-disable")
    public Result<Map<String, Object>> batchDisable(@RequestBody @Valid BatchDeviceRequest request,
                                                    HttpServletRequest httpRequest) {
        List<Long> ids = normalizeDeviceIds(request.getDeviceIds());
        if (ids.isEmpty()) {
            return Result.error(400, "设备ID列表不能为空");
        }

        int success = deviceService.batchDisableDevices(ids);
        if (success == 0) {
            saveAuditLog("DEVICE_BATCH_DISABLE", "DEVICE", ids.toString(),
                    "未找到可停用设备-批量停用失败", "FAIL", httpRequest);
            return Result.error(404, "未找到可停用设备");
        }

        saveAuditLog("DEVICE_BATCH_DISABLE", "DEVICE", ids.toString(),
                "批量停用设备-" + success + "台", "SUCCESS", httpRequest);
        log.info("[设备] 批量停用: 请求数={}, 成功={}", ids.size(), success);
        return Result.success(batchResult(ids.size(), success));
    }

    /**
     * 批量启用设备。
     */
    @RequirePermission("device:update")
    @PutMapping("/batch-enable")
    public Result<Map<String, Object>> batchEnable(@RequestBody @Valid BatchDeviceRequest request,
                                                   HttpServletRequest httpRequest) {
        List<Long> ids = normalizeDeviceIds(request.getDeviceIds());
        if (ids.isEmpty()) {
            return Result.error(400, "设备ID列表不能为空");
        }

        int success = deviceService.batchEnableDevices(ids);
        if (success == 0) {
            saveAuditLog("DEVICE_BATCH_ENABLE", "DEVICE", ids.toString(),
                    "未找到可启用设备-批量启用失败", "FAIL", httpRequest);
            return Result.error(404, "未找到可启用设备");
        }

        saveAuditLog("DEVICE_BATCH_ENABLE", "DEVICE", ids.toString(),
                "批量启用设备-" + success + "台", "SUCCESS", httpRequest);
        log.info("[设备] 批量启用: 请求数={}, 成功={}", ids.size(), success);
        return Result.success(batchResult(ids.size(), success));
    }

    /**
     * 批量删除设备（逻辑删除）。
     */
    @RequirePermission("device:delete")
    @DeleteMapping("/batch")
    public Result<Map<String, Object>> batchDelete(@RequestBody @Valid BatchDeviceRequest request,
                                                   HttpServletRequest httpRequest) {
        List<Long> ids = normalizeDeviceIds(request.getDeviceIds());
        if (ids.isEmpty()) {
            return Result.error(400, "设备ID列表不能为空");
        }

        int success = deviceService.batchDeleteDevices(ids);
        if (success == 0) {
            saveAuditLog("DEVICE_BATCH_DELETE", "DEVICE", ids.toString(),
                    "未找到可删除设备-批量删除失败", "FAIL", httpRequest);
            return Result.error(404, "未找到可删除设备");
        }

        saveAuditLog("DEVICE_BATCH_DELETE", "DEVICE", ids.toString(),
                "批量删除设备-" + success + "台", "SUCCESS", httpRequest);
        log.info("[设备] 批量删除: 请求数={}, 成功={}", ids.size(), success);
        return Result.success(batchResult(ids.size(), success));
    }

    /**
     * 删除设备（软删除）。
     */
    @RequirePermission("device:delete")
    @DeleteMapping("/{deviceId}")
    public Result<Void> delete(@PathVariable String deviceId,
                               HttpServletRequest httpRequest) {
        Device existing = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getDeleted, false)
                .one();
        if (existing == null) {
            saveAuditLog("DEVICE_DELETE", "DEVICE", deviceId,
                    "设备不存在-删除失败", "FAIL", httpRequest);
            return Result.error("设备不存在");
        }

        existing.setEnabled(false);
        existing.setStatus(0);
        deviceService.updateById(existing);
        boolean removed = deviceService.removeById(existing.getId());
        if (!removed) {
            saveAuditLog("DEVICE_DELETE", "DEVICE", deviceId,
                    "设备删除失败", "FAIL", httpRequest);
            return Result.error(500, "设备删除失败");
        }

        saveAuditLog("DEVICE_DELETE", "DEVICE", deviceId,
                "删除设备-" + existing.getName(), "SUCCESS", httpRequest);
        log.info("[设备] 删除: deviceId={}, name={}", deviceId, existing.getName());
        return Result.success();
    }

    // ======================== 地图轻量接口 ========================

    /**
     * 地图标记 — 仅返回 6 个字段，比 /list?pageSize=10000 轻量 60%+。
     * 用于 Dashboard 数字孪生地图组件，避免传输 latestData 等大字段。
     */
    @RequirePermission("device:read")
    @GetMapping("/map-locations")
    public Result<List<Map<String, Object>>> mapLocations() {
        List<Device> devices = deviceService.lambdaQuery()
                .select(Device::getDeviceId, Device::getName, Device::getLocation,
                        Device::getStatus, Device::getArea, Device::getHealthScore)
                .eq(Device::getDeleted, false)
                .eq(Device::getEnabled, true)
                .list();

        List<Map<String, Object>> result = new ArrayList<>(devices.size());
        for (Device d : devices) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", d.getDeviceId());
            item.put("name", d.getName());
            item.put("location", d.getLocation());
            item.put("status", d.getStatus());
            item.put("area", d.getArea());
            item.put("healthScore", d.getHealthScore());
            result.add(item);
        }
        return Result.success(result);
    }

    // ======================== 审计日志 ========================

    private void saveAuditLog(String action, String targetType, String targetId,
                              String detail, String result, HttpServletRequest request) {
        try {
            AuditLog logEntry = new AuditLog();
            String operator = SecurityContext.getCurrentUsername();
            logEntry.setOperator(operator != null ? operator : "UNKNOWN");
            logEntry.setAction(action);
            logEntry.setTargetType(targetType);
            logEntry.setTargetId(targetId);
            logEntry.setDetail(detail);
            logEntry.setResult(result);
            logEntry.setIpAddress(getClientIp(request));
            logEntry.setOperatedAt(LocalDateTime.now());
            auditLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.error("审计日志记录失败: {}", e.getMessage());
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    private List<Long> normalizeDeviceIds(List<Long> deviceIds) {
        return deviceIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private Map<String, Object> batchResult(int total, int success) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("success", success);
        result.put("failed", total - success);
        return result;
    }

    // ───────────── 健康评分 API ─────────────

    private final AlarmRecordMapper alarmRecordMapper;
    private final TelemetryMapper telemetryMapper;
    private final TelemetryDao telemetryDao;
    private final ControlCommandMapper controlCommandMapper;
    private final VisionEventDao visionEventDao;
    private final VoiceEventDao voiceEventDao;
    private final VisionEventService visionEventService;
    private final VoiceEventService voiceEventService;
    private final AlarmRecordService alarmRecordService;
    private final ObjectMapper objectMapper;

    @RequirePermission("device:read")
    @GetMapping("/{deviceId}/health")
    public Result<Map<String, Object>> getHealth(@PathVariable String deviceId) {
        Device device = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId).eq(Device::getDeleted, false).one();
        if (device == null) return Result.error("设备不存在");

        // 四个维度实时计算（权重与 HealthScoreTask 一致）
        int offline = calcOffline(deviceId);
        int comm = calcComm(deviceId);
        int response = calcResponse(deviceId);
        int sensor = calcSensor(device);

        // 总分 = 维度加权平均（与定时任务一致，保持数据实时性）
        int overallScore = (int) Math.round(
            0.30 * offline + 0.25 * comm + 0.25 * response + 0.20 * sensor
        );

        String level;
        String color;
        if (overallScore >= 90) { level = "优秀"; color = "#4caf50"; }
        else if (overallScore >= 70) { level = "良好"; color = "#ff9800"; }
        else if (overallScore >= 50) { level = "一般"; color = "#ff9800"; }
        else if (overallScore >= 30) { level = "较差"; color = "#f44336"; }
        else { level = "危险"; color = "#f44336"; }

        List<Map<String, Object>> dimensions = new ArrayList<>();
        dimensions.add(dimItem("离线频次", offline, "30%", offline == 100 ? null : "近7天离线次数较多"));
        dimensions.add(dimItem("通信质量", comm, "25%", comm == 100 ? null : "遥测上报间隔波动较大"));
        dimensions.add(dimItem("指令响应率", response, "25%", response == 100 ? null : "部分指令未收到设备确认"));
        dimensions.add(dimItem("传感器状态", sensor, "20%", sensor == 100 ? null : "部分传感器读数异常或为空"));

        String suggestion = overallScore >= 90 ? "设备状态极佳" :
                overallScore >= 70 ? "设备总体健康，建议定期巡检" :
                overallScore >= 50 ? "关注设备运行状况，建议安排检查" :
                "设备健康度较低，建议尽快安排维修";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("deviceName", device.getName());
        result.put("overallScore", overallScore);
        result.put("level", level);
        result.put("levelColor", color);
        result.put("dimensions", dimensions);
        result.put("suggestion", suggestion);
        result.put("evaluatedAt", LocalDateTime.now().toString());
        return Result.success(result);
    }

    @RequirePermission("device:read")
    @GetMapping("/health/summary")
    public Result<Map<String, Object>> healthSummary() {
        List<Device> devices = deviceService.lambdaQuery()
                .eq(Device::getEnabled, true).eq(Device::getDeleted, false).list();
        List<Map<String, Object>> list = new ArrayList<>();
        int healthy = 0, warning = 0, critical = 0;
        double sum = 0;
        for (Device d : devices) {
            int s = d.getHealthScore() != null ? d.getHealthScore().intValue() : 0;
            sum += s;
            if (s >= 90) healthy++;
            else if (s >= 70) healthy++;
            else if (s >= 50) warning++;
            else critical++;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", d.getDeviceId());
            item.put("name", d.getName());
            item.put("score", s);
            item.put("level", s >= 90 ? "优秀" : s >= 70 ? "良好" : s >= 50 ? "一般" : s >= 30 ? "较差" : "危险");
            list.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDevices", devices.size());
        result.put("healthyCount", healthy);
        result.put("warningCount", warning);
        result.put("criticalCount", critical);
        result.put("averageScore", devices.isEmpty() ? 0 : Math.round(sum / devices.size()));
        result.put("list", list);
        return Result.success(result);
    }

    private Map<String, Object> dimItem(String name, int score, String weight, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("score", score);
        m.put("weight", weight);
        m.put("reason", reason);
        return m;
    }

    private int calcOffline(String deviceId) {
        long count = alarmRecordMapper.selectCount(
                new LambdaQueryWrapper<AlarmRecord>()
                        .eq(AlarmRecord::getDeviceId, deviceId)
                        .eq(AlarmRecord::getType, "OFFLINE")
                        .ge(AlarmRecord::getStartAt, LocalDateTime.now().minusDays(7)));
        if (count == 0) return 100;
        if (count == 1) return 80;
        if (count == 2) return 60;
        if (count == 3) return 40;
        return 20;
    }

    private int calcComm(String deviceId) {
        List<Telemetry> list;
        try {
            list = telemetryDao.query24h(deviceId);
        } catch (DataAccessException e) {
            list = telemetryMapper.selectList(
                    new LambdaQueryWrapper<Telemetry>()
                            .eq(Telemetry::getDeviceId, deviceId)
                            .ge(Telemetry::getCreateTime, LocalDateTime.now().minusHours(24))
                            .orderByAsc(Telemetry::getCreateTime));
        }
        if (list == null || list.size() < 3) return 0;
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < list.size(); i++) {
            LocalDateTime prev = list.get(i - 1).getCollectedAt();
            LocalDateTime curr = list.get(i).getCollectedAt();
            if (prev == null || curr == null) continue;
            Duration d = Duration.between(prev, curr);
            gaps.add((double) Math.abs(d.getSeconds() - 300));
        }
        if (gaps.isEmpty()) return 0;
        double avg = gaps.stream().mapToDouble(Double::doubleValue).average().orElse(999);
        if (avg < 30) return 100;
        if (avg < 60) return 80;
        if (avg < 120) return 60;
        return 40;
    }

    private int calcResponse(String deviceId) {
        List<ControlCommand> all = controlCommandMapper.selectList(
                new LambdaQueryWrapper<ControlCommand>()
                        .eq(ControlCommand::getDeviceId, deviceId)
                        .ge(ControlCommand::getIssuedAt, LocalDateTime.now().minusDays(7)));
        if (all.isEmpty()) return 100;
        long acked = all.stream().filter(c -> c.getAckAt() != null).count();
        double rate = (double) acked / all.size();
        if (rate >= 1.0) return 100;
        if (rate >= 0.8) return 80;
        if (rate >= 0.5) return 60;
        return 30;
    }

    private int calcSensor(Device device) {
        if (device.getLatestData() == null || device.getLatestData().isBlank()) return 0;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(device.getLatestData(), Map.class);
            int abnormal = 0, total = 0;
            abnormal += chk(data, "illuminance", 0, 2000); total++;
            abnormal += chk(data, "temperature", -10, 50); total++;
            abnormal += chk(data, "humidity", 0, 100);     total++;
            abnormal += chk(data, "pm25", 0, 500);         total++;
            abnormal += chk(data, "aqi", 0, 500);          total++;
            if (total == 0) return 100;
            double ratio = (double) abnormal / total;
            if (ratio == 0) return 100;
            if (ratio <= 0.2) return 70;
            if (ratio <= 0.5) return 40;
            return 10;
        } catch (Exception e) { return 0; }
    }

    private int chk(Map<String, Object> data, String key, double min, double max) {
        Object val = data.get(key);
        if (val == null) return 1;
        try {
            double d = Double.parseDouble(val.toString());
            return (d >= min && d <= max) ? 0 : 1;
        } catch (NumberFormatException e) { return 1; }
    }

    /** 融合感知面板：聚合遥测 + 视觉 + 语音 + 告警 + 健康分 */
    @RequirePermission("device:read")
    @GetMapping("/{deviceId}/perception")
    public Result<Map<String, Object>> perception(@PathVariable String deviceId) {
        Device device = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId).eq(Device::getDeleted, false).one();
        if (device == null) return Result.error("设备不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", device.getDeviceId());
        result.put("deviceName", device.getName());
        result.put("status", device.getStatus());
        result.put("lastHeartbeatAt", device.getLastHeartbeatAt() != null ? device.getLastHeartbeatAt().toString() : null);
        result.put("healthScore", device.getHealthScore());

        // 遥测快照
        if (device.getLatestData() != null && !device.getLatestData().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> telemetry = objectMapper.readValue(device.getLatestData(), Map.class);
                result.put("telemetry", telemetry);
            } catch (JsonProcessingException e) {
                result.put("telemetry", null);
            }
        } else {
            result.put("telemetry", null);
        }

        // 最新视觉事件
        VisionEvent ve;
        try {
            ve = visionEventDao.latest(deviceId);
        } catch (DataAccessException e) {
            ve = visionEventService.getOne(
                    new LambdaQueryWrapper<VisionEvent>()
                            .eq(VisionEvent::getDeviceId, deviceId)
                            .orderByDesc(VisionEvent::getOccurredAt).last("LIMIT 1"));
        }
        if (ve != null) {
            Map<String, Object> veMap = new LinkedHashMap<>();
            veMap.put("eventType", EventTextNormalizer.normalize(ve.getEventType()));
            veMap.put("confidence", ve.getConfidence());
            veMap.put("snapshotRef", ve.getSnapshotRef());
            veMap.put("occurredAt", ve.getOccurredAt() != null ? ve.getOccurredAt().toString() : null);
            result.put("latestVision", veMap);
        } else {
            result.put("latestVision", null);
        }

        // 最新语音事件
        VoiceEvent vo;
        try {
            vo = voiceEventDao.latest(deviceId);
        } catch (DataAccessException e) {
            vo = voiceEventService.getOne(
                    new LambdaQueryWrapper<VoiceEvent>()
                            .eq(VoiceEvent::getDeviceId, deviceId)
                            .orderByDesc(VoiceEvent::getOccurredAt).last("LIMIT 1"));
        }
        if (vo != null) {
            Map<String, Object> voMap = new LinkedHashMap<>();
            voMap.put("type", EventTextNormalizer.normalize(vo.getType()));
            voMap.put("content", EventTextNormalizer.normalize(vo.getContent()));
            voMap.put("source", EventTextNormalizer.normalize(vo.getSource()));
            voMap.put("occurredAt", vo.getOccurredAt() != null ? vo.getOccurredAt().toString() : null);
            result.put("latestVoice", voMap);
        } else {
            result.put("latestVoice", null);
        }

        // 最近告警
        List<AlarmRecord> alarms = alarmRecordService.list(
                new LambdaQueryWrapper<AlarmRecord>()
                        .eq(AlarmRecord::getDeviceId, deviceId)
                        .orderByDesc(AlarmRecord::getStartAt).last("LIMIT 3"));
        List<Map<String, Object>> alarmList = new ArrayList<>();
        for (AlarmRecord a : alarms) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("type", a.getType());
            am.put("level", a.getLevel());
            am.put("startAt", a.getStartAt() != null ? a.getStartAt().toString() : null);
            am.put("recoverAt", a.getRecoverAt() != null ? a.getRecoverAt().toString() : null);
            alarmList.add(am);
        }
        result.put("recentAlarms", alarmList);

        return Result.success(result);
    }

    /**
     * 故障模拟 — 向随机（或指定）在线设备发布异常遥测数据，触发 FAULT 告警。
     * 连续发布 2 条异常遥测以满足连续计数防抖阈值。
     */
    @RequirePermission("device:control")
    @PostMapping("/fault-simulate")
    public Result<Map<String, Object>> faultSimulate(@RequestParam(required = false) String deviceId) {
        // 选取目标设备
        Device target;
        if (deviceId != null && !deviceId.isBlank()) {
            target = deviceService.lambdaQuery()
                    .eq(Device::getDeviceId, deviceId)
                    .eq(Device::getDeleted, false).one();
            if (target == null) return Result.error("设备不存在: " + deviceId);
        } else {
            List<Device> onlineDevices = deviceService.lambdaQuery()
                    .eq(Device::getEnabled, true)
                    .eq(Device::getDeleted, false)
                    .eq(Device::getStatus, 1)
                    .last("LIMIT 50").list();
            if (onlineDevices.isEmpty()) {
                return Result.error("无在线设备可用于故障模拟");
            }
            target = onlineDevices.get(new Random().nextInt(onlineDevices.size()));
        }

        // 构造异常遥测 JSON（所有传感器值设为 999，明显超出正常范围）
        Map<String, Object> faultTelemetry = new LinkedHashMap<>();
        faultTelemetry.put("deviceId", target.getDeviceId());
        faultTelemetry.put("illuminance", 999);
        faultTelemetry.put("temperature", 999);
        faultTelemetry.put("humidity", 999);
        faultTelemetry.put("pm25", 999);
        faultTelemetry.put("aqi", 999);
        faultTelemetry.put("pir", 999);
        faultTelemetry.put("trafficFlow", 999);
        faultTelemetry.put("collectedAt", LocalDateTime.now().toString());

        String topic = (target.getTopicPrefix() != null ? target.getTopicPrefix() : "streetlight")
                + "/" + target.getDeviceId() + "/telemetry";
        String payload;
        try {
            payload = objectMapper.writeValueAsString(faultTelemetry);
        } catch (JsonProcessingException e) {
            return Result.error("JSON 序列化失败: " + e.getMessage());
        }

        // 连续发布 2 条以满足连续异常阈值
        mqttPublisher.publish(topic, payload, 1);
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        mqttPublisher.publish(topic, payload, 1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", target.getDeviceId());
        result.put("deviceName", target.getName());
        result.put("topic", topic);
        result.put("faultValues", faultTelemetry);
        log.info("[故障模拟] 设备={}, 异常遥测已发布 (×2)", target.getDeviceId());
        return Result.success(result);
    }
}
