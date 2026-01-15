package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.entity.AiReplySetting;
import com.xianyu.autoreply.entity.DefaultReply;
import com.xianyu.autoreply.entity.Keyword;
import com.xianyu.autoreply.repository.AiReplySettingRepository;
import com.xianyu.autoreply.repository.DefaultReplyRepository;
import com.xianyu.autoreply.repository.KeywordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reply")
public class ReplyController {

    private final KeywordRepository keywordRepository;
    private final DefaultReplyRepository defaultReplyRepository;
    private final AiReplySettingRepository aiReplySettingRepository;

    @Autowired
    public ReplyController(KeywordRepository keywordRepository,
                           DefaultReplyRepository defaultReplyRepository,
                           AiReplySettingRepository aiReplySettingRepository) {
        this.keywordRepository = keywordRepository;
        this.defaultReplyRepository = defaultReplyRepository;
        this.aiReplySettingRepository = aiReplySettingRepository;
    }

    // Keywords
    @GetMapping("/keywords")
    public List<Keyword> getKeywords(@RequestParam String cookieId) {
        return keywordRepository.findByCookieId(cookieId);
    }

    @PostMapping("/keywords")
    public Keyword addKeyword(@RequestBody Keyword keyword) {
        return keywordRepository.save(keyword);
    }
    
    @DeleteMapping("/keywords/{id}")
    public void deleteKeyword(@PathVariable Long id) {
        // Warning: Keyword entity uses synthetic Long ID in Java, but Python used composite.
        // We assume frontend adapts to Long ID or we lookup by content.
        keywordRepository.deleteById(id);
    }

    // Default Reply
    @GetMapping("/default")
    public DefaultReply getDefaultReply(@RequestParam String cookieId) {
        return defaultReplyRepository.findById(cookieId).orElse(null);
    }

    @PostMapping("/default")
    public DefaultReply updateDefaultReply(@RequestBody DefaultReply defaultReply) {
        return defaultReplyRepository.save(defaultReply);
    }

    // AI Settings
    @GetMapping("/ai")
    public AiReplySetting getAiSetting(@RequestParam String cookieId) {
        return aiReplySettingRepository.findById(cookieId).orElse(null);
    }

    @PostMapping("/ai")
    public AiReplySetting updateAiSetting(@RequestBody AiReplySetting setting) {
        return aiReplySettingRepository.save(setting);
    }
}
