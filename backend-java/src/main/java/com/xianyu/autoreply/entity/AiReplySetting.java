package com.xianyu.autoreply.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_reply_settings")
public class AiReplySetting {

    @Id
    @Column(name = "cookie_id")
    private String cookieId;

    @Column(name = "ai_enabled")
    private Boolean aiEnabled = false;

    @Column(name = "model_name")
    private String modelName = "qwen-plus";

    @Column(name = "api_key")
    private String apiKey;

    @Column(name = "base_url")
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    @Column(name = "max_discount_percent")
    private Integer maxDiscountPercent = 10;

    @Column(name = "max_discount_amount")
    private Integer maxDiscountAmount = 100;

    @Column(name = "max_bargain_rounds")
    private Integer maxBargainRounds = 3;

    @Column(name = "custom_prompts", columnDefinition = "TEXT")
    private String customPrompts;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
