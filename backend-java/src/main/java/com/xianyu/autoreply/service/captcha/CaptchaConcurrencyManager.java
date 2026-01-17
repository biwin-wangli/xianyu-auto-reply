package com.xianyu.autoreply.service.captcha;

import com.xianyu.autoreply.service.captcha.model.CaptchaResult;
import com.xianyu.autoreply.service.captcha.model.TrajectoryPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 滑块验证并发管理器
 */
@Slf4j
@Component
public class CaptchaConcurrencyManager {
    
    private final int maxConcurrent = 3;  // 最大并发数
    private final Semaphore semaphore = new Semaphore(maxConcurrent);
    private final Map<String, Long> activeSlots = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * 获取滑块验证槽位
     */
    public boolean acquireSlot(String userId, int timeoutSeconds) {
        try {
            log.info("【{}】请求滑块验证槽位，当前活跃: {}/{}", userId, activeSlots.size(), maxConcurrent);
            
            boolean acquired = semaphore.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
            
            if (acquired) {
                activeSlots.put(userId, System.currentTimeMillis());
                log.info("【{}】已获取滑块验证槽位，当前活跃: {}/{}", userId, activeSlots.size(), maxConcurrent);
                return true;
            } else {
                log.warn("【{}】获取滑块验证槽位超时", userId);
                return false;
            }
        } catch (InterruptedException e) {
            log.error("【{}】获取槽位被中断", userId, e);
            return false;
        }
    }
    
    /**
     * 释放滑块验证槽位
     */
    public void releaseSlot(String userId) {
        activeSlots.remove(userId);
        semaphore.release();
        log.info("【{}】已释放滑块验证槽位，当前活跃: {}/{}", userId, activeSlots.size(), maxConcurrent);
    }
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("maxConcurrent", maxConcurrent);
        stats.put("activeCount", activeSlots.size());
        stats.put("availableSlots", maxConcurrent - activeSlots.size());
        return stats;
    }
}
