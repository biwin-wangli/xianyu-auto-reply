package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.AiItemCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiItemCacheRepository extends JpaRepository<AiItemCache, String> {
}
