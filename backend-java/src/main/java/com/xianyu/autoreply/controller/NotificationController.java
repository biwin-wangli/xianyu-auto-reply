package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.entity.MessageNotification;
import com.xianyu.autoreply.entity.NotificationChannel;
import com.xianyu.autoreply.repository.MessageNotificationRepository;
import com.xianyu.autoreply.repository.NotificationChannelRepository;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping
public class NotificationController {

    private final NotificationChannelRepository channelRepository;
    private final MessageNotificationRepository notificationRepository;

    @Autowired
    public NotificationController(NotificationChannelRepository channelRepository,
                                  MessageNotificationRepository notificationRepository) {
        this.channelRepository = channelRepository;
        this.notificationRepository = notificationRepository;
    }

    // Channels
    @GetMapping("/notification-channels")
    public List<NotificationChannel> listChannels() {
        return channelRepository.findAll();
    }

    @PostMapping("/notification-channels")
    public NotificationChannel createChannel(@RequestBody ChannelIn input) {
        NotificationChannel channel = new NotificationChannel();
        channel.setName(input.getName());
        channel.setType(input.getType());
        channel.setConfig(input.getConfig());
        channel.setEnabled(true);
        channel.setUserId(1L); // Default
        channel.setCreatedAt(LocalDateTime.now());
        channel.setUpdatedAt(LocalDateTime.now());
        return channelRepository.save(channel);
    }

    @PutMapping("/notification-channels/{id}")
    public NotificationChannel updateChannel(@PathVariable Long id, @RequestBody ChannelUpdate input) {
        NotificationChannel channel = channelRepository.findById(id).orElseThrow();
        channel.setName(input.getName());
        channel.setConfig(input.getConfig());
        channel.setEnabled(input.isEnabled());
        channel.setUpdatedAt(LocalDateTime.now());
        return channelRepository.save(channel);
    }

    @DeleteMapping("/notification-channels/{id}")
    public void deleteChannel(@PathVariable Long id) {
        channelRepository.deleteById(id);
    }

    // Notifications
    @GetMapping("/message-notifications")
    public List<MessageNotification> listNotifications() {
        return notificationRepository.findAll();
    }

    @GetMapping("/message-notifications/{cookieId}")
    public List<MessageNotification> listAccountNotifications(@PathVariable String cookieId) {
        // Need custom query in repository
        // For now, filtering all. 
        // Real impl: notificationRepository.findByCookieId(cookieId)
        return notificationRepository.findAll().stream()
                .filter(n -> n.getCookieId().equals(cookieId))
                .toList();
    }

    @PostMapping("/message-notifications/{cookieId}")
    public MessageNotification setNotification(@PathVariable String cookieId, @RequestBody NotificationIn input) {
        MessageNotification notif = new MessageNotification();
        notif.setCookieId(cookieId);
        notif.setChannelId(input.getChannelId());
        notif.setEnabled(input.isEnabled());
        notif.setCreatedAt(LocalDateTime.now());
        notif.setUpdatedAt(LocalDateTime.now());
        return notificationRepository.save(notif);
    }

    @DeleteMapping("/message-notifications/{id}")
    public void deleteNotification(@PathVariable Long id) {
        notificationRepository.deleteById(id);
    }

    @Data
    public static class ChannelIn {
        private String name;
        private String type;
        private String config;
    }

    @Data
    public static class ChannelUpdate {
        private String name;
        private String config;
        private boolean enabled;
    }

    @Data
    public static class NotificationIn {
        private Long channelId;
        private boolean enabled;
    }
}
