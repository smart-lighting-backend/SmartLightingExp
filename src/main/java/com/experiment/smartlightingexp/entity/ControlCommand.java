package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_command")
public class ControlCommand {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceId;

    private String action;

    private Integer brightness;

    private String source;

    private String operator;

    private String status;

    private LocalDateTime issuedAt;

    private LocalDateTime ackAt;

    private String resultDetail;
}
