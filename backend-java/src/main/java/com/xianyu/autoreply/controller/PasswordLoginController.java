package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.service.BrowserService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class PasswordLoginController {

    private final BrowserService browserService;
    private final com.xianyu.autoreply.service.TokenService tokenService;

    @Autowired
    public PasswordLoginController(BrowserService browserService, com.xianyu.autoreply.service.TokenService tokenService) {
        this.browserService = browserService;
        this.tokenService = tokenService;
    }

    @PostMapping("/password-login")
    public Map<String, Object> passwordLogin(@RequestBody PasswordLoginRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (request.getAccount_id() == null || request.getAccount() == null || request.getPassword() == null) {
            return Map.of("success", false, "message", "账号ID、登录账号和密码不能为空");
        }
        
        String authHeader = httpRequest.getHeader("Authorization");
        Long userId = 1L; // Fallback to admin if no token (for compatibility), but we try to extract
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
             String token = authHeader.substring(7);
             com.xianyu.autoreply.service.TokenService.TokenInfo info = tokenService.verifyToken(token);
             if (info != null) {
                 userId = info.userId;
             }
        }
        
        String sessionId = browserService.startPasswordLogin(
                request.getAccount_id(),
                request.getAccount(),
                request.getPassword(),
                request.getShow_browser() != null && request.getShow_browser(),
                userId
        );
        
        return Map.of("success", true, "session_id", sessionId, "message", "登录任务已启动");
    }

    @GetMapping("/password-login/check/{sessionId}")
    public Map<String, Object> checkPasswordLoginStatus(@PathVariable String sessionId) {
        return browserService.checkPasswordLoginStatus(sessionId);
    }

    @Data
    public static class PasswordLoginRequest {
        private String account_id;
        private String account;
        private String password;
        private Boolean show_browser;
    }
}
