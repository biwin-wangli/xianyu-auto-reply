package com.xianyu.autoreply.websocket;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xianyu.autoreply.service.CaptchaSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class CaptchaWebSocketHandler extends TextWebSocketHandler {

    private final CaptchaSessionService sessionService;
    private final Map<String, WebSocketSession> wsConnections = new ConcurrentHashMap<>();

    @Autowired
    public CaptchaWebSocketHandler(CaptchaSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // Extract session_id from URL: /api/captcha/ws/{session_id}
        String path = session.getUri().getPath();
        String sessionId = path.substring(path.lastIndexOf('/') + 1);
        
        log.info("WS Connection established: {}", sessionId);
        wsConnections.put(sessionId, session);
        
        CaptchaSessionService.CaptchaSession captchaSession = sessionService.getSession(sessionId);
        if (captchaSession != null) {
            JSONObject info = new JSONObject();
            info.put("type", "session_info");
            info.put("screenshot", captchaSession.getScreenshot());
            info.put("captcha_info", captchaSession.getCaptchaInfo());
            info.put("viewport", captchaSession.getViewport());
            session.sendMessage(new TextMessage(info.toString()));
        } else {
             JSONObject error = new JSONObject();
             error.put("type", "error");
             error.put("message", "会话不存在");
             session.sendMessage(new TextMessage(error.toString()));
             session.close();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        JSONObject data = JSONUtil.parseObj(payload);
        String type = data.getStr("type");
        
        String path = session.getUri().getPath();
        String sessionId = path.substring(path.lastIndexOf('/') + 1);

        if ("mouse_event".equals(type)) {
            String eventType = data.getStr("event_type");
            int x = data.getInt("x");
            int y = data.getInt("y");
            
            boolean success = sessionService.handleMouseEvent(sessionId, eventType, x, y);
            
            if (success && "up".equals(eventType)) {
                // Check completion stub
                boolean completed = sessionService.checkCompletion(sessionId);
                if (completed) {
                    JSONObject resp = new JSONObject();
                    resp.put("type", "completed");
                    resp.put("message", "验证成功！");
                    session.sendMessage(new TextMessage(resp.toString()));
                }
            }
        } else if ("check_completion".equals(type)) {
             boolean completed = sessionService.checkCompletion(sessionId);
             JSONObject resp = new JSONObject();
             resp.put("type", "completion_status");
             resp.put("completed", completed);
             session.sendMessage(new TextMessage(resp.toString()));
        } else if ("ping".equals(type)) {
             JSONObject resp = new JSONObject();
             resp.put("type", "pong");
             session.sendMessage(new TextMessage(resp.toString()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String path = session.getUri().getPath();
        if (path != null) {
            String sessionId = path.substring(path.lastIndexOf('/') + 1);
            wsConnections.remove(sessionId);
            log.info("WS Connection closed: {}", sessionId);
        }
    }
}
