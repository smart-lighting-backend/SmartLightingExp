package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("energy_record")
public class EnergyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceId;

    private LocalDate recordDate;

    private Integer onDurationMin;

    private BigDecimal avgBrightness;

    private BigDecimal estimatedKwh;

    private BigDecimal savingRate;

    private BigDecimal carbonReduction;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
