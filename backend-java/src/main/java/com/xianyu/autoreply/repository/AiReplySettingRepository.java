package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.AiReplySetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiReplySettingRepository extends JpaRepository<AiReplySetting, String> {
}
