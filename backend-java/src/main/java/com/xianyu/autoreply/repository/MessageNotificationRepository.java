package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.MessageNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MessageNotificationRepository extends JpaRepository<MessageNotification, Long> {
}
