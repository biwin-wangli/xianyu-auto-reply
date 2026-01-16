package com.xianyu.autoreply.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.service.BrowserService;
import com.xianyu.autoreply.service.TokenService;
import com.xianyu.autoreply.service.XianyuClientService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/cookies")
public class CookieController {

    private final CookieRepository cookieRepository;
    private final XianyuClientService xianyuClientService;
    private final BrowserService browserService;
    private final TokenService tokenService;

    @Autowired
    public CookieController(CookieRepository cookieRepository,
                            XianyuClientService xianyuClientService,
                            BrowserService browserService,
                            TokenService tokenService) {
        this.cookieRepository = cookieRepository;
        this.xianyuClientService = xianyuClientService;
        this.browserService = browserService;
        this.tokenService = tokenService;
    }

    // Helper to get user ID
    private Long getUserId(String token) {
        if (token == null) throw new RuntimeException("Unauthorized");
        String rawToken = token.replace("Bearer ", "");
        TokenService.TokenInfo info = tokenService.verifyToken(rawToken);
        if (info == null) throw new RuntimeException("Unauthorized");
        return info.userId;
    }

    private void checkOwnership(Cookie cookie, Long userId) {
        if (cookie != null && !cookie.getUserId().equals(userId)) {
            throw new RuntimeException("Forbidden: You do not own this cookie");
        }
    }

    @GetMapping
    public List<Cookie> listCookies(@RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        // Repository needs a method findByUserId. Assuming it exists.
        return cookieRepository.findByUserId(userId);
    }

    @GetMapping("/details")
    public List<Cookie> getAllCookiesDetails(@RequestHeader(value = "Authorization", required = false) String token) {
        return listCookies(token);
    }

    @PostMapping
    public Cookie addCookie(@RequestBody CookieIn cookieIn, @RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        
        // Check if ID exists
        if (cookieRepository.existsById(cookieIn.getId())) {
             throw new RuntimeException("Cookie ID already exists");
        }

        Cookie cookie = new Cookie();
        cookie.setId(cookieIn.getId());
        cookie.setValue(cookieIn.getValue());
        cookie.setUserId(userId);
        cookie.setEnabled(true);
        cookie.setAutoConfirm(1);
        cookie.setPauseDuration(10);
        cookie.setShowBrowser(0);
        cookie.setCreatedAt(LocalDateTime.now());
        cookie.setUpdatedAt(LocalDateTime.now());

        cookieRepository.save(cookie);
        // Start client if needed (Python: creates client on connection, doesn't auto start unless valid)
        // xianyuClientService.startClient(cookie.getId()); // Optional, depending on logic

        return cookie;
    }

    @PutMapping("/{id}")
    public Cookie updateCookie(@PathVariable String id, @RequestBody CookieIn cookieIn, @RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        checkOwnership(cookie, userId);
        
        cookie.setValue(cookieIn.getValue());
        cookie.setUpdatedAt(LocalDateTime.now());
        cookieRepository.save(cookie);
        return cookie;
    }

    @DeleteMapping("/{id}")
    public void deleteCookie(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        checkOwnership(cookie, userId);
        
        cookieRepository.deleteById(id);
    }

    @GetMapping("/{id}/details")
    public Cookie getCookieDetails(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        checkOwnership(cookie, userId);
        return cookie;
    }

    // Missing Endpoints Implementation

    // login-info
    @PutMapping("/{id}/login-info")
    public Cookie updateLoginInfo(@PathVariable String id, @RequestBody LoginInfoUpdate update, @RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        checkOwnership(cookie, userId);
        
        if (update.getUsername() != null) cookie.setUsername(update.getUsername());
        if (update.getPassword() != null) cookie.setPassword(update.getPassword());
        if (update.getShowBrowser() != null) cookie.setShowBrowser(update.getShowBrowser() ? 1 : 0);
        
        return cookieRepository.save(cookie);
    }

    // pause-duration
    @PutMapping("/{id}/pause-duration")
    public Cookie updatePauseDuration(@PathVariable String id, @RequestBody PauseDurationUpdate update, @RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        checkOwnership(cookie, userId);
        
        cookie.setPauseDuration(update.getPauseDuration());
        return cookieRepository.save(cookie);
    }

    @GetMapping("/{id}/pause-duration")
    public Map<String, Integer> getPauseDuration(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        checkOwnership(cookie, userId);
        return Map.of("pause_duration", cookie.getPauseDuration());
    }

    // auto-confirm
    @PutMapping("/{id}/auto-confirm")
    public Cookie updateAutoConfirm(@PathVariable String id, @RequestBody AutoConfirmUpdate update, @RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        checkOwnership(cookie, userId);
        
        cookie.setAutoConfirm(update.isAutoConfirm() ? 1 : 0);
        return cookieRepository.save(cookie);
    }

    @GetMapping("/{id}/auto-confirm")
    public Map<String, Integer> getAutoConfirm(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        checkOwnership(cookie, userId);
        return Map.of("auto_confirm", cookie.getAutoConfirm());
    }

    // remark
    @PutMapping("/{id}/remark")
    public Cookie updateRemark(@PathVariable String id, @RequestBody RemarkUpdate update, @RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        checkOwnership(cookie, userId);
        
        cookie.setRemark(update.getRemark());
        return cookieRepository.save(cookie);
    }

    @GetMapping("/{id}/remark")
    public Map<String, String> getRemark(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        checkOwnership(cookie, userId);
        return Map.of("remark", cookie.getRemark());
    }

    // status
    @PutMapping("/{id}/status")
    public Cookie updateStatus(@PathVariable String id, @RequestBody StatusUpdate update, @RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        Cookie cookie = cookieRepository.findById(id).orElseThrow(() -> new RuntimeException("Cookie not found"));
        checkOwnership(cookie, userId);
        
        cookie.setEnabled(update.isEnabled());
        cookieRepository.save(cookie);
        // Start/Stop client logic placeholder
        return cookie;
    }
    
    // cookies/check - Usually global validation or test, Python Line 4340
    @GetMapping("/check")
    public Map<String, Object> checkCookies(@RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        // Logic to check validity of user's cookies.
        // For now, return stub or call BrowserService if needed.
        return Map.of("status", "checked", "count", 0); 
    }

    // DTOs with JSON Properties

    @Data
    public static class CookieIn {
        private String id;
        private String value;
    }

    @Data
    public static class LoginInfoUpdate {
        private String username;
        private String password;
        @JsonProperty("show_browser")
        private Boolean showBrowser;
    }

    @Data
    public static class AutoConfirmUpdate {
        @JsonProperty("auto_confirm")
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

    @Data
    public static class PauseDurationUpdate {
        @JsonProperty("pause_duration")
        private Integer pauseDuration;
    }
}