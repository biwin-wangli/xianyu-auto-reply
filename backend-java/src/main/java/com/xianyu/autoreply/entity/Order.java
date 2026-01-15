package com.xianyu.autoreply.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @Column(name = "order_id")
    private String orderId;

    @Column(name = "item_id")
    private String itemId;

    @Column(name = "buyer_id")
    private String buyerId;

    @Column(name = "spec_name")
    private String specName;

    @Column(name = "spec_value")
    private String specValue;

    private String quantity;

    private String amount;

    @Column(name = "order_status")
    private String orderStatus = "unknown";

    @Column(name = "cookie_id")
    private String cookieId;

    @Column(name = "is_bargain")
    private Integer isBargain = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
