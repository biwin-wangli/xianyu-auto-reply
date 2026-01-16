package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.service.XianyuClient;
import com.xianyu.autoreply.service.XianyuClientService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/send-message")
public class MessageController {

    private final XianyuClientService xianyuClientService;
    private final com.xianyu.autoreply.repository.SystemSettingRepository systemSettingRepository;
    
    // Default key matching Python's API_SECRET_KEY
    private static final String DEFAULT_API_KEY = "xianyu_api_secret_2024";

    @Autowired
    public MessageController(XianyuClientService xianyuClientService, 
                             com.xianyu.autoreply.repository.SystemSettingRepository systemSettingRepository) {
        this.xianyuClientService = xianyuClientService;
        this.systemSettingRepository = systemSettingRepository;
    }

    @PostMapping
    public Response sendMessage(@RequestBody SendMessageRequest request) {
        // Validate API Key
        if (!verifyApiKey(request.getApi_key())) {
            return new Response(false, "Invalid API Key");
        }
        
        XianyuClient client = xianyuClientService.getClient(request.getCookie_id());
        if (client == null) {
            return new Response(false, "Client not found or not connected");
        }
        
        // Return result from client (assuming synchronous for now, or fire-and-forget)
        try {
             client.sendMessage(request.getChat_id(), request.getTo_user_id(), request.getMessage());
             return new Response(true, "Message sent");
        } catch (Exception e) {
             return new Response(false, "Failed to send message: " + e.getMessage());
        }
    }
    
    private boolean verifyApiKey(String apiKey) {
        // Fetch from system settings
        String savedKey = systemSettingRepository.findById("qq_reply_secret_key")
                .map(com.xianyu.autoreply.entity.SystemSetting::getValue)
                .orElse(DEFAULT_API_KEY);
                
        return savedKey.equals(apiKey);
    }

    @Data
    public static class SendMessageRequest {
        private String api_key;
        private String cookie_id;
        private String chat_id;
        private String to_user_id;
        private String message;
    }
    
    @Data
    public static class Response {
        private boolean success;
        private String message;
        
        public Response(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
