package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("decision_log")
public class DecisionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceId;

    private String inputSnapshot;

    private String matchedPolicy;

    private String actionTaken;

    private String result;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
