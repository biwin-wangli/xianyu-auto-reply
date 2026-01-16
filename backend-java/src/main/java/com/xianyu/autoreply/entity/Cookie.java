package com.xianyu.autoreply.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "cookies")
public class Cookie {

    @Id
    private String id; // cookie_id

    @Column(nullable = false, length = 10000) // cookies can be long
    private String value;

    @Column(name = "user_id", nullable = false)
    @JsonProperty("user_id")
    private Long userId;

    @Column(name = "auto_confirm")
    @ColumnDefault("1")
    @JsonProperty("auto_confirm")
    private Integer autoConfirm = 1;

    @ColumnDefault("''")
    private String remark;

    @Column(name = "pause_duration")
    @ColumnDefault("10")
    @JsonProperty("pause_duration")
    private Integer pauseDuration = 10;

    @ColumnDefault("''")
    private String username;

    @ColumnDefault("''")
    private String password;

    @Column(name = "show_browser")
    @ColumnDefault("0")
    @JsonProperty("show_browser")
    private Integer showBrowser = 0;

    @Column(name = "enabled")
    @ColumnDefault("true")
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    // We can add a ManyToOne relationship if needed, but for now keeping it simple with IDs
    // @ManyToOne(fetch = FetchType.LAZY)
    // @JoinColumn(name = "user_id", insertable = false, updatable = false)
    // private User user;
}
