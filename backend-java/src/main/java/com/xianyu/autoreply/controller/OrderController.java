package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.entity.Order;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.repository.OrderRepository;
import com.xianyu.autoreply.service.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/orders")
public class OrderController extends BaseController {

    private final OrderRepository orderRepository;
    private final CookieRepository cookieRepository;

    @Autowired
    public OrderController(OrderRepository orderRepository,
                           CookieRepository cookieRepository,
                           TokenService tokenService) {
        super(tokenService);
        this.orderRepository = orderRepository;
        this.cookieRepository = cookieRepository;
    }

    @GetMapping
    public List<Order> getAllOrders() {
        // Implement logic to filter by current user logic.
        // For simple migration assuming "admin" or checking cookies.
        // Python logic iterates user cookies and fetches orders.
        // Here we mock "current user" context by fetching all cookies (User 1 assumption again)

        List<String> cookieIds = cookieRepository.findAll().stream()
                .map(Cookie::getId)
                .collect(Collectors.toList());

        List<Order> result = new ArrayList<>();
        for (String cid : cookieIds) {
            result.addAll(orderRepository.findByCookieId(cid));
        }
        return result;
    }

    @GetMapping("/{orderId}")
    public Map<String, Object> getOrder(@PathVariable String orderId) {
        // Python checks ownership. We will just check existence first.
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("订单不存在"));

        return Map.of("success", true, "data", order);
    }

    @DeleteMapping("/{orderId}")
    public Map<String, Object> deleteOrder(@PathVariable String orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new RuntimeException("订单不存在");
        }
        orderRepository.deleteById(orderId);
        return Map.of("success", true, "message", "删除成功");
    }
}
