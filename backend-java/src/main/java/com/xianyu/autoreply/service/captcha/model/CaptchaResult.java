package com.xianyu.autoreply.service.captcha.model;

import lombok.Data;
import java.util.Map;

/**
 * 滑块验证结果
 */
@Data
public class CaptchaResult {
    private boolean success;
    private String message;
    private Map<String, String> cookies;
    private long duration;  // 处理耗时（毫秒）
    
    public static CaptchaResult success(Map<String, String> cookies, long duration) {
        CaptchaResult result = new CaptchaResult();
        result.setSuccess(true);
        result.setMessage("滑块验证成功");
        result.setCookies(cookies);
        result.setDuration(duration);
        return result;
    }
    
    public static CaptchaResult failure(String message) {
        CaptchaResult result = new CaptchaResult();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }
}
