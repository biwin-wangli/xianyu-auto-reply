package com.xianyu.autoreply.config;

import com.xianyu.autoreply.websocket.CaptchaWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CaptchaWebSocketHandler captchaWebSocketHandler;

    @Autowired
    public WebSocketConfig(CaptchaWebSocketHandler captchaWebSocketHandler) {
        this.captchaWebSocketHandler = captchaWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Python: @router.websocket("/ws/{session_id}")
        // Spring WS matching usually query param or variable interception is harder.
        // We register generic path and parse URI in handler or use handshake interceptor for attributes.
        // Mapping: /api/captcha/ws/*
        registry.addHandler(captchaWebSocketHandler, "/api/captcha/ws/*")
                .setAllowedOrigins("*");
    }
}
