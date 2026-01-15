package com.xianyu.autoreply.controller;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/captcha") // Consistent with Python's router naming in reply_server.py
public class CaptchaController {

    @PostMapping("/generate")
    public Map<String, Object> generateCaptcha(@RequestBody CaptchaRequest request) {
        // Simple simplified implementation similar to Python's basic random code
        // In Python file `db_manager.py` (checked earlier via file list), it used to generate random codes.
        String code = cn.hutool.core.util.RandomUtil.randomString(4);
        
        // This should interface with db_manager properly, but since we are migrating to Java:
        // We can just store it in a Cache or lightweight DB
        // For simplicity: Return a mock base64 image (or real one via Hutool Captcha)
        
        cn.hutool.captcha.LineCaptcha captcha = cn.hutool.captcha.CaptchaUtil.createLineCaptcha(200, 100);
        String imageBase64 = captcha.getImageBase64();
        String validCode = captcha.getCode();
        
        // Save to DB (mocking DB Manager logic, or direct repo call if we had CaptchaRepo)
        // Since we didn't create CaptchaEntity in the plan, I will use a static Map for temporary session
        // Warning: This is not persistent but functional for migration demo.
        CaptchaCache.put(request.getSession_id(), validCode);
        
        return Map.of(
            "success", true,
            "captcha_image", "data:image/png;base64," + imageBase64,
            "session_id", request.getSession_id(),
            "message", "Captcha generated"
        );
    }

    @PostMapping("/verify")
    public Map<String, Object> verifyCaptcha(@RequestBody VerifyCaptchaRequest request) {
        String validCode = CaptchaCache.get(request.getSession_id());
        if (validCode != null && validCode.equalsIgnoreCase(request.getCaptcha_code())) {
            CaptchaCache.remove(request.getSession_id());
            return Map.of("success", true, "message", "Verified");
        }
        return Map.of("success", false, "message", "Invalid Code");
    }
    
    // Simple in-memory cache for demo/migration completeness
    private static final java.util.Map<String, String> CaptchaCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Data
    public static class CaptchaRequest {
        private String session_id;
    }
    
    @Data
    public static class VerifyCaptchaRequest {
        private String session_id;
        private String captcha_code;
    }
}
