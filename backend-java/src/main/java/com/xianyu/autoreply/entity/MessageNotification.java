package com.xianyu.autoreply.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "message_notifications")
public class MessageNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cookie_id", nullable = false)
    private String cookieId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(nullable = false)
    @ColumnDefault("true")
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    // Optional: ManyToOne relation to channel for easy fetching
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "channel_id", insertable = false, updatable = false)
    private NotificationChannel channel;
}
