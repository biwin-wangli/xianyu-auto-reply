package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    // 查找最近一个未使用且未过期的验证码
    // 注意：expiresAt check 通常在 Service 层做，这里查询最新记录即可
    Optional<EmailVerification> findFirstByEmailAndCodeAndUsedFalseOrderByCreatedAtDesc(String email, String code);
}
