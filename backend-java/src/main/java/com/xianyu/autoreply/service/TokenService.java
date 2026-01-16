package com.xianyu.autoreply.service;

import cn.hutool.core.util.IdUtil;
import com.xianyu.autoreply.entity.User;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 和 会话管理服务
 * 对应 Python 中的 SESSION_TOKENS 全局变量
 */
@Service
public class TokenService {

    // 存储会话token: {token: UserInfo}
    // Python结构: {token: {'user_id': int, 'username': str, 'timestamp': float}}
    private final Map<String, TokenInfo> sessionTokens = new ConcurrentHashMap<>();

    // token过期时间：24小时
    private static final long TOKEN_EXPIRE_TIME_MS = 24 * 60 * 60 * 1000L;

    public static class TokenInfo {
        public Long userId;
        public String username;
        public boolean isAdmin;
        public long timestamp;

        public TokenInfo(Long userId, String username, boolean isAdmin) {
            this.userId = userId;
            this.username = username;
            this.isAdmin = isAdmin;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * 生成并存储 Token
     */
    public String generateToken(User user, boolean isAdmin) {
        String token = IdUtil.simpleUUID(); // 生成无 "-" 的 UUID
        
        // Python实现是 secrets.token_urlsafe(32) -> 43 chars
        // 这里用 UUID 也可以
        
        sessionTokens.put(token, new TokenInfo(user.getId(), user.getUsername(), isAdmin));
        return token;
    }

    /**
     * 验证 Token
     */
    public TokenInfo verifyToken(String token) {
        if (token == null || !sessionTokens.containsKey(token)) {
            return null;
        }

        TokenInfo info = sessionTokens.get(token);

        // 检查过期
        if (System.currentTimeMillis() - info.timestamp > TOKEN_EXPIRE_TIME_MS) {
            sessionTokens.remove(token);
            return null;
        }

        return info;
    }

    /**
     * 移除 Token (Logout)
     */
    public void invalidateToken(String token) {
        if (token != null) {
            sessionTokens.remove(token);
        }
    }
}
