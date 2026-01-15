package com.xianyu.autoreply.controller;

import lombok.Data;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/geetest")
public class GeetestController {

    // Simplified Geetest implementation for migration parity
    // Real implementation would require Geetest SDK integration

    @GetMapping("/register")
    public Map<String, Object> register() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("code", 200);
        response.put("message", "获取成功");
        
        Map<String, Object> data = new HashMap<>();
        data.put("gt", "mock_gt_" + UUID.randomUUID().toString().substring(0, 8));
        data.put("challenge", UUID.randomUUID().toString().replace("-", ""));
        data.put("success", 1);
        data.put("new_captcha", true);
        
        response.put("data", data);
        return response;
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody ValidateRequest request) {
        Map<String, Object> response = new HashMap<>();
        // Mock validation success
        response.put("success", true);
        response.put("code", 200);
        response.put("message", "验证通过");
        return response;
    }

    @Data
    public static class ValidateRequest {
        private String challenge;
        private String validate;
        private String seccode;
    }
}
