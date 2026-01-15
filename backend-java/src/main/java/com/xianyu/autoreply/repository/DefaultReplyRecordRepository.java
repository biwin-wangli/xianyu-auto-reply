package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.DefaultReplyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface DefaultReplyRecordRepository extends JpaRepository<DefaultReplyRecord, Long> {
    Optional<DefaultReplyRecord> findByCookieIdAndChatId(String cookieId, String chatId);
}
