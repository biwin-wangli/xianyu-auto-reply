package com.xianyu.autoreply.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "cards")
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type; // api, text, data, image

    @Column(name = "api_config", columnDefinition = "TEXT")
    private String apiConfig;

    @Column(name = "text_content", columnDefinition = "TEXT")
    private String textContent;

    @Column(name = "data_content", columnDefinition = "TEXT")
    private String dataContent;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(columnDefinition = "TEXT")
    private String description;

    private Boolean enabled = true;

    @Column(name = "delay_seconds")
    private Integer delaySeconds = 0;

    @Column(name = "is_multi_spec")
    private Boolean isMultiSpec = false;

    @Column(name = "spec_name")
    private String specName;

    @Column(name = "spec_value")
    private String specValue;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
