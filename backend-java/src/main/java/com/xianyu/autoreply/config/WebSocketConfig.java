package com.xianyu.autoreply.config;

import com.xianyu.autoreply.websocket.CaptchaWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

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

    /**
     * 配置WebSocket容器参数，解决"消息过大"导致的1009错误
     * 
     * 设置最大文本消息缓冲区：10MB
     * 设置最大二进制消息缓冲区：10MB
     * 设置会话空闲超时：30分钟
     * 
     * @return ServletServerContainerFactoryBean配置实例
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        
        // 设置最大文本消息缓冲区大小为10MB（默认8192字节，太小会导致1009错误）
        container.setMaxTextMessageBufferSize(10 * 1024 * 1024);
        
        // 设置最大二进制消息缓冲区大小为10MB
        container.setMaxBinaryMessageBufferSize(10 * 1024 * 1024);
        
        // 设置会话空闲超时时间（毫秒），30分钟无活动则关闭连接
        container.setMaxSessionIdleTimeout(30L * 60 * 1000);
        
        return container;
    }
}
