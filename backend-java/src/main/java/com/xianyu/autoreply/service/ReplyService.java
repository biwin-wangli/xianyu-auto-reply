package com.xianyu.autoreply.service;

import com.xianyu.autoreply.entity.DefaultReply;
import com.xianyu.autoreply.entity.DefaultReplyRecord;
import com.xianyu.autoreply.entity.Keyword;
import com.xianyu.autoreply.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class ReplyService {

    private final KeywordRepository keywordRepository;
    private final DefaultReplyRepository defaultReplyRepository;
    private final DefaultReplyRecordRepository defaultReplyRecordRepository;
    private final AiReplyService aiReplyService;

    @Autowired
    public ReplyService(KeywordRepository keywordRepository,
                        DefaultReplyRepository defaultReplyRepository,
                        DefaultReplyRecordRepository defaultReplyRecordRepository,
                        AiReplyService aiReplyService) {
        this.keywordRepository = keywordRepository;
        this.defaultReplyRepository = defaultReplyRepository;
        this.defaultReplyRecordRepository = defaultReplyRecordRepository;
        this.aiReplyService = aiReplyService;
    }

    public String determineReply(String cookieId, String chatId, String userId, String itemId, String message) {
        // 1. Check Keywords
        List<Keyword> keywords = keywordRepository.findByCookieId(cookieId); // Should optimize to search cache or DB query
        // Simple iteration for now
        for (Keyword k : keywords) {
            if (message.contains(k.getKeyword())) {
                 return k.getReply();
            }
        }

        // 2. Check AI Reply
        String aiReply = aiReplyService.generateReply(cookieId, chatId, userId, itemId, message);
        if (aiReply != null) {
            return aiReply;
        }

        // 3. Default Reply
        DefaultReply defaultReply = defaultReplyRepository.findById(cookieId).orElse(null);
        if (defaultReply != null && Boolean.TRUE.equals(defaultReply.getEnabled())) {
            // Check reply once
            if (Boolean.TRUE.equals(defaultReply.getReplyOnce())) {
                Optional<DefaultReplyRecord> record = defaultReplyRecordRepository.findByCookieIdAndChatId(cookieId, chatId);
                if (record.isPresent()) {
                    return null; // Already replied
                }
            }
            // Save record
            DefaultReplyRecord newRecord = new DefaultReplyRecord();
            newRecord.setCookieId(cookieId);
            newRecord.setChatId(chatId);
            defaultReplyRecordRepository.save(newRecord);

            return defaultReply.getReplyContent();
        }

        return null;
    }
}
