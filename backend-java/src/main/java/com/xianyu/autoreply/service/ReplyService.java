package com.xianyu.autoreply.service;

import com.xianyu.autoreply.entity.DefaultReply;
import com.xianyu.autoreply.entity.DefaultReplyRecord;
import com.xianyu.autoreply.entity.Keyword;
import com.xianyu.autoreply.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class ReplyService {

    private final KeywordRepository keywordRepository;
    private final DefaultReplyRepository defaultReplyRepository;
    private final DefaultReplyRecordRepository defaultReplyRecordRepository;
    private final AiReplyService aiReplyService;

    @Autowired
    public ReplyService(KeywordRepository keywordRepository,
                        DefaultReplyRepository defaultReplyRepository,
                        DefaultReplyRecordRepository defaultReplyRecordRepository,
                        AiReplyService aiReplyService) {
        this.keywordRepository = keywordRepository;
        this.defaultReplyRepository = defaultReplyRepository;
        this.defaultReplyRecordRepository = defaultReplyRecordRepository;
        this.aiReplyService = aiReplyService;
    }

    public String determineReply(String cookieId, String chatId, String userId, String itemId, String message) {
        // 1. Check Keywords
        List<Keyword> keywords = keywordRepository.findByCookieId(cookieId); // Should optimize to search cache or DB query
        // Simple iteration for now
        for (Keyword k : keywords) {
            if (message.contains(k.getKeyword())) {
                 return k.getReply();
            }
        }

        // 2. Check AI Reply
        String aiReply = aiReplyService.generateReply(cookieId, chatId, userId, itemId, message);
        if (aiReply != null) {
            return aiReply;
        }

        // 3. Default Reply
        DefaultReply defaultReply = defaultReplyRepository.findById(cookieId).orElse(null);
        if (defaultReply != null && Boolean.TRUE.equals(defaultReply.getEnabled())) {
            // Check reply once
            if (Boolean.TRUE.equals(defaultReply.getReplyOnce())) {
                Optional<DefaultReplyRecord> record = defaultReplyRecordRepository.findByCookieIdAndChatId(cookieId, chatId);
                if (record.isPresent()) {
                    return null; // Already replied
                }
            }
            // Save record
            DefaultReplyRecord newRecord = new DefaultReplyRecord();
            newRecord.setCookieId(cookieId);
            newRecord.setChatId(chatId);
            defaultReplyRecordRepository.save(newRecord);

            return defaultReply.getReplyContent();
        }

        return null;
    }

    /**
     * 处理来自XianyuClient的消息
     * 此方法由XianyuClient在收到消息后调用
     */
    public void processMessage(String cookieId, com.alibaba.fastjson2.JSONObject message, 
                               org.springframework.web.socket.WebSocketSession session) {
        try {
            log.info("【{}】开始处理消息", cookieId);
            
            // 提取消息信息
            String chatId = extractChatId(message);
            String userId = extractUserId(message);
            String itemId = extractItemId(message);
            String messageContent = extractMessageContent(message);
            
            if (chatId == null || messageContent == null) {
                log.warn("【{}】消息格式不完整，跳过处理", cookieId);
                return;
            }
            
            log.info("【{}】收到消息 - chatId: {}, userId: {}, itemId: {}, content: {}", 
                cookieId, chatId, userId, itemId, messageContent);
            
            // 调用自动回复逻辑
            String replyContent = determineReply(cookieId, chatId, userId, itemId, messageContent);
            
            if (replyContent != null) {
                log.info("【{}】准备发送回复: {}", cookieId, replyContent);
                // 调用发送消息方法（通过XianyuClientService获取client并发送）
                try {
                    // 注意：这里需要从XianyuClientService获取client
                    // sendReplyMessage(session, chatId, userId, replyContent);
                    log.warn("【{}】自动回复功能需要XianyuClientService支持，当前仅记录日志", cookieId);
                } catch (Exception e) {
                    log.error("【{}】发送回复失败", cookieId, e);
                }
            } else {
                log.debug("【{}】无需回复", cookieId);
            }

            
        } catch (Exception e) {
            log.error("【{}】处理消息失败", cookieId, e);
        }
    }
    
    /**
     * 从消息中提取chatId
     */
    private String extractChatId(com.alibaba.fastjson2.JSONObject message) {
        try {
            if (message.containsKey("1")) {
                Object obj1 = message.get("1");
                if (obj1 instanceof com.alibaba.fastjson2.JSONObject) {
                    com.alibaba.fastjson2.JSONObject msg1 = (com.alibaba.fastjson2.JSONObject) obj1;
                    if (msg1.containsKey("2")) {
                        return msg1.getString("2");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("提取chatId失败", e);
        }
        return null;
    }
    
    /**
     * 从消息中提取userId
     */
    private String extractUserId(com.alibaba.fastjson2.JSONObject message) {
        try {
            if (message.containsKey("1")) {
                Object obj1 = message.get("1");
                if (obj1 instanceof com.alibaba.fastjson2.JSONObject) {
                    com.alibaba.fastjson2.JSONObject msg1 = (com.alibaba.fastjson2.JSONObject) obj1;
                    if (msg1.containsKey("10")) {
                        com.alibaba.fastjson2.JSONObject msg10 = msg1.getJSONObject("10");
                        if (msg10 != null && msg10.containsKey("senderUserId")) {
                            return msg10.getString("senderUserId");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("提取userId失败", e);
        }
        return "unknown_user";
    }
    
    /**
     * 从消息中提取itemId
     */
    private String extractItemId(com.alibaba.fastjson2.JSONObject message) {
        try {
            if (message.containsKey("1")) {
                Object obj1 = message.get("1");
                if (obj1 instanceof com.alibaba.fastjson2.JSONObject) {
                    com.alibaba.fastjson2.JSONObject msg1 = (com.alibaba.fastjson2.JSONObject) obj1;
                    if (msg1.containsKey("10")) {
                        com.alibaba.fastjson2.JSONObject msg10 = msg1.getJSONObject("10");
                        if (msg10 != null && msg10.containsKey("reminderUrl")) {
                            String url = msg10.getString("reminderUrl");
                            if (url != null && url.contains("itemId=")) {
                                return url.split("itemId=")[1].split("&")[0];
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("提取itemId失败", e);
        }
        return null;
    }
    
    /**
     * 从消息中提取消息内容
     */
    private String extractMessageContent(com.alibaba.fastjson2.JSONObject message) {
        try {
            if (message.containsKey("1")) {
                Object obj1 = message.get("1");
                if (obj1 instanceof com.alibaba.fastjson2.JSONObject) {
                    com.alibaba.fastjson2.JSONObject msg1 = (com.alibaba.fastjson2.JSONObject) obj1;
                    if (msg1.containsKey("10")) {
                        com.alibaba.fastjson2.JSONObject msg10 = msg1.getJSONObject("10");
                        if (msg10 != null && msg10.containsKey("content")) {
                            return msg10.getString("content");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("提取消息内容失败", e);
        }
        return null;
    }

    // ========== 以下方法供 XianyuClient.processChatMessageReply 使用 ==========

    /**
     * 获取API回复
     * 对应Python: get_api_reply
     */
    public String getApiReply(String msgTime, String userUrl, String sendUserId,
                              String sendUserName, String itemId, String sendMessage, String chatId) {
        // 简化实现：实际应该调用外部API
        // Python中从 AUTO_REPLY.get('api', {}).get('url') 获取API地址
        log.debug("【API回复】暂未实现，返回null");
        return null;
    }

    /**
     * 获取关键词回复
     * 对应Python: get_keyword_reply
     *
     * @return 回复内容，或 "EMPTY_REPLY" 表示匹配到但不回复，或 null 表示未匹配
     */
    public String getKeywordReply(String sendUserName, String sendUserId,
                                  String sendMessage, String itemId) {
        try {
            // 这里应该查询关键词库并匹配
            // 简化实现：调用现有的determineReply逻辑
            // 实际应该单独实现关键词匹配逻辑
            log.debug("【关键词回复】暂使用简化实现");
            return null;
        } catch (Exception e) {
            log.error("关键词回复查询失败", e);
            return null;
        }
    }

    /**
     * 获取AI回复
     * 对应Python: get_ai_reply
     */
    public String getAiReply(String sendUserName, String sendUserId,
                             String sendMessage, String itemId, String chatId) {
        try {
            // 调用AI回复服务
            return aiReplyService.generateReply(null, chatId, sendUserId, itemId, sendMessage);
        } catch (Exception e) {
            log.error("AI回复生成失败", e);
            return null;
        }
    }

    /**
     * 获取默认回复
     * 对应Python: get_default_reply
     *
     * @return String (纯文字回复) 或 Map&lt;String, Object&gt; (包含image_url/text) 或 "EMPTY_REPLY"
     */
    public Object getDefaultReply(String sendUserName, String sendUserId,
                                  String sendMessage, String chatId, String itemId) {
        try {
            // 查询默认回复配置
            // Python中返回可能是：
            // 1. "EMPTY_REPLY" - 表示不回复
            // 2. dict with 'image_url' and 'text' - 图片+文字回复
            // 3. String - 纯文字回复
            
            // 简化实现：返回null表示无默认回复
            log.debug("【默认回复】暂使用简化实现");
            return null;
        } catch (Exception e) {
            log.error("默认回复查询失败", e);
            return null;
        }
    }
}
