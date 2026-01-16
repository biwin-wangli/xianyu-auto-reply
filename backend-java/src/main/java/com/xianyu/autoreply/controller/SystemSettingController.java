package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.entity.SystemSetting;
import com.xianyu.autoreply.repository.SystemSettingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 系统设置控制器
 * 提供系统配置的查询接口
 */
@RestController
@RequestMapping("/system-settings")
public class SystemSettingController {

    private final SystemSettingRepository systemSettingRepository;

    @Autowired
    public SystemSettingController(SystemSettingRepository systemSettingRepository) {
        this.systemSettingRepository = systemSettingRepository;
    }

    /**
     * 获取公开的系统设置（无需认证）
     * 对应 Python 工程中的 GET /system-settings/public
     */
    /**
     * 获取公开的系统设置（无需认证）
     * 对应 Python 工程中的 GET /system-settings/public
     */
    @GetMapping("/public")
    public Map<String, String> getPublicSystemSettings() {
        Set<String> publicKeys = Set.of(
            "registration_enabled",
            "show_default_login_info",
            "login_captcha_enabled"
        );
        
        List<SystemSetting> allHelper = systemSettingRepository.findAll();
        Map<String, String> result = new HashMap<>();
        
        // 默认值 (Python logic)
        result.put("registration_enabled", "true");
        result.put("show_default_login_info", "true");
        result.put("login_captcha_enabled", "true");

        for (SystemSetting setting : allHelper) {
            if (publicKeys.contains(setting.getKey())) {
                result.put(setting.getKey(), setting.getValue());
            }
        }
        return result;
    }

    /**
     * 获取系统设置（排除敏感信息）
     * 对应 Python: GET /system-settings
     */
    @GetMapping
    public Map<String, String> getAllSettings() {
        List<SystemSetting> all = systemSettingRepository.findAll();
        Map<String, String> settings = new HashMap<>();
        for (SystemSetting s : all) {
            // 排除敏感信息
            if ("admin_password_hash".equals(s.getKey())) {
                continue;
            }
            settings.put(s.getKey(), s.getValue());
        }
        return settings;
    }

    /**
     * 更新系统设置
     * 对应 Python: PUT /system-settings/{key}
     */
    @org.springframework.web.bind.annotation.PutMapping("/{key}")
    public Map<String, String> updateSetting(@org.springframework.web.bind.annotation.PathVariable String key, 
                                             @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        // 禁止直接修改密码哈希
        if ("admin_password_hash".equals(key)) {
            throw new RuntimeException("请使用密码修改接口");
        }

        String value = body.get("value");
        String description = body.get("description");

        SystemSetting setting = systemSettingRepository.findByKey(key).orElse(new SystemSetting());
        setting.setKey(key); // Ensure key is set for new entries
        if (value != null) setting.setValue(value);
        if (description != null) setting.setDescription(description);
        
        systemSettingRepository.save(setting);
        
        Map<String, String> response = new HashMap<>();
        response.put("msg", "system setting updated");
        return response;
    }

    /**
     * 获取注册开关状态
     * 对应 Python: GET /registration-status
     */
    @GetMapping("/registration-status") // Note: logic maps to /public usually, but Python has explicit endpoint too if used by admin or specific component
    public Map<String, Boolean> getRegistrationStatus() {
       return getStatusHelper("registration_enabled");
    }

    /**
     * 获取登录信息显示状态
     * 对应 Python: GET /login-info-status
     */
    @GetMapping("/login-info-status")
    public Map<String, Boolean> getLoginInfoStatus() {
       return getStatusHelper("show_default_login_info");
    }
    
    private Map<String, Boolean> getStatusHelper(String key) {
        String val = systemSettingRepository.findByKey(key)
                .map(SystemSetting::getValue)
                .orElse("true"); // Default true
        boolean enabled = "true".equalsIgnoreCase(val) || "1".equals(val);
        
        Map<String, Boolean> res = new HashMap<>();
        res.put("enabled", enabled);
        return res;
    }
}
