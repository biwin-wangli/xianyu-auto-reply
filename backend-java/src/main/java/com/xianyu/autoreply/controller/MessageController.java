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

    @Autowired
    public MessageController(XianyuClientService xianyuClientService) {
        this.xianyuClientService = xianyuClientService;
    }

    @PostMapping
    public Response sendMessage(@RequestBody SendMessageRequest request) {
        // Validate API Key (Skipped for simple migration, add later)
        
        XianyuClient client = xianyuClientService.getClient(request.getCookie_id());
        if (client == null) {
            return new Response(false, "Client not found or not connected");
        }
        
        client.sendMessage(request.getChat_id(), request.getTo_user_id(), request.getMessage());
        return new Response(true, "Message sent");
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
