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
@Table(name = "item_info")
public class ItemInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cookie_id", nullable = false)
    @JsonProperty("cookie_id")
    private String cookieId;

    @Column(name = "item_id", nullable = false)
    @JsonProperty("item_id")
    private String itemId;

    @Column(name = "item_title")
    @JsonProperty("item_title")
    private String itemTitle;

    @Column(name = "item_description", columnDefinition = "TEXT")
    @JsonProperty("item_description")
    private String itemDescription;

    @Column(name = "item_category")
    @JsonProperty("item_category")
    private String itemCategory;

    @Column(name = "item_price")
    @JsonProperty("item_price")
    private String itemPrice;

    @Column(name = "item_detail", columnDefinition = "TEXT")
    @JsonProperty("item_detail")
    private String itemDetail;

    @Column(name = "is_multi_spec")
    @ColumnDefault("false")
    @JsonProperty("is_multi_spec")
    private Boolean isMultiSpec = false;

    @Column(name = "multi_quantity_delivery")
    @ColumnDefault("false")
    @JsonProperty("multi_quantity_delivery")
    private Boolean multiQuantityDelivery = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
}
