package com.xianyu.autoreply.model;

import lombok.Data;

/**
 * 商品详情缓存
 * 用于缓存商品详情，减少重复的网络请求
 * 支持24小时TTL和LRU淘汰策略
 */
@Data
public class ItemDetailCache {
    
    /**
     * 商品详情内容
     */
    private String detail;
    
    /**
     * 缓存创建时间（时间戳，毫秒）
     */
    private long timestamp;
    
    /**
     * 最后访问时间（时间戳，毫秒）
     * 用于LRU淘汰策略
     */
    private long accessTime;
    
    /**
     * 创建一个新的商品详情缓存
     * 
     * @param detail 商品详情内容
     */
    public ItemDetailCache(String detail) {
        this.detail = detail;
        long currentTime = System.currentTimeMillis();
        this.timestamp = currentTime;
        this.accessTime = currentTime;
    }
    
    /**
     * 默认构造函数
     */
    public ItemDetailCache() {
        this.detail = "";
        long currentTime = System.currentTimeMillis();
        this.timestamp = currentTime;
        this.accessTime = currentTime;
    }
    
    /**
     * 更新访问时间（用于LRU）
     */
    public void updateAccessTime() {
        this.accessTime = System.currentTimeMillis();
    }
    
    /**
     * 检查缓存是否过期
     * 
     * @param ttlSeconds TTL时长（秒）
     * @return true表示已过期，false表示未过期
     */
    public boolean isExpired(int ttlSeconds) {
        long currentTime = System.currentTimeMillis();
        return (currentTime - this.timestamp) >= (ttlSeconds * 1000L);
    }
}
