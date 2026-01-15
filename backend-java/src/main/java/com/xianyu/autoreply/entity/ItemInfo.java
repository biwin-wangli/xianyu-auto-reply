package com.xianyu.autoreply.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "item_info", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"cookie_id", "item_id"})
})
public class ItemInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cookie_id", nullable = false)
    private String cookieId;

    @Column(name = "item_id", nullable = false)
    private String itemId;

    @Column(name = "item_title")
    private String itemTitle;

    @Column(name = "item_description", columnDefinition = "TEXT")
    private String itemDescription;

    @Column(name = "item_category")
    private String itemCategory;

    @Column(name = "item_price")
    private String itemPrice;

    @Column(name = "item_detail", columnDefinition = "TEXT")
    private String itemDetail;

    @Column(name = "is_multi_spec")
    private Boolean isMultiSpec = false;
    
    @Column(name = "multi_quantity_delivery")
    private Boolean multiQuantityDelivery = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
