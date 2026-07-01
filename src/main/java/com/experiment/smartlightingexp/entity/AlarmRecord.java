package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("alarm_record")
public class AlarmRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceId;

    private String type;

    private String level;

    private String status;

    private String reason;

    private LocalDateTime startAt;

    private LocalDateTime recoverAt;

    private String handler;
}
