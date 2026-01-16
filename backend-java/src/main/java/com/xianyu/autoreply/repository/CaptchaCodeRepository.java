package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.CaptchaCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CaptchaCodeRepository extends JpaRepository<CaptchaCode, Long> {
    Optional<CaptchaCode> findBySessionId(String sessionId);
    void deleteBySessionId(String sessionId);
}
