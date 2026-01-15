package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.service.BrowserService;
import com.xianyu.autoreply.service.XianyuClientService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/cookies")
public class CookieController {

    private final CookieRepository cookieRepository;
    private final XianyuClientService xianyuClientService;
    private final BrowserService browserService;

    @Autowired
    public CookieController(CookieRepository cookieRepository,
                            XianyuClientService xianyuClientService,
                            BrowserService browserService) {
        this.cookieRepository = cookieRepository;
        this.xianyuClientService = xianyuClientService;
        this.browserService = browserService;
    }

    @GetMapping
    public List<Cookie> listCookies() {
        // In real app, filter by user. For now return all.
        return cookieRepository.findAll();
    }

    @PostMapping
    public Cookie addCookie(@RequestBody CookieIn cookieIn) {
        Cookie cookie = new Cookie();
        cookie.setId(cookieIn.getId());
        cookie.setValue(cookieIn.getValue());
        cookie.setUserId(1L); // Default user for now
        cookie.setEnabled(true);
        cookie.setCreatedAt(LocalDateTime.now());
        cookie.setUpdatedAt(LocalDateTime.now());

        cookieRepository.save(cookie);
        // Start client if needed
        xianyuClientService.startClient(cookie.getId());

        return cookie;
    }

    @PutMapping("/{id}")
    public Cookie updateCookie(@PathVariable String id, @RequestBody CookieIn cookieIn) {
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        cookie.setValue(cookieIn.getValue());
        cookie.setUpdatedAt(LocalDateTime.now());
        cookieRepository.save(cookie);
        return cookie;
    }

    @DeleteMapping("/{id}")
    public void deleteCookie(@PathVariable String id) {
        cookieRepository.deleteById(id);
        // Stop client
        // xianyuClientService.stopClient(id); // If method existed
    }

    @GetMapping("/{id}/details")
    public Cookie getCookieDetails(@PathVariable String id) {
        return cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
    }

    @PutMapping("/{id}/auto-confirm")
    public Cookie updateAutoConfirm(@PathVariable String id, @RequestBody AutoConfirmUpdate update) {
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        cookie.setAutoConfirm(update.isAutoConfirm() ? 1 : 0);
        return cookieRepository.save(cookie);
    }

    @PutMapping("/{id}/remark")
    public Cookie updateRemark(@PathVariable String id, @RequestBody RemarkUpdate update) {
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        cookie.setRemark(update.getRemark());
        return cookieRepository.save(cookie);
    }

    @PutMapping("/{id}/status")
    public Cookie updateStatus(@PathVariable String id, @RequestBody StatusUpdate update) {
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        cookie.setEnabled(update.isEnabled());
        cookieRepository.save(cookie);
        if (update.isEnabled()) {
            xianyuClientService.startClient(id);
        } else {
            // Stop client logic if implemented
        }
        return cookie;
    }

    @Data
    public static class CookieIn {
        private String id;
        private String value;
    }

    @Data
    public static class AutoConfirmUpdate {
        private boolean autoConfirm;
    }

    @Data
    public static class RemarkUpdate {
        private String remark;
    }

    @Data
    public static class StatusUpdate {
        private boolean enabled;
    }

    // QR Login Stubs
    @PostMapping("/qr-login/generate")
    public Map<String, Object> generateQrCode() {
        return Map.of("success", true, "session_id", UUID.randomUUID().toString(), "qr_url", "http://mock-qr");
    }

    @GetMapping("/qr-login/check/{sessionId}")
    public Map<String, Object> checkQrCode(@PathVariable String sessionId) {
        return Map.of("status", "processing", "message", "Waiting for scan...");
    }

    // Face Verification Stubs
    @GetMapping("/face-verification/screenshot/{accountId}")
    public Map<String, Object> getScreenshot(@PathVariable String accountId) {
        return Map.of("success", false, "message", "No screenshot available");
    }
}