package com.xianyu.autoreply.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class CaptchaSessionService {

    // Simulating the Python 'active_sessions' dict
    // Key: session_id
    private final Map<String, CaptchaSession> activeSessions = new ConcurrentHashMap<>();

    @Data
    public static class CaptchaSession {
        private String sessionId;
        private String screenshot; // Base64
        private Map<String, Object> captchaInfo;
        private Map<String, Object> viewport;
        private boolean completed;
        // In real impl, would hold Playwright Page object here
        // private Page page; 
    }

    public CaptchaSession getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }
    
    public Map<String, CaptchaSession> getAllSessions() {
        return activeSessions;
    }

    public void createSession(String sessionId, String screenshot, Map<String, Object> captchaInfo, Map<String, Object> viewport) {
        CaptchaSession session = new CaptchaSession();
        session.setSessionId(sessionId);
        session.setScreenshot(screenshot);
        session.setCaptchaInfo(captchaInfo);
        session.setViewport(viewport);
        session.setCompleted(false);
        activeSessions.put(sessionId, session);
        log.info("Session created: {}", sessionId);
    }

    public void closeSession(String sessionId) {
        activeSessions.remove(sessionId);
        log.info("Session closed: {}", sessionId);
    }
    
    // Logic to handle mouse events - Stub since we can't control browser without Page
    public boolean handleMouseEvent(String sessionId, String eventType, int x, int y) {
        if (!activeSessions.containsKey(sessionId)) return false;
        log.info("Mouse event {}: {},{} for session {}", eventType, x, y, sessionId);
        // Interaction Logic would go here
        return true; 
    }

    public boolean checkCompletion(String sessionId) {
        if (!activeSessions.containsKey(sessionId)) return false;
        return activeSessions.get(sessionId).isCompleted();
    }
}
