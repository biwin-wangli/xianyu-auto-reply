package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.MessageNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageNotificationRepository extends JpaRepository<MessageNotification, Long> {
    List<MessageNotification> findByCookieId(String cookieId);
    Optional<MessageNotification> findByCookieIdAndChannelId(String cookieId, Long channelId);
    void deleteByCookieId(String cookieId);
}
