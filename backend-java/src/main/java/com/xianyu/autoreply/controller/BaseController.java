package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.service.TokenService;
import lombok.Data;

import java.util.Objects;

/**
 * 用于处理 Admin 账号的特殊逻辑
 *
 * @author wangli
 * @since 2026-01-18 23:21
 */
@Data
public abstract class BaseController {
    protected final TokenService tokenService;

    protected boolean isAdmin(String token) {
        return isAdmin(getUserId(token));
    }

    protected boolean isAdmin(Long userId) {
        return Objects.equals(1L, userId);
    }

    // Helper to get user ID
    protected Long getUserId(String token) {
        if (token == null) throw new RuntimeException("Unauthorized");
        String rawToken = token.replace("Bearer ", "");
        TokenService.TokenInfo info = tokenService.verifyToken(rawToken);
        if (info == null) throw new RuntimeException("Unauthorized");
        return info.userId;
    }
}
