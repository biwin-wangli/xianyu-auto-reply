package com.xianyu.autoreply.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.xianyu.autoreply.entity.MessageNotification;
import com.xianyu.autoreply.entity.NotificationChannel;
import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.repository.MessageNotificationRepository;
import com.xianyu.autoreply.repository.NotificationChannelRepository;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping
public class NotificationController {

    private final NotificationChannelRepository channelRepository;
    private final MessageNotificationRepository notificationRepository;
    private final CookieRepository cookieRepository;

    @Autowired
    public NotificationController(NotificationChannelRepository channelRepository,
                                  MessageNotificationRepository notificationRepository,
                                  CookieRepository cookieRepository) {
        this.channelRepository = channelRepository;
        this.notificationRepository = notificationRepository;
        this.cookieRepository = cookieRepository;
    }

    // ------------------------- 通知渠道接口 -------------------------

    @GetMapping("/notification-channels")
    public List<NotificationChannel> getAllChannels() {
        return channelRepository.findAll();
    }

    @PostMapping("/notification-channels")
    public NotificationChannel createChannel(@RequestBody ChannelRequest request) {
        NotificationChannel channel = new NotificationChannel();
        channel.setName(request.getName());
        channel.setType(request.getType());
        // Frontend sends config as object, we store as JSON string
        channel.setConfig(JSONUtil.toJsonStr(request.getConfig()));
        channel.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        return channelRepository.save(channel);
    }

    @GetMapping("/notification-channels/{id}")
    public NotificationChannel getChannel(@PathVariable Long id) {
        return channelRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Channel not found"));
    }

    @PutMapping("/notification-channels/{id}")
    public NotificationChannel updateChannel(@PathVariable Long id, @RequestBody ChannelRequest request) {
        NotificationChannel channel = channelRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Channel not found"));
        
        if (request.getName() != null) channel.setName(request.getName());
        if (request.getType() != null) channel.setType(request.getType());
        if (request.getConfig() != null) channel.setConfig(JSONUtil.toJsonStr(request.getConfig()));
        if (request.getEnabled() != null) channel.setEnabled(request.getEnabled());
        
        return channelRepository.save(channel);
    }

    @DeleteMapping("/notification-channels/{id}")
    public Map<String, String> deleteChannel(@PathVariable Long id) {
        if (!channelRepository.existsById(id)) {
             throw new RuntimeException("Channel not found");
        }
        channelRepository.deleteById(id);
        return Map.of("msg", "notification channel deleted");
    }

    // ------------------------- 消息通知配置接口 -------------------------

    @GetMapping("/message-notifications")
    public Map<String, List<MessageNotification>> getAllMessageNotifications() {
        // Only return notifications for current user's cookies
        // In this migration we assume single user or handle filtering by cookies
        
        // 1. Get all cookies for current user (Mocking user_id=1 for now as per Auth logic limitation)
        List<Cookie> userCookies = cookieRepository.findAll(); // Should filter by user_id logic if multi-user
        List<String> cookieIds = userCookies.stream().map(Cookie::getId).collect(Collectors.toList());
        
        // 2. We don't have a direct "findAllByUser" for notifications easily without Join
        // So we iterate valid cookies
        Map<String, List<MessageNotification>> result = new HashMap<>();
        for (String cid : cookieIds) {
            List<MessageNotification> notifs = notificationRepository.findByCookieId(cid);
            if (!notifs.isEmpty()) {
                result.put(cid, notifs);
            }
        }
        return result;
    }

    @GetMapping("/message-notifications/{cid}")
    public List<MessageNotification> getAccountNotifications(@PathVariable String cid) {
        return notificationRepository.findByCookieId(cid);
    }

    @PostMapping("/message-notifications/{cid}")
    public Map<String, String> setMessageNotification(@PathVariable String cid, @RequestBody NotificationRequest request) {
        // Check channel exists
        if (!channelRepository.existsById(request.getChannel_id())) {
            throw new RuntimeException("Channel not found");
        }

        // Check if exists
        MessageNotification notification = notificationRepository
                .findByCookieIdAndChannelId(cid, request.getChannel_id())
                .orElse(new MessageNotification());

        if (notification.getId() == null) {
            notification.setCookieId(cid);
            notification.setChannelId(request.getChannel_id());
        }
        
        notification.setEnabled(request.getEnabled());
        notificationRepository.save(notification);

        return Map.of("msg", "message notification set");
    }

    @Transactional
    @DeleteMapping("/message-notifications/account/{cid}")
    public Map<String, String> deleteAccountNotifications(@PathVariable String cid) {
        notificationRepository.deleteByCookieId(cid);
        return Map.of("msg", "account notifications deleted");
    }

    @DeleteMapping("/message-notifications/{id}")
    public Map<String, String> deleteMessageNotification(@PathVariable Long id) {
        if (!notificationRepository.existsById(id)) {
            throw new RuntimeException("Notification config not found");
        }
        notificationRepository.deleteById(id);
        return Map.of("msg", "message notification deleted");
    }

    @Data
    public static class ChannelRequest {
        private String name;
        private String type;
        private Object config; // Map or Object
        private Boolean enabled;
    }

    @Data
    public static class NotificationRequest {
        private Long channel_id;
        private Boolean enabled;
    }
}
