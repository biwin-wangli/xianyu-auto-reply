package com.xianyu.autoreply.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.repository.CookieRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 订单状态处理器（简化版）
 * 对应Python的OrderStatusHandler类
 * 用于处理系统消息和红色提醒消息中的订单状态更新
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusHandler {
    
    private final CookieRepository cookieRepository;
    
    // 线程锁，用于保护并发访问
    private final ReentrantLock lock = new ReentrantLock();
    
    // 状态映射
    private static final Map<String, String> STATUS_MAPPING = new HashMap<String, String>() {{
        put("processing", "处理中");
        put("pending_ship", "待发货");
        put("shipped", "已发货");
        put("completed", "已完成");
        put("refunding", "退款中");
        put("cancelled", "已关闭");
    }};
    
    // 消息类型与状态的映射
    private static final Map<String, String> MESSAGE_STATUS_MAPPING = new HashMap<String, String>() {{
        put("[买家确认收货，交易成功]", "completed");
        put("[你已确认收货，交易成功]", "completed");
        put("[你已发货]", "shipped");
        put("你已发货", "shipped");
        put("[你已发货，请等待买家确认收货]", "shipped");
        put("[我已付款，等待你发货]", "pending_ship");
        put("[我已拍下，待付款]", "processing");
        put("[买家已付款]", "pending_ship");
        put("[付款完成]", "pending_ship");
        put("[已付款，待发货]", "pending_ship");
        put("[退款成功，钱款已原路退返]", "cancelled");
        put("[你关闭了订单，钱款已原路退返]", "cancelled");
    }};
    
    /**
     * 处理系统消息并更新订单状态
     * 对应Python: handle_system_message()
     * 
     * @param message 原始消息数据
     * @param sendMessage 消息内容
     * @param cookieId Cookie ID
     * @param msgTime 消息时间
     * @return true=处理了订单状态更新，false=未处理
     */
    public boolean handleSystemMessage(JSONObject message, String sendMessage, String cookieId, String msgTime) {
        lock.lock();
        try {
            // 检查消息是否在映射表中
            if (!MESSAGE_STATUS_MAPPING.containsKey(sendMessage)) {
                return false;
            }
            
            String newStatus = MESSAGE_STATUS_MAPPING.get(sendMessage);
            
            // 提取订单ID
            String orderId = extractOrderId(message);
            if (orderId == null) {
                log.warn("[{}] 【{}】{}，无法提取订单ID，跳过处理", msgTime, cookieId, sendMessage);
                return false;
            }
            
            // 更新订单状态（简化版 - 实际项目中应调用数据库）
            log.info("[{}] 【{}】{}，订单 {} 状态应更新为{}", 
                msgTime, cookieId, sendMessage, orderId, STATUS_MAPPING.get(newStatus));
            
            // 实际项目应调用: db_manager.insert_or_update_order(order_id, order_status, cookie_id)
            
            return true;
            
        } catch (Exception e) {
            log.error("[{}] 【{}】处理系统消息订单状态更新时出错: {}", msgTime, cookieId, e.getMessage(), e);
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 处理红色提醒消息并更新订单状态
     * 对应Python: handle_red_reminder_message()
     * 
     * @param message 原始消息数据
     * @param redReminder 红色提醒内容
     * @param userId 用户ID
     * @param cookieId Cookie ID
     * @param msgTime 消息时间
     * @return true=处理了订单状态更新，false=未处理
     */
    public boolean handleRedReminderMessage(JSONObject message, String redReminder, String userId, 
                                           String cookieId, String msgTime) {
        lock.lock();
        try {
            // 只处理交易关闭的情况
            if (!"交易关闭".equals(redReminder)) {
                return false;
            }
            
            // 提取订单ID
            String orderId = extractOrderId(message);
            if (orderId == null) {
                log.warn("[{}] 【{}】交易关闭，无法提取订单ID，跳过处理", msgTime, cookieId);
                return false;
            }
            
            // 更新订单状态为已关闭
            log.info("[{}] 【{}】交易关闭，订单 {} 状态应更新为已关闭", msgTime, cookieId, orderId);
            
            // 实际项目应调用: db_manager.insert_or_update_order(order_id, order_status='cancelled', cookie_id)
            
            return true;
            
        } catch (Exception e) {
            log.error("[{}] 【{}】处理红色提醒消息时出错: {}", msgTime, cookieId, e.getMessage(), e);
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 从消息中提取订单ID
     * 对应Python: extract_order_id()
     * 
     * @param message 消息对象
     * @return 订单ID，提取失败返回null
     */
    private String extractOrderId(JSONObject message) {
        try {
            // 方法1: 从button的targetUrl中提取orderId
            if (message.containsKey("1") && message.get("1") instanceof JSONObject) {
                JSONObject message1 = message.getJSONObject("1");
                if (message1.containsKey("6") && message1.get("6") instanceof JSONObject) {
                    JSONObject message16 = message1.getJSONObject("6");
                    if (message16.containsKey("3") && message16.get("3") instanceof JSONObject) {
                        JSONObject message163 = message16.getJSONObject("3");
                        String contentJsonStr = message163.getString("5");
                        if (contentJsonStr != null) {
                            try {
                                JSONObject contentData = JSON.parseObject(contentJsonStr);
                                
                                // 从button的targetUrl提取
                                String targetUrl = contentData.getJSONObject("dxCard")
                                    .getJSONObject("item")
                                    .getJSONObject("main")
                                    .getJSONObject("exContent")
                                    .getJSONObject("button")
                                    .getString("targetUrl");
                                    
                                if (targetUrl != null) {
                                    Pattern pattern = Pattern.compile("orderId=(\\d+)");
                                    Matcher matcher = pattern.matcher(targetUrl);
                                    if (matcher.find()) {
                                        return matcher.group(1);
                                    }
                                }
                            } catch (Exception e) {
                                // 忽略解析错误，继续尝试其他方法
                            }
                        }
                    }
                }
            }
            
            // 方法2: 在整个消息字符串中搜索订单ID模式
            String messageStr = message.toJSONString();
            String[] patterns = {
                "orderId[=:](\\d{10,})",
                "order_detail\\?id=(\\d{10,})",
                "\"id\"\\s*:\\s*\"?(\\d{10,})\"?",
                "bizOrderId[=:](\\d{10,})"
            };
            
            for (String patternStr : patterns) {
                Pattern pattern = Pattern.compile(patternStr);
                Matcher matcher = pattern.matcher(messageStr);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            
            return null;
            
        } catch (Exception e) {
            log.error("提取订单ID失败: {}", e.getMessage());
            return null;
        }
    }
}
