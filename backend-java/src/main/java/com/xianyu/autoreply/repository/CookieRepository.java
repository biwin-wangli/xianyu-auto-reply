package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.Cookie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CookieRepository extends JpaRepository<Cookie, String> {
    List<Cookie> findByUserId(Long userId);
}
