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
@Table(name = "orders")
public class Order {

    @Id
    @Column(name = "order_id")
    @JsonProperty("order_id")
    private String orderId;

    @Column(name = "item_id")
    @JsonProperty("item_id")
    private String itemId;

    @Column(name = "buyer_id")
    @JsonProperty("buyer_id")
    private String buyerId;

    @Column(name = "spec_name")
    @JsonProperty("spec_name")
    private String specName;

    @Column(name = "spec_value")
    @JsonProperty("spec_value")
    private String specValue;

    @Column(name = "quantity")
    @JsonProperty("quantity")
    private String quantity;

    @Column(name = "amount")
    @JsonProperty("amount")
    private String amount;

    @Column(name = "order_status")
    @ColumnDefault("'unknown'")
    @JsonProperty("order_status")
    private String orderStatus;

    @Column(name = "cookie_id")
    @JsonProperty("cookie_id")
    private String cookieId;

    @Column(name = "is_bargain")
    @ColumnDefault("0")
    @JsonProperty("is_bargain")
    private Integer isBargain = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
}
