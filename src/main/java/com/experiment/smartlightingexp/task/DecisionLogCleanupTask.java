package com.experiment.smartlightingexp.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.experiment.smartlightingexp.entity.DecisionLog;
import com.experiment.smartlightingexp.mapper.DecisionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 决策日志定期清理 — 删除 7 天前的旧记录，防止 decision_log 表无限膨胀。
 * 每天凌晨 3:30 执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionLogCleanupTask {

    private final DecisionLogMapper decisionLogMapper;

    private static final int RETENTION_DAYS = 7;

    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanup() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
            long deleted = decisionLogMapper.delete(
                    new LambdaQueryWrapper<DecisionLog>()
                            .lt(DecisionLog::getCreateTime, cutoff));
            if (deleted > 0) {
                log.info("[DecisionLogCleanup] Deleted {} records older than {} days (before {})",
                        deleted, RETENTION_DAYS, cutoff.toLocalDate());
            }
        } catch (Exception e) {
            log.error("[DecisionLogCleanup] Cleanup failed: {}", e.getMessage());
        }
    }
}
