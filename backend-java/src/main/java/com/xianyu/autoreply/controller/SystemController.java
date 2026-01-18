package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.entity.SystemSetting;
import com.xianyu.autoreply.repository.SystemSettingRepository;
import com.xianyu.autoreply.service.TokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController extends BaseController {

    private final SystemSettingRepository systemSettingRepository;

    @Autowired
    public SystemController(SystemSettingRepository systemSettingRepository,
                            TokenService tokenService) {
        super(tokenService);
        this.systemSettingRepository = systemSettingRepository;
    }

    @GetMapping("/settings")
    public List<SystemSetting> getSettings() {
        return systemSettingRepository.findAll();
    }

    @PostMapping("/settings")
    public SystemSetting updateSetting(@RequestBody SystemSetting setting) {
        return systemSettingRepository.save(setting);
    }

    @GetMapping("/public")
    public Map<String, String> getPublicSettings() {
        // Return public settings like registration_enabled
        // In real app, fetch from DB. For now, mock or fetch if safe.
        // Python code filters keys: registration_enabled, show_default_login_info, login_captcha_enabled
        // We can reuse getSettings() and filter.

        List<SystemSetting> all = systemSettingRepository.findAll();
        Map<String, String> publicSettings = new java.util.HashMap<>();

        // Defaults
        publicSettings.put("registration_enabled", "true");
        publicSettings.put("show_default_login_info", "true");
        publicSettings.put("login_captcha_enabled", "true");

        for (SystemSetting setting : all) {
            String key = setting.getKey();
            if ("registration_enabled".equals(key) ||
                    "show_default_login_info".equals(key) ||
                    "login_captcha_enabled".equals(key)) {
                publicSettings.put(key, setting.getValue());
            }
        }
        return publicSettings;
    }

    // Version endpoints (mock for now as they proxy external URLs in Python)
    @GetMapping("/version/check")
    public Map<String, Object> checkVersion() {
        // In Python this calls an external URL. 
        // We return a dummy response or implement the HTTP call using Hutool if needed.
        return Collections.singletonMap("version", "1.0.0");
    }

    @GetMapping("/version/changelog")
    public Map<String, Object> getChangelog() {
        return Collections.singletonMap("html", "<p>Migrated to Java!</p>");
    }

    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> getLogs(@RequestParam(defaultValue = "100") int lines) {
        try {
            // Read from logs/backend-java.log logic
            File logFile = new File("logs/backend-java.log");
            if (!logFile.exists()) {
                return ResponseEntity.ok(Collections.singletonMap("logs", "Log file not found"));
            }
            List<String> allLines = Files.readAllLines(Paths.get(logFile.getPath()));
            int start = Math.max(0, allLines.size() - lines);
            List<String> tail = allLines.subList(start, allLines.size());
            return ResponseEntity.ok(Collections.singletonMap("logs", String.join("\n", tail)));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Collections.singletonMap("error", e.getMessage()));
        }
    }
}
