package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.AiConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {
    List<AiConversation> findByCookieIdAndChatIdOrderByCreatedAtAsc(String cookieId, String chatId);
    long countByChatIdAndCookieIdAndIntentAndRole(String chatId, String cookieId, String intent, String role);
}
