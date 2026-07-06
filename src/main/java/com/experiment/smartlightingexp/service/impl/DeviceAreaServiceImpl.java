package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.entity.DeviceArea;
import com.experiment.smartlightingexp.mapper.DeviceAreaMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.service.DeviceAreaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 设备分区服务实现。
 */
@Service
@RequiredArgsConstructor
public class DeviceAreaServiceImpl extends ServiceImpl<DeviceAreaMapper, DeviceArea> implements DeviceAreaService {

    private final DeviceMapper deviceMapper;

    @Override
    public List<Map<String, Object>> getAreaTree() {
        // 查所有启用的区域
        List<DeviceArea> all = lambdaQuery()
                .eq(DeviceArea::getEnabled, true)
                .orderByAsc(DeviceArea::getCreateTime)
                .list();

        // 按 parentId 分组
        Map<Long, List<DeviceArea>> grouped = all.stream()
                .filter(a -> a.getParentId() != null)
                .collect(Collectors.groupingBy(DeviceArea::getParentId));

        // 构建顶级节点
        List<Map<String, Object>> tree = new ArrayList<>();
        for (DeviceArea area : all) {
            if (area.getParentId() == null) {
                tree.add(buildNode(area, grouped));
            }
        }
        return tree;
    }

    /** 递归构建树节点。 */
    private Map<String, Object> buildNode(DeviceArea area, Map<Long, List<DeviceArea>> grouped) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", area.getId());
        node.put("name", area.getName());
        node.put("description", area.getDescription());
        node.put("parentId", area.getParentId());

        List<DeviceArea> children = grouped.get(area.getId());
        if (children != null && !children.isEmpty()) {
            List<Map<String, Object>> childNodes = children.stream()
                    .map(child -> buildNode(child, grouped))
                    .collect(Collectors.toList());
            node.put("children", childNodes);
        }
        return node;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteArea(Long id) {
        // 检查是否有子区域
        long childCount = count(new LambdaQueryWrapper<DeviceArea>()
                .eq(DeviceArea::getParentId, id));
        if (childCount > 0) {
            throw new IllegalStateException("该区域下存在 " + childCount + " 个子区域，请先删除子区域");
        }

        // 检查是否有设备引用
        long deviceCount = deviceMapper.selectCount(new LambdaQueryWrapper<Device>()
                .eq(Device::getAreaId, id)
                .eq(Device::getDeleted, false));
        if (deviceCount > 0) {
            throw new IllegalStateException("该区域下存在 " + deviceCount + " 台设备，请先移除设备区域关联");
        }

        removeById(id);
    }
}
