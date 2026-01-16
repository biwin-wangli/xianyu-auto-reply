package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByCookieId(String cookieId);
    
    // For stats potentially
    long count();
}
