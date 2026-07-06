package com.experiment.smartlightingexp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.experiment.smartlightingexp.entity.DeviceArea;

import java.util.List;
import java.util.Map;

/**
 * 设备分区服务接口。
 */
public interface DeviceAreaService extends IService<DeviceArea> {

    /**
     * 获取区域树形结构（Map 列表，含 children 嵌套）。
     */
    List<Map<String, Object>> getAreaTree();

    /**
     * 删除区域 — 检查是否被设备引用或存在子区域。
     */
    void deleteArea(Long id);
}
