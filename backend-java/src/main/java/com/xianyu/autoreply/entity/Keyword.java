package com.xianyu.autoreply.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "keywords")
public class Keyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // Synthetic ID, as original table didn't have one but needed for JPA

    @Column(name = "cookie_id")
    private String cookieId;

    private String keyword;

    private String reply;

    @Column(name = "item_id")
    private String itemId;

    @Column(length = 20)
    private String type = "text";

    @Column(name = "image_url")
    private String imageUrl;
}
