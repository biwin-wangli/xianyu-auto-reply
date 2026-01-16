package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.entity.CaptchaCode;
import com.xianyu.autoreply.repository.CaptchaCodeRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RestController
// 注意：移除了类级别的 @RequestMapping("/api/captcha")，改用方法级别的根路径映射
public class CaptchaController {

    private final CaptchaCodeRepository captchaCodeRepository;

    @Autowired
    public CaptchaController(CaptchaCodeRepository captchaCodeRepository) {
        this.captchaCodeRepository = captchaCodeRepository;
    }


    /**
     * 生成图形验证码
     * 对应 Python: /generate-captcha
     */
    @PostMapping("/generate-captcha")
    public Map<String, Object> generateCaptcha(@RequestBody CaptchaRequest request) {
        // 生成验证码
        cn.hutool.captcha.LineCaptcha captcha = cn.hutool.captcha.CaptchaUtil.createLineCaptcha(200, 100, 4, 20);
        String code = captcha.getCode();
        String imageBase64 = captcha.getImageBase64();

        // 查找旧的记录并删除 (防止同一个 session_id 积累垃圾数据)
        // 注意：实际生产中可能需要事务或定时清理，这里简单处理
        captchaCodeRepository.findBySessionId(request.getSession_id())
                .ifPresent(old -> captchaCodeRepository.delete(old));

        // 保存到数据库
        CaptchaCode entity = new CaptchaCode();
        entity.setSessionId(request.getSession_id());
        entity.setCode(code);
        // 设置5分钟后过期
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        
        captchaCodeRepository.save(entity);

        return Map.of(
            "success", true,
            "captcha_image", "data:image/png;base64," + imageBase64,
            "session_id", request.getSession_id(),
            "message", "图形验证码生成成功"
        );
    }

    /**
     * 验证图形验证码
     * 对应 Python: /verify-captcha
     */
    @PostMapping("/verify-captcha")
    public Map<String, Object> verifyCaptcha(@RequestBody VerifyCaptchaRequest request) {
        var codeOpt = captchaCodeRepository.findBySessionId(request.getSession_id());

        if (codeOpt.isEmpty()) {
            return Map.of("success", false, "message", "图形验证码错误或已过期");
        }

        CaptchaCode codeEntity = codeOpt.get();

        // 检查过期时间
        if (codeEntity.getExpiresAt().isBefore(LocalDateTime.now())) {
            captchaCodeRepository.delete(codeEntity);
            return Map.of("success", false, "message", "图形验证码已过期");
        }

        // 验证代码内容 (忽略大小写)
        if (!codeEntity.getCode().equalsIgnoreCase(request.getCaptcha_code())) {
            return Map.of("success", false, "message", "图形验证码错误");
        }

        // 验证成功后删除验证码
        captchaCodeRepository.delete(codeEntity);

        return Map.of(
            "success", true,
            "message", "图形验证码验证成功"
        );
    }

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
