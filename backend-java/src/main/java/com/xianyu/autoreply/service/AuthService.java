package com.xianyu.autoreply.service;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.core.util.RandomUtil;
import com.xianyu.autoreply.entity.EmailVerification;
import com.xianyu.autoreply.entity.User;
import com.xianyu.autoreply.repository.EmailVerificationRepository;
import com.xianyu.autoreply.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final EmailService emailService;

    @Autowired
    public AuthService(UserRepository userRepository, 
                       EmailVerificationRepository emailVerificationRepository,
                       EmailService emailService) {
        this.userRepository = userRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.emailService = emailService;
    }

    public User verifyUserPassword(String username, String password) {
        Optional<User> optUser = userRepository.findByUsername(username);
        if (optUser.isPresent()) {
            User user = optUser.get();
            // 使用 SHA256 验证密码
            String inputHash = DigestUtil.sha256Hex(password);
            if (inputHash.equals(user.getPasswordHash())) {
                return user;
            }
        }
        return null;
    }
    
    public User verifyUserPasswordByEmail(String email, String password) {
        Optional<User> optUser = userRepository.findByEmail(email);
        if (optUser.isPresent()) {
            User user = optUser.get();
            // 使用 SHA256 验证密码
            String inputHash = DigestUtil.sha256Hex(password);
            if (inputHash.equals(user.getPasswordHash())) {
                return user;
            }
        }
        return null;
    }

    @Transactional
    public boolean verifyEmailCode(String email, String code, String type) {
        // type 参数逻辑在 Python 中用于区分 register/login，但查表逻辑一致
        Optional<EmailVerification> optCode = emailVerificationRepository.findFirstByEmailAndCodeAndUsedFalseOrderByCreatedAtDesc(email, code);
        
        if (optCode.isPresent()) {
            EmailVerification ev = optCode.get();
            if (ev.getExpiresAt().isAfter(LocalDateTime.now())) {
                // 验证成功，标记为已使用
                ev.setUsed(true);
                emailVerificationRepository.save(ev);
                return true;
            }
        }
        return false;
    }
    
    public User getUserByEmail(String email) {
        return userRepository.findByEmail(email).orElse(null);
    }
    
    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username).orElse(null);
    }
    
    @Transactional
    public boolean updateUserPassword(String username, String newPassword) {
        Optional<User> optUser = userRepository.findByUsername(username);
        if (optUser.isPresent()) {
            User user = optUser.get();
            user.setPasswordHash(DigestUtil.sha256Hex(newPassword));
            userRepository.save(user);
            return true;
        }
        return false;
    }

    /**
     * 发送验证码
     */
    @Transactional
    public boolean sendVerificationCode(String email, String type) {
        // 1. 生成6位数字验证码
        String code = RandomUtil.randomNumbers(6);
        
        // 2. 保存到数据库
        EmailVerification ev = new EmailVerification();
        ev.setEmail(email);
        ev.setCode(code);
        ev.setExpiresAt(LocalDateTime.now().plusMinutes(5)); // 5分钟有效期
        ev.setUsed(false);
        emailVerificationRepository.save(ev);
        
        // 3. 发送邮件
        return emailService.sendVerificationCode(email, code, type);
    }
}
