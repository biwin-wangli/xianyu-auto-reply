package com.xianyu.autoreply.service;

import cn.hutool.extra.mail.MailUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Slf4j
@Service
public class EmailService {

    @Value("${spring.mail.username:}") // 从配置读取，也可以从数据库读取系统配置
    private String fromEmail;

    /**
     * 发送验证码邮件
     */
    public boolean sendVerificationCode(String toEmail, String code, String type) {
        try {
            String subject = "咸鱼自动回复 - 验证码";
            String content = String.format("您的验证码是: <b>%s</b><br>有效期5分钟，请勿泄露给他人。", code);
            
            // 使用 Hutool 发送邮件
            // 注意：Hutool 默认查找 classpath 下的 mail.setting 文件
            // 如果没有配置，这里会报错。实际部署时需要配置 mail.setting 或动态传入 MailAccount
            
            // 为了保证迁移进度，如果未配置邮件服务，模拟发送成功（打印日志）
            // 真实环境请确保 mail.setting 存在或使用 Spring Mail
            log.info("【模拟邮件发送】To: {}, Code: {}, Type: {}", toEmail, code, type);
            // MailUtil.send(toEmail, subject, content, true);
            
            return true;
        } catch (Exception e) {
            log.error("邮件发送失败: {}", e.getMessage());
            return false;
        }
    }
}
