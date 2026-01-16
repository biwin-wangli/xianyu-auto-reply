package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.dto.KeywordWithItemIdRequest;
import com.xianyu.autoreply.entity.AiReplySetting;
import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.entity.DefaultReply;
import com.xianyu.autoreply.entity.Keyword;
import com.xianyu.autoreply.repository.AiReplySettingRepository;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.repository.DefaultReplyRepository;
import com.xianyu.autoreply.repository.KeywordRepository;
import com.xianyu.autoreply.service.AiReplyService;
import com.xianyu.autoreply.service.TokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.stream.Collectors;

@RestController
@RequestMapping
public class KeywordController {

    private final KeywordRepository keywordRepository;
    private final DefaultReplyRepository defaultReplyRepository;
    private final AiReplySettingRepository aiReplySettingRepository;
    private final CookieRepository cookieRepository;
    private final AiReplyService aiReplyService;
    private final TokenService tokenService;

    @Autowired
    public KeywordController(KeywordRepository keywordRepository,
                             DefaultReplyRepository defaultReplyRepository,
                             AiReplySettingRepository aiReplySettingRepository,
                             CookieRepository cookieRepository,
                             AiReplyService aiReplyService,
                             TokenService tokenService) {
        this.keywordRepository = keywordRepository;
        this.defaultReplyRepository = defaultReplyRepository;
        this.aiReplySettingRepository = aiReplySettingRepository;
        this.cookieRepository = cookieRepository;
        this.aiReplyService = aiReplyService;
        this.tokenService = tokenService;
    }

    // ------------------------- Keywords -------------------------

    // 对应 Python: @app.get('/keywords-with-item-id/{cid}')
    @GetMapping("/keywords-with-item-id/{cid}")
    public List<Keyword> getKeywordsWithItemId(@PathVariable String cid, @RequestHeader(value = "Authorization", required = false) String token) {
        // Validate user ownership
        if (token != null) {
            String rawToken = token.replace("Bearer ", "");
            TokenService.TokenInfo tokenInfo = tokenService.verifyToken(rawToken);
            if (tokenInfo != null) {
                Long userId = tokenInfo.userId;
                Cookie cookie = cookieRepository.findById(cid).orElse(null);
                if (cookie != null && !cookie.getUserId().equals(userId)) {
                    throw new RuntimeException("无权限访问该Cookie");
                }
            }
        }
        return keywordRepository.findByCookieId(cid);
    }
    
    // 对应 Python: @app.post('/keywords-with-item-id/{cid}')
    @PostMapping("/keywords-with-item-id/{cid}")
    public Map<String, Object> updateKeywordsWithItemId(@PathVariable String cid, 
                                                                  @RequestBody KeywordWithItemIdRequest request,
                                                                  @RequestHeader(value = "Authorization", required = false) String token) {
        // Validate user ownership
        if (token != null) {
            String rawToken = token.replace("Bearer ", "");
            TokenService.TokenInfo tokenInfo = tokenService.verifyToken(rawToken);
            if (tokenInfo != null) {
                Long userId = tokenInfo.userId;
                Cookie cookie = cookieRepository.findById(cid).orElse(null);
                if (cookie != null && !cookie.getUserId().equals(userId)) {
                    throw new RuntimeException("无权限操作该Cookie");
                }
            }
        }

        Set<String> keywordSet = new HashSet<>();
        List<Keyword> keywordsToSave = new ArrayList<>();

        for (Map<String, Object> kwData : request.getKeywords()) {
            String keywordStr = (String) kwData.get("keyword");
            if (keywordStr == null || keywordStr.trim().isEmpty()) {
                throw new RuntimeException("关键词不能为空");
            }
            keywordStr = keywordStr.trim();
            
            String reply = (String) kwData.getOrDefault("reply", "");
            String itemId = (String) kwData.getOrDefault("item_id", "");
            if (itemId != null && itemId.trim().isEmpty()) itemId = null; // Normalize empty to null

            // Check duplicate in request
            String key = keywordStr + "|" + (itemId == null ? "" : itemId);
            if (keywordSet.contains(key)) {
                String itemText = itemId != null ? "（商品ID: " + itemId + "）" : "（通用关键词）";
                throw new RuntimeException("关键词 '" + keywordStr + "' " + itemText + " 在当前提交中重复");
            }
            keywordSet.add(key);
            
            // Check conflict with image keywords in DB
            if (itemId != null) {
                if (!keywordRepository.findConflictImageKeywords(cid, keywordStr, itemId).isEmpty()) {
                    throw new RuntimeException("关键词 '" + keywordStr + "' （商品ID: " + itemId + "） 已存在（图片关键词），无法保存为文本关键词");
                }
            } else {
                if (!keywordRepository.findConflictGenericImageKeywords(cid, keywordStr).isEmpty()) {
                     throw new RuntimeException("关键词 '" + keywordStr + "' （通用关键词） 已存在（图片关键词），无法保存为文本关键词");
                }
            }
            
            Keyword k = new Keyword();
            k.setCookieId(cid);
            k.setKeyword(keywordStr);
            k.setReply(reply);
            k.setItemId(itemId);
            k.setType("text");
            keywordsToSave.add(k);
        }
        
        // Transactional update
        updateKeywordsTransactional(cid, keywordsToSave);
        
        return Map.of("msg", "updated", "count", keywordsToSave.size());
    }
    
