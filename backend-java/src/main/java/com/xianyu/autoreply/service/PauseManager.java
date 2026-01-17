package com.xianyu.autoreply.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 暂停管理器
 * 对应Python的AutoReplyPauseManager类
 * 用于管理聊天会话的暂停状态（手动发送消息后暂停10分钟自动回复）
 */
@Slf4j
@Component
public class PauseManager {
    
    // 暂停时长（秒） - 默认10分钟
    private static final long PAUSE_DURATION_SECONDS = 10 * 60;
    
    // 存储暂停的chat_id和暂停结束时间 {chatId: pauseEndTime}
    private final Map<String, Long> pausedChats = new ConcurrentHashMap<>();
    
    /**
     * 暂停指定chat_id的自动回复
     * 对应Python: pause_chat(chat_id, cookie_id)
     * 
     * @param chatId 聊天ID
     * @param cookieId Cookie ID（用于日志）
     */
    public void pauseChat(String chatId, String cookieId) {
        long pauseEndTime = System.currentTimeMillis() + (PAUSE_DURATION_SECONDS * 1000);
        pausedChats.put(chatId, pauseEndTime);
        
        log.info("【{}】已暂停chat_id {} 的自动回复，持续10分钟", cookieId, chatId);
    }
    
    /**
     * 检查chat_id是否处于暂停状态
     * 对应Python: is_chat_paused(chat_id)
     * 
     * @param chatId 聊天ID
     * @return true=暂停中，false=未暂停
     */
    public boolean isChatPaused(String chatId) {
        Long pauseEndTime = pausedChats.get(chatId);
        if (pauseEndTime == null) {
            return false;
        }
        
        // 检查是否已过期
        if (System.currentTimeMillis() >= pauseEndTime) {
            // 已过期，移除
            pausedChats.remove(chatId);
            return false;
        }
        
        return true;
    }
    
    /**
     * 获取剩余暂停时间（秒）
     * 对应Python: get_remaining_pause_time(chat_id)
     * 
     * @param chatId 聊天ID
     * @return 剩余暂停时间（秒），如果未暂停则返回0
     */
    public long getRemainingPauseTime(String chatId) {
        Long pauseEndTime = pausedChats.get(chatId);
        if (pauseEndTime == null) {
            return 0;
        }
        
        long remaining = (pauseEndTime - System.currentTimeMillis()) / 1000;
        return Math.max(0, remaining);
    }
    
    /**
     * 清理过期的暂停记录
     * 对应Python: cleanup_expired_pauses()
     */
    public void cleanupExpiredPauses() {
        long currentTime = System.currentTimeMillis();
        pausedChats.entrySet().removeIf(entry -> currentTime >= entry.getValue());
    }
    
    /**
     * 取消指定chat_id的暂停
     * 
     * @param chatId 聊天ID
     */
    public void resumeChat(String chatId) {
        pausedChats.remove(chatId);
        log.info("已取消chat_id {} 的暂停状态", chatId);
    }
}
