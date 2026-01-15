package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.Keyword;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KeywordRepository extends JpaRepository<Keyword, Long> {
    List<Keyword> findByCookieId(String cookieId);
    void deleteByCookieId(String cookieId);
}
