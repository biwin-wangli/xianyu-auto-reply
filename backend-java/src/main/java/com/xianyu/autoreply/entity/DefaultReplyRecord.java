package com.xianyu.autoreply.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "default_reply_records", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"cookie_id", "chat_id"})
})
public class DefaultReplyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cookie_id", nullable = false)
    private String cookieId;

    @Column(name = "chat_id", nullable = false)
    private String chatId;

    @CreationTimestamp
    @Column(name = "replied_at", updatable = false)
    private LocalDateTime repliedAt;
}
