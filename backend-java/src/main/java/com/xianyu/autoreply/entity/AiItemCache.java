package com.xianyu.autoreply.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_item_cache")
public class AiItemCache {

    @Id
    @Column(name = "item_id")
    private String itemId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String data;

    private Double price;

    @Column(columnDefinition = "TEXT")
    private String description;

    @UpdateTimestamp
    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;
}
