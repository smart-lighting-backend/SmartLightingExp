package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("device_credential")
public class DeviceCredential {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceId;

    private String username;

    private String passwordHash;

    private String factorySerialEncrypted;

    private String deviceIdCodeEncrypted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