    @Transactional
    protected void updateKeywordsTransactional(String cid, List<Keyword> newKeywords) {
        keywordRepository.deleteTextKeywordsByCookieId(cid);
        keywordRepository.saveAll(newKeywords);
    }

    // 对应 Python: @app.get('/keywords/{cid}')
    @GetMapping("/keywords/{cid}")
    public List<Keyword> getKeywords(@PathVariable String cid) {
        return keywordRepository.findByCookieId(cid);
    }
    
    // 对应 Python: @app.post('/keywords/{cid}')
    @PostMapping("/keywords/{cid}")
    public Keyword addKeyword(@PathVariable String cid, @RequestBody Keyword keyword) {
        keyword.setCookieId(cid);
        return keywordRepository.save(keyword);
    }
    
    // 对应 Python: @app.delete('/keywords/{cid}/{index}') 
    // Python used index, Java uses ID. 
    @DeleteMapping("/keywords/{cid}/{id}")
    public void deleteKeyword(@PathVariable String cid, @PathVariable Long id) {
        keywordRepository.deleteById(id);
    }

    // ------------------------- Default Reply -------------------------

    @GetMapping("/default-replies/{cid}")
    public DefaultReply getDefaultReply(@PathVariable String cid) {
        return defaultReplyRepository.findById(cid).orElse(null);
    }

    @PostMapping("/default-replies/{cid}")
    public DefaultReply updateDefaultReply(@PathVariable String cid, @RequestBody DefaultReply defaultReply) {
        defaultReply.setCookieId(cid);
        return defaultReplyRepository.save(defaultReply);
    }
    
    // ------------------------- AI Settings -------------------------
    
    // GET /ai-reply-settings - Get all AI settings for current user (Aggregated)
    @GetMapping("/ai-reply-settings")
    public Map<String, AiReplySetting> getAllAiSettings() {
        List<String> cookieIds = cookieRepository.findAll().stream()
                .map(Cookie::getId)
                .collect(Collectors.toList());

        List<AiReplySetting> settings = aiReplySettingRepository.findAllById(cookieIds);
        
        return settings.stream().collect(Collectors.toMap(AiReplySetting::getCookieId, s -> s));
    }
    
    @GetMapping("/ai-reply-settings/{cookieId}")
    public AiReplySetting getAiSetting(@PathVariable String cookieId) {
        return aiReplySettingRepository.findById(cookieId).orElse(null);
    }

    @PutMapping("/ai-reply-settings/{cookieId}")
    public AiReplySetting updateAiSetting(@PathVariable String cookieId, @RequestBody AiReplySetting setting) {
        setting.setCookieId(cookieId);
        return aiReplySettingRepository.save(setting);
    }

    @PostMapping("/ai-reply-test/{cookieId}")
    public Map<String, String> testAiReply(@PathVariable String cookieId, @RequestBody Map<String, Object> testData) {
        String chatId = (String) testData.getOrDefault("chat_id", "test_chat_" + System.currentTimeMillis());
        String userId = (String) testData.getOrDefault("user_id", "test_user");
        String itemId = (String) testData.getOrDefault("item_id", "test_item");
        String message = (String) testData.get("message");
        
        String reply = aiReplyService.generateReply(cookieId, chatId, userId, itemId, message);
        return java.util.Collections.singletonMap("reply", reply);
    }
}
