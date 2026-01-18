package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.service.CaptchaSessionService;
import com.xianyu.autoreply.service.TokenService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/captcha")
public class CaptchaRemoteController extends BaseController {

    private final CaptchaSessionService sessionService;

    @Autowired
    public CaptchaRemoteController(CaptchaSessionService sessionService,
                                   TokenService tokenService) {
        super(tokenService);
        this.sessionService = sessionService;
    }

    @GetMapping("/sessions")
    public Map<String, Object> getActiveSessions() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        sessionService.getAllSessions().forEach((id, session) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("session_id", id);
            map.put("completed", session.isCompleted());
            // has_websocket check would require exposing wsConnections from Handler, skipped for now
            map.put("has_websocket", true);
            sessions.add(map);
        });

        return Map.of("count", sessions.size(), "sessions", sessions);
    }

    @GetMapping("/session/{sessionId}")
    public Map<String, Object> getSessionInfo(@PathVariable String sessionId) {
        CaptchaSessionService.CaptchaSession session = sessionService.getSession(sessionId);
        if (session == null) throw new RuntimeException("会话不存在");

        Map<String, Object> resp = new HashMap<>();
        resp.put("session_id", sessionId);
        resp.put("screenshot", session.getScreenshot());
        resp.put("captcha_info", session.getCaptchaInfo());
        resp.put("viewport", session.getViewport());
        resp.put("completed", session.isCompleted());
        return resp;
    }

    @GetMapping("/screenshot/{sessionId}")
    public Map<String, String> getScreenshot(@PathVariable String sessionId) {
        CaptchaSessionService.CaptchaSession session = sessionService.getSession(sessionId);
        if (session == null) throw new RuntimeException("会话不存在");

        return Map.of("screenshot", session.getScreenshot());
    }

    @PostMapping("/mouse_event")
    public Map<String, Object> handleMouseEvent(@RequestBody MouseEventRequest request) {
        boolean success = sessionService.handleMouseEvent(
                request.getSession_id(),
                request.getEvent_type(),
                request.getX(),
                request.getY()
        );

        if (!success) throw new RuntimeException("处理失败");

        boolean completed = sessionService.checkCompletion(request.getSession_id());

        return Map.of("success", true, "completed", completed);
    }

    @PostMapping("/check_completion")
    public Map<String, Object> checkCompletion(@RequestBody Map<String, String> body) {
        String sessionId = body.get("session_id");
        boolean completed = sessionService.checkCompletion(sessionId);
        return Map.of("session_id", sessionId, "completed", completed);
    }

    @DeleteMapping("/session/{sessionId}")
    public Map<String, Boolean> closeSession(@PathVariable String sessionId) {
        sessionService.closeSession(sessionId);
        return Map.of("success", true);
    }

    // HTML Control Page serving could be done by Thymeleaf or Static Resource, 
    // here returning simple string or checking static folder.
    // Python served specific HTML file.

    @Data
    public static class MouseEventRequest {
        private String session_id;
        private String event_type;
        private int x;
        private int y;
    }
}
