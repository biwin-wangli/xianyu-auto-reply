package com.xianyu.autoreply.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.service.BrowserService;
import com.xianyu.autoreply.service.TokenService;
import com.xianyu.autoreply.service.XianyuClientService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/cookies")
public class CookieController extends BaseController {

    private final CookieRepository cookieRepository;
    private final XianyuClientService xianyuClientService;
    private final BrowserService browserService;

    @Autowired
    public CookieController(CookieRepository cookieRepository,
                            XianyuClientService xianyuClientService,
                            BrowserService browserService,
                            TokenService tokenService) {
        super(tokenService);
        this.cookieRepository = cookieRepository;
        this.xianyuClientService = xianyuClientService;
        this.browserService = browserService;
    }



    private void checkOwnership(Cookie cookie, Long userId) {
        if (isAdmin(userId)) return;
        if (cookie != null && !cookie.getUserId().equals(userId)) {
            throw new RuntimeException("Forbidden: You do not own this cookie");
        }
    }

    @GetMapping
    public List<Cookie> listCookies(@RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        if (isAdmin(userId)) {
            return cookieRepository.findAll();
        }
        // Repository needs a method findByUserId. Assuming it exists.
        return cookieRepository.findByUserId(userId);
    }

    /**
     * 获取所有Cookie的详细信息（包括值和状态）
     * 对应Python的 get_cookies_details 接口
     */
    @GetMapping("/details")
    public List<CookieDetailsResponse> getAllCookiesDetails(@RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = getUserId(token);
        List<Cookie> userCookies = new ArrayList<>();
        if (Objects.equals(1L, userId)) {
            userCookies.addAll(cookieRepository.findAll());
        } else {
            // 获取当前用户的所有cookies
            userCookies.addAll(cookieRepository.findByUserId(userId));
        }

        // 构建详细信息响应
        return userCookies.stream().map(cookie -> {
            CookieDetailsResponse response = new CookieDetailsResponse();
            response.setId(cookie.getId());
            response.setValue(cookie.getValue());
            response.setEnabled(cookie.getEnabled());
            response.setAutoConfirm(cookie.getAutoConfirm());
            response.setRemark(cookie.getRemark() != null ? cookie.getRemark() : "");
            response.setPauseDuration(cookie.getPauseDuration() != null ? cookie.getPauseDuration() : 10);
            response.setUsername(cookie.getUsername());
            response.setLoginPassword(cookie.getPassword());
            response.setShowBrowser(cookie.getShowBrowser() == 1);
            return response;
        }).collect(Collectors.toList());
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

    /**
     * Cookie详细信息响应 - 对应Python的 get_cookies_details 返回格式
     */
    @Data
    public static class CookieDetailsResponse {
        private String id;
        private String value;
        private Boolean enabled;
        @JsonProperty("auto_confirm")
        private Integer autoConfirm;
        private String remark;
        @JsonProperty("pause_duration")
        private Integer pauseDuration;
        private String username;
        @JsonProperty("login_password")
        private String loginPassword;
        @JsonProperty("show_browser")
        private Boolean showBrowser;
    }
}