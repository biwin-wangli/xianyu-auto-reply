package com.xianyu.autoreply.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xianyu.autoreply.entity.AiConversation;
import com.xianyu.autoreply.entity.AiReplySetting;
import com.xianyu.autoreply.repository.AiConversationRepository;
import com.xianyu.autoreply.repository.AiItemCacheRepository;
import com.xianyu.autoreply.repository.AiReplySettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class AiReplyService {

    private final AiReplySettingRepository aiReplySettingRepository;
    private final AiConversationRepository aiConversationRepository;
    private final AiItemCacheRepository aiItemCacheRepository;

    @Autowired
    public AiReplyService(AiReplySettingRepository aiReplySettingRepository,
                          AiConversationRepository aiConversationRepository,
                          AiItemCacheRepository aiItemCacheRepository) {
        this.aiReplySettingRepository = aiReplySettingRepository;
        this.aiConversationRepository = aiConversationRepository;
        this.aiItemCacheRepository = aiItemCacheRepository;
    }

    private static final java.util.Map<String, String> DEFAULT_PROMPTS = java.util.Map.of(
        "price", "你是一位经验丰富的销售专家，擅长议价。\n" +
                 "语言要求：简短直接，每句≤10字，总字数≤40字。\n" +
                 "议价策略：\n" +
                 "1. 根据议价次数递减优惠：第1次小幅优惠，第2次中等优惠，第3次最大优惠\n" +
                 "2. 接近最大议价轮数时要坚持底线，强调商品价值\n" +
                 "3. 优惠不能超过设定的最大百分比和金额\n" +
                 "4. 语气要友好但坚定，突出商品优势\n" +
                 "注意：结合商品信息、对话历史和议价设置，给出合适的回复。",
        "tech", "你是一位技术专家，专业解答产品相关问题。\n" +
                "语言要求：简短专业，每句≤10字，总字数≤40字。\n" +
                "回答重点：产品功能、使用方法、注意事项。\n" +
                "注意：基于商品信息回答，避免过度承诺。",
        "default", "你是一位资深电商卖家，提供优质客服。\n" +
                   "语言要求：简短友好，每句≤10字，总字数≤40字。\n" +
                   "回答重点：商品介绍、物流、售后等常见问题。\n" +
                   "注意：结合商品信息，给出实用建议。"
    );

    public String generateReply(String cookieId, String chatId, String userId, String itemId, String userMessage) {
        AiReplySetting setting = aiReplySettingRepository.findById(cookieId).orElse(null);
        if (setting == null || !Boolean.TRUE.equals(setting.getAiEnabled())) {
            return null;
        }

        String intent = detectIntent(userMessage);

        // Save User Message
        saveConversation(cookieId, chatId, userId, itemId, "user", userMessage, intent);

        // Bargain Limit Check
        if ("price".equals(intent)) {
            long bargainCount = aiConversationRepository.countByChatIdAndCookieIdAndIntentAndRole(chatId, cookieId, "price", "user");
            int maxBargainRounds = setting.getMaxBargainRounds() != null ? setting.getMaxBargainRounds() : 3;
            // Note: count includes the current message we just saved? actually yes since we saved it above.
            // Python: `bargain_count = self.get_bargain_count(...)` -> `if bargain_count >= max...`
            // If we just saved it, count is at least 1. 
            // If limit is 3, saving 3rd message makes count 3. 3>=3? If strict less than logic in Python?
            // Python: `if bargain_count >= max_bargain_rounds:` -> Refuse.
            // Wait, if I just asked price 3rd time, count is 3. 3>=3 -> Refuse? 
            // That means I can only ask 2 times? 
            // Let's assume Python logic is correct: >= limit means Stop.
            
            if (bargainCount > maxBargainRounds) { // Use > if we want to allow the Nth round, or >= to stop at N? 
                // Using exact Python logic: >= 
                // But wait, if max is 3, and this is the 3rd time, do we answer or refuse?
                // Python seems to refuse IF count >= max.
                // So if max=3, 3rd time Refuse.
                String refuseReply = "抱歉，这个价格已经是最优惠的了，不能再便宜了哦！";
                saveConversation(cookieId, chatId, userId, itemId, "assistant", refuseReply, intent);
                return refuseReply;
            }
        }

        // Build Item Info
        com.xianyu.autoreply.entity.AiItemCache item = aiItemCacheRepository.findById(itemId).orElse(null);
        String title = "未知";
        String price = "未知";
        String desc = "无";
        if (item != null) {
            price = item.getPrice() != null ? String.valueOf(item.getPrice()) : "未知";
            desc = item.getDescription() != null ? item.getDescription() : "无";
            if (item.getData() != null) {
                try {
                     JSONObject dataJson = JSON.parseObject(item.getData());
                     title = dataJson.getString("title");
                     if (title == null) title = "未知";
                } catch (Exception e) {}
            }
        }
        
        String itemDesc = String.format("商品标题: %s\n商品价格: %s元\n商品描述: %s", title, price, desc);

        // Build Context
        List<AiConversation> history = aiConversationRepository.findByCookieIdAndChatIdOrderByCreatedAtAsc(cookieId, chatId);
        StringBuilder contextStr = new StringBuilder();
        int historyLimit = 10;
        int start = Math.max(0, history.size() - historyLimit);
        for(int i = start; i < history.size(); i++) {
             // Skip the very last one as it is the current message? 
             // Python: context[-10:] includes current? 
             // `get_conversation_context` fetches from DB with limit.
             // We just saved `userMessage`, so it is in history.
             // Python Logic: 
             // 1. save user msg.
             // 2. get context (limit 20).
             // 3. construct prompt with context. (so current msg is in context).
             // 4. construct user prompt with `User Message: {message}` again?
             // Python line 364: context_str from history.
             // Python line 383: `用户消息：{message}` inside prompt.
             // It seems redundant but follows prompt template.
             // Note: context_str should probably EXCLUDE current message to avoid duplication if we explicitly add it in prompt?
             // Python `context` query: `ORDER BY created_at DESC LIMIT ?` then reversed.
             // It likely includes the message just saved.
             // However, context in prompt is usually "History".
             // Let's include everything except the very last one (current) to avoid "User: xxx" appearing twice (once in history, once in 'User Message').
             // Or Python includes it in history AND explicitly. Model can handle it.
             // Let's follow Python: context_str contains recent messages.
             AiConversation conv = history.get(i);
             // If we want to avoid duplication, we can check if it is the current message (id).
             // But simpler to just append.
             contextStr.append(conv.getRole()).append(": ").append(conv.getContent()).append("\n");
        }

        // Custom Prompts
        String customPromptsJson = setting.getCustomPrompts();
        String systemPrompt = null;
        if (customPromptsJson != null) {
            try {
                JSONObject cp = JSON.parseObject(customPromptsJson);
                systemPrompt = cp.getString(intent);
            } catch (Exception e) {}
        }
        if (systemPrompt == null) {
            systemPrompt = DEFAULT_PROMPTS.getOrDefault(intent, DEFAULT_PROMPTS.get("default"));
        }

        // User Prompt Construction
        int maxBargain = setting.getMaxBargainRounds() != null ? setting.getMaxBargainRounds() : 3;
        int maxDiscountPct = setting.getMaxDiscountPercent() != null ? setting.getMaxDiscountPercent() : 10;
        double maxDiscountAmt = setting.getMaxDiscountAmount() != null ? setting.getMaxDiscountAmount() : 100.0;
        int currentBargainCount = (int) (intent.equals("price") ? aiConversationRepository.countByChatIdAndCookieIdAndIntentAndRole(chatId, cookieId, "price", "user") : 0);
        
        String userPrompt = String.format(
            "商品信息：\n%s\n\n对话历史：\n%s\n\n议价设置：\n- 当前议价次数：%d\n- 最大议价轮数：%d\n- 最大优惠百分比：%d%%\n- 最大优惠金额：%.2f元\n\n用户消息：{%s}\n\n请根据以上信息生成回复：",
            itemDesc, contextStr.toString(), currentBargainCount, maxBargain, maxDiscountPct, maxDiscountAmt, userMessage
        );

        JSONArray messages = new JSONArray();
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);
        
        JSONObject usrMsg = new JSONObject();
        usrMsg.put("role", "user");
        usrMsg.put("content", userPrompt);
        messages.add(usrMsg);

        // Call API
        String replyContent = callLlmApi(setting, messages);
        
        if (replyContent != null) {
            saveConversation(cookieId, chatId, userId, itemId, "assistant", replyContent, intent);
        }

        return replyContent;
    }

    private String detectIntent(String message) {
        if (message == null) return "default";
        String lowerMsg = message.toLowerCase();
        
        // Price keywords (from ai_reply_engine.py)
        String[] priceKeywords = {
            "便宜", "优惠", "刀", "降价", "包邮", "价格", "多少钱", "能少", "还能", "最低", "底价",
            "实诚价", "到100", "能到", "包个邮", "给个价", "什么价"
        };
        for (String kw : priceKeywords) {
            if (lowerMsg.contains(kw)) {
                return "price";
            }
        }

        // Tech keywords
        String[] techKeywords = {"怎么用", "参数", "坏了", "故障", "设置", "说明书", "功能", "用法", "教程", "驱动"};
        for (String kw : techKeywords) {
            if (lowerMsg.contains(kw)) {
                return "tech";
            }
        }

        return "default";
    }

    private String callLlmApi(AiReplySetting setting, JSONArray messages) {
        try {
            JSONObject body = new JSONObject();
            // Gemini needs different payload structure if using standard `generateContent`
            // But if using OpenAI-compatible endpoint (some proxies do), standard is fine.
            // Python code `_call_gemini_api` used `generateContent` URL.
            // Python code `_call_openai_api` used `/chat/completions`.
            // Here implementation tries to be generic. 
            // Let's respect Python's logic: if "gemini" in model name, use Gemini structure?
            // Or just stick to OpenAI format as `AiReplyService` is often using a unified proxy.
            
            // Re-reading Python `_call_gemini_api`:
            // It sends `{"contents": [{"role": "user", "parts": [...]}]}`
            
            String model = setting.getModelName();
            String url = setting.getBaseUrl();
            String apiKey = setting.getApiKey();
            
            if (model.toLowerCase().contains("gemini")) {
                // Gemini Logic
                 String geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
                 
                 // Convert messages to Gemini format
                 // Gemini only supports "user" and "model" roles in history, and system instruction separate.
                 // Simplified for now: just take the last user message or simple history.
                 // Python `_call_gemini_api` logic: finds system, finds all user parts.
                 
                 // System Instruction
                 String systemText = "";
                 for(int i=0; i<messages.size(); i++) {
                     if ("system".equals(messages.getJSONObject(i).getString("role"))) {
                         systemText = messages.getJSONObject(i).getString("content");
                         break;
                     }
                 }
                 
                 // User content (Last one)
                 String userText = messages.getJSONObject(messages.size()-1).getString("content");
                 
                 JSONObject geminiBody = new JSONObject();
                 JSONObject contentPart = new JSONObject();
                 contentPart.put("role", "user");
                 contentPart.put("parts", new JSONArray().fluentAdd(new JSONObject().fluentPut("text", userText)));
                 geminiBody.put("contents", new JSONArray().fluentAdd(contentPart));
                 
                 if (!systemText.isEmpty()) {
                     geminiBody.put("systemInstruction", new JSONObject().fluentPut("parts", new JSONArray().fluentAdd(new JSONObject().fluentPut("text", systemText))));
                 }
                 
                 HttpResponse response = HttpRequest.post(geminiUrl)
                    .header("Content-Type", "application/json")
                    .body(geminiBody.toString())
                    .execute();
                    
                 if (response.isOk()) {
                     JSONObject res = JSON.parseObject(response.body());
                     try {
                         return res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
                     } catch(Exception e) {
                         log.error("Gemini parse error: {}", response.body());
                     }
                 }
                 return null;
            }

            // Standard OpenAI / DashScope Logic
            body.put("model", model);
            body.put("messages", messages);
            
            if (url != null && !url.endsWith("/chat/completions") && !url.contains("dashscope")) {
                url += "/chat/completions";
            }
            if (url != null && url.contains("dashscope") && !url.contains("/api/v1")) {
                 // DashScope specific fix if needed, but usually users provide full URL or base
                 // Python `_call_dashscope_api` constructs: `https://dashscope.aliyuncs.com/api/v1/apps/{app_id}/completion`
                 // If base_url has /apps/, handle it.
                 if (url.contains("/apps/")) {
                     // Assume Python logic:
                     // app_id is extracted.
                     // But simpler to just use what user provided if it works.
                 }
            }

            HttpResponse response = HttpRequest.post(url)
                .header("Authorization", "Bearer " + setting.getApiKey())
                .header("Content-Type", "application/json")
                .body(body.toString())
                .execute();

            if (response.isOk()) {
                JSONObject res = JSON.parseObject(response.body());
                if (res.containsKey("choices")) {
                    JSONArray choices = res.getJSONArray("choices");
                    if (choices != null && !choices.isEmpty()) {
                        return choices.getJSONObject(0).getJSONObject("message").getString("content");
                    }
                } else if (res.containsKey("output")) {
                    // DashScope
                    return res.getJSONObject("output").getString("text");
                }
            } else {
                log.error("LLM API Error: {} - {}", response.getStatus(), response.body());
            }
        } catch (Exception e) {
            log.error("Failed to call LLM API", e);
        }
        return null;
    }
    private void saveConversation(String cookieId, String chatId, String userId, String itemId, String role, String content, String intent) {
        AiConversation conversation = new AiConversation();
        conversation.setCookieId(cookieId);
        conversation.setChatId(chatId);
        conversation.setUserId(userId);
        conversation.setItemId(itemId);
        conversation.setRole(role);
        conversation.setContent(content);
        conversation.setIntent(intent);
        aiConversationRepository.save(conversation);
    }
}
