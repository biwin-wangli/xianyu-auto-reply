package com.xianyu.autoreply.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_stats", indexes = {
    @Index(name = "idx_anonymous_id", columnList = "anonymous_id"),
    @Index(name = "idx_last_seen", columnList = "last_seen")
})
public class UserStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "anonymous_id", nullable = false, unique = true)
    private String anonymousId;

    @Column(name = "first_seen", updatable = false)
    @CreationTimestamp
    private LocalDateTime firstSeen;

    @Column(name = "last_seen")
    @UpdateTimestamp
    private LocalDateTime lastSeen;

    private String os;

    private String version;

    @Column(name = "total_reports")
    private Integer totalReports = 1;
}
