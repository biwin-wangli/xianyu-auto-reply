package com.xianyu.autoreply.controller;

import cn.hutool.core.util.StrUtil;
import com.xianyu.autoreply.entity.User;
import com.xianyu.autoreply.service.AuthService;
import com.xianyu.autoreply.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;
    
    private static final String ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

    @Autowired
    public AuthController(AuthService authService, TokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

    /**
     * 发送验证码接口
     * 对应 Python: /send-verification-code
     */
    @PostMapping("/send-verification-code")
    public Map<String, Object> sendVerificationCode(@RequestBody SendCodeRequest request) {
        Map<String, Object> response = new HashMap<>();
        if (StrUtil.isBlank(request.getEmail())) {
            response.put("success", false);
            response.put("message", "邮箱不能为空");
            return response;
        }

        boolean success = authService.sendVerificationCode(request.getEmail(), request.getType());
        if (success) {
            response.put("success", true);
            response.put("message", "验证码发送成功");
        } else {
            response.put("success", false);
            response.put("message", "验证码发送失败");
        }
        return response;
    }

    /**
     * 登录接口
     * 对应 Python: /login
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        User user = null;
        String loginType = "";

        // 1. 用户名/密码登录
        if (StrUtil.isNotBlank(request.getUsername()) && StrUtil.isNotBlank(request.getPassword())) {
            loginType = "用户名/密码";
            log.info("【{}】尝试用户名登录", request.getUsername());
            user = authService.verifyUserPassword(request.getUsername(), request.getPassword());
        } 
        // 2. 邮箱/密码登录
        else if (StrUtil.isNotBlank(request.getEmail()) && StrUtil.isNotBlank(request.getPassword())) {
            loginType = "邮箱/密码";
            log.info("【{}】尝试邮箱密码登录", request.getEmail());
            user = authService.verifyUserPasswordByEmail(request.getEmail(), request.getPassword());
        }
        // 3. 邮箱/验证码登录
        else if (StrUtil.isNotBlank(request.getEmail()) && StrUtil.isNotBlank(request.getVerification_code())) {
            loginType = "邮箱/验证码";
            log.info("【{}】尝试邮箱验证码登录", request.getEmail());
            if (authService.verifyEmailCode(request.getEmail(), request.getVerification_code(), "login")) {
                user = authService.getUserByEmail(request.getEmail());
                if (user == null) {
                    return new LoginResponse(false, "用户不存在");
                }
            } else {
                return new LoginResponse(false, "验证码错误或已过期");
            }
        } else {
            return new LoginResponse(false, "请提供有效的登录信息");
        }

        if (user != null) {
            boolean isAdmin = ADMIN_USERNAME.equals(user.getUsername());
            String token = tokenService.generateToken(user, isAdmin);
            
            log.info("【{}#{}】{}登录成功{}", user.getUsername(), user.getId(), loginType, isAdmin ? "（管理员）" : "");
            
            return new LoginResponse(true, token, "登录成功", user.getId(), user.getUsername(), isAdmin);
        } else {
            log.warn("{}登录失败", loginType);
            if (loginType.contains("验证码")) {
                // 这个分支其实上面已经处理了，这里是兜底逻辑
                 return new LoginResponse(false, "用户不存在");
            }
            return new LoginResponse(false, "用户名或密码错误"); // 或邮箱或密码错误
        }
    }

    /**
     * 验证token接口
     * 对应 Python: /verify
     */
    @GetMapping("/verify")
    public Map<String, Object> verify(HttpServletRequest request) {
        String token = getTokenFromRequest(request);
        TokenService.TokenInfo info = tokenService.verifyToken(token);
        
        Map<String, Object> response = new HashMap<>();
        if (info != null) {
            response.put("authenticated", true);
            response.put("user_id", info.userId);
            response.put("username", info.username);
            response.put("is_admin", info.isAdmin);
        } else {
            response.put("authenticated", false);
        }
        return response;
    }

    /**
     * 登出接口
     * 对应 Python: /logout
     */
    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request) {
        String token = getTokenFromRequest(request);
        tokenService.invalidateToken(token);
        Map<String, String> response = new HashMap<>();
        response.put("message", "已登出");
        return response;
    }
    
    /**
     * 修改管理员密码接口
     * 对应 Python: /change-admin-password
     */
    @PostMapping("/change-admin-password")
    public Map<String, Object> changeAdminPassword(@RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        String token = getTokenFromRequest(httpRequest);
        TokenService.TokenInfo info = tokenService.verifyToken(token);
        
        Map<String, Object> response = new HashMap<>();
        if (info == null || !info.isAdmin) {
             response.put("success", false);
             response.put("message", "未授权访问或非管理员");
             return response;
        }

        User user = authService.verifyUserPassword(ADMIN_USERNAME, request.getCurrent_password());
        if (user == null) {
            response.put("success", false);
            response.put("message", "当前密码错误");
            return response;
        }

        boolean success = authService.updateUserPassword(ADMIN_USERNAME, request.getNew_password());
        if (success) {
            log.info("【admin#{}】管理员密码修改成功", user.getId());
            response.put("success", true);
            response.put("message", "密码修改成功");
        } else {
            response.put("success", false);
            response.put("message", "密码修改失败");
        }
        return response;
    }
    
    /**
     * 普通用户修改密码接口
     * 对应 Python: /change-password
     */
    @PostMapping("/change-password")
    public Map<String, Object> changeUserPassword(@RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        String token = getTokenFromRequest(httpRequest);
        TokenService.TokenInfo info = tokenService.verifyToken(token);
        
        Map<String, Object> response = new HashMap<>();
        if (info == null) {
             response.put("success", false);
             response.put("message", "无法获取用户信息");
             return response;
        }

        User user = authService.verifyUserPassword(info.username, request.getCurrent_password());
        if (user == null) {
             response.put("success", false);
             response.put("message", "当前密码错误");
             return response;
        }

        boolean success = authService.updateUserPassword(info.username, request.getNew_password());
        if (success) {
            log.info("【{}#{}】用户密码修改成功", info.username, info.userId);
            response.put("success", true);
            response.put("message", "密码修改成功");
        } else {
            response.put("success", false);
            response.put("message", "密码修改失败");
        }
        return response;
    }
    
    /**
     * 检查是否使用默认密码
     * 对应 Python: /api/check-default-password
     */
    @GetMapping("/api/check-default-password")
    public Map<String, Boolean> checkDefaultPassword(HttpServletRequest httpRequest) {
        String token = getTokenFromRequest(httpRequest);
        TokenService.TokenInfo info = tokenService.verifyToken(token);
        
        Map<String, Boolean> response = new HashMap<>();
        if (info == null || !info.isAdmin) {
             response.put("using_default", false);
             return response;
        }
        
        User adminUser = authService.verifyUserPassword(ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD);
        response.put("using_default", adminUser != null);
        return response;
    }

    private String getTokenFromRequest(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StrUtil.isNotBlank(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
        private String email;
        private String verification_code;
    }

    @Data
    public static class LoginResponse {
        private boolean success;
        private String token;
        private String message;
        private Long user_id;
        private String username;
        private Boolean is_admin;

        public LoginResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public LoginResponse(boolean success, String token, String message, Long userId, String username, boolean isAdmin) {
            this.success = success;
            this.token = token;
            this.message = message;
            this.user_id = userId;
            this.username = username;
            this.is_admin = isAdmin;
        }
    }
    
    @Data
    public static class ChangePasswordRequest {
        private String current_password;
        private String new_password;
    }

    @Data
    public static class SendCodeRequest {
        private String email;
        private String type; // 'login', 'register', 'reset'
        private String session_id; // optional
    }
}
