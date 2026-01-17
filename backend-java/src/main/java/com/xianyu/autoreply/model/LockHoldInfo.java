package com.xianyu.autoreply.model;

import lombok.Data;
import java.util.concurrent.CompletableFuture;

/**
 * 锁持有信息
 * 用于跟踪订单锁的持有状态和延迟释放任务
 */
@Data
public class LockHoldInfo {
    
    /**
     * 锁是否被持有
     */
    private boolean locked;
    
    /**
     * 锁获取时间（时间戳，毫秒）
     */
    private long lockTime;
    
    /**
     * 锁释放时间（时间戳，毫秒），null表示尚未释放
     */
    private Long releaseTime;
    
    /**
     * 延迟释放任务
     */
    private CompletableFuture<Void> task;
    
    /**
     * 创建一个新的锁持有信息
     * 
     * @param locked 是否持有
     * @param lockTime 锁获取时间
     */
    public LockHoldInfo(boolean locked, long lockTime) {
        this.locked = locked;
        this.lockTime = lockTime;
        this.releaseTime = null;
        this.task = null;
    }
    
    /**
     * 默认构造函数
     */
    public LockHoldInfo() {
        this.locked = false;
        this.lockTime = System.currentTimeMillis();
        this.releaseTime = null;
        this.task = null;
    }
}
