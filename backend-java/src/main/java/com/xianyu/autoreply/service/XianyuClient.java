package com.xianyu.autoreply.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.model.ConnectionState;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.utils.XianyuUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class XianyuClient implements WebSocketHandler {

    private final String cookieId;
    private final CookieRepository cookieRepository;
    private final ReplyService replyService;
    private final BrowserService browserService;
    private final StandardWebSocketClient wsClient;
    
    @Getter
    private ConnectionState connectionState = ConnectionState.DISCONNECTED;
    
    private WebSocketSession session;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatTask;
    private Map<String, String> cookiesData;
    private String myId; // Added

    private static final String WEBSOCKET_URL = "wss://wss-goofish.dingtalk.com/";
    private static final long HEARTBEAT_INTERVAL = 15; // seconds

    public XianyuClient(String cookieId, CookieRepository cookieRepository, ReplyService replyService, BrowserService browserService) {
        this.cookieId = cookieId;
        this.cookieRepository = cookieRepository;
        this.replyService = replyService;
        this.browserService = browserService;
        this.wsClient = new StandardWebSocketClient();
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    public void start() {
        log.info("【{}】Starting XianyuClient...", cookieId);
        loadCookies();
        connect();
    }

    public void stop() {
        log.info("【{}】Stopping XianyuClient...", cookieId);
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException e) {
                log.error("Error closing session", e);
            }
        }
        scheduler.shutdown();
        connectionState = ConnectionState.CLOSED;
    }

    private void loadCookies() {
        Cookie cookie = cookieRepository.findById(cookieId).orElse(null);
        if (cookie == null) {
            log.error("【{}】Cookie not found in DB", cookieId);
            throw new RuntimeException("Cookie not found");
        }
        this.cookiesData = XianyuUtils.transCookies(cookie.getValue());
        if (this.cookiesData.containsKey("unb")) {
            this.myId = this.cookiesData.get("unb");
            log.info("【{}】Initialized myId: {}", cookieId, myId);
        } else {
            throw new RuntimeException("Cookie missing 'unb' field, cannot initialize XianyuClient");
        }
    }

    private void connect() {
        if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.CONNECTING) {
            return;
        }

        // Simple validation check (e.g., check timestamp or minimal api call)
        // If invalid, refresh
        // For now, we trust DB, but if connection Fails, we should refresh.
        
        connectionState = ConnectionState.CONNECTING;
        log.info("【{}】Connecting to WebSocket...", cookieId);
        
        wsClient.doHandshake(this, null, java.net.URI.create(WEBSOCKET_URL))
            .addCallback(
                result -> {
                    log.info("【{}】Handshake success", cookieId);
                    this.session = result;
                    connectionState = ConnectionState.CONNECTED;
                    startHeartbeat();
                },
                ex -> {
                    log.error("【{}】Handshake failed: {}", cookieId, ex.getMessage());
                    // Try to refresh cookie if handshake fails (likely token expired)
                    log.info("【{}】Attempting to refresh cookies...", cookieId);
                    Map<String, String> newCookies = browserService.refreshCookies(cookieId);
                    if (newCookies != null && !newCookies.isEmpty()) {
                         // Update DB logic is inside refreshCookies? No, extract it.
                         // BrowserService.refreshCookies returns map. We need to save it.
                         updateCookiesInDb(newCookies);
                         // Retry connect
                         scheduleReconnect();
                    } else {
                        connectionState = ConnectionState.FAILED;
                        scheduleReconnect();
                    }
                }
            );
    }
    
    private void updateCookiesInDb(Map<String, String> newCookies) {
        Cookie cookie = cookieRepository.findById(cookieId).orElse(null);
        if (cookie != null) {
             // Convert map to string
             StringBuilder sb = new StringBuilder();
             newCookies.forEach((k, v) -> sb.append(k).append("=").append(v).append("; "));
             cookie.setValue(sb.toString());
             cookieRepository.save(cookie);
             this.cookiesData = newCookies;
             log.info("【{}】Cookies updated in DB", cookieId);
        }
    }

    private void startHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
    }

    private void sendHeartbeat() {
        if (session != null && session.isOpen()) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("lwp", "/!");
                JSONObject headers = new JSONObject();
                headers.put("mid", XianyuUtils.generateMid());
                msg.put("headers", headers);

                session.sendMessage(new TextMessage(msg.toJSONString()));
                log.debug("Heartbeat sent for {}", cookieId);
            } catch (Exception e) {
                log.error("Failed to send heartbeat for {}", cookieId, e);
            }
        }
    }

    private void scheduleReconnect() {
        scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("【{}】Connection established: {}", cookieId, session.getId());
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        try {
            String payload = message.getPayload().toString();
            // Assuming payload is the raw JSON key "1", "2" etc or similar wrapper.
            // But based on Python code, it receives a payload, decrypts it.
            // The python code: 
            // sync_data = message_data["body"]["syncPushPackage"]["data"][0]
            // data = base64.b64decode(sync_data["data"])
            
            JSONObject root = JSON.parseObject(payload);
            
            // Check headers for heartbeat ack?
            // Python sends Ack if valid message.
            
            if (root.containsKey("body") && root.getJSONObject("body").containsKey("syncPushPackage")) {
                JSONArray dataArray = root.getJSONObject("body").getJSONObject("syncPushPackage").getJSONArray("data");
                if (dataArray != null && !dataArray.isEmpty()) {
                    for (int i = 0; i < dataArray.size(); i++) {
                        JSONObject syncItem = dataArray.getJSONObject(i);
                        String encryptedData = syncItem.getString("data");
                        if (encryptedData != null) {
                            String decrypted = XianyuUtils.decrypt(encryptedData);
                            log.debug("【{}】Decrypted content: {}", cookieId, decrypted);
                            
                            JSONObject msgJson = JSON.parseObject(decrypted);
                            processDecryptedMessage(msgJson);
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("【{}】Error handling message", cookieId, e);
        }
    }

    private void processDecryptedMessage(JSONObject message) {
        try {
            // Check format matches Python "message_1" logic
            if (!message.containsKey("1")) return;
            
            JSONObject message1 = message.getJSONObject("1");
            if (!message1.containsKey("10")) return;
            
            JSONObject message10 = message1.getJSONObject("10"); // Details
            
            String sendUserId = message10.getString("senderUserId");
            String sendUserName = message10.getString("senderNick");
            String content = message10.getString("reminderContent"); // Send message
            String chatIdRaw = message1.getString("2");
            String chatId = (chatIdRaw != null && chatIdRaw.contains("@")) ? chatIdRaw.split("@")[0] : chatIdRaw;
            
            // Extract itemId
            String itemId = null;
            String reminderUrl = message10.getString("reminderUrl");
            if (reminderUrl != null && reminderUrl.contains("itemId=")) {
                itemId = reminderUrl.split("itemId=")[1].split("&")[0];
            } else {
                // Fallback attempt recursive? Or default
                itemId = "auto_" + sendUserId + "_" + System.currentTimeMillis();
            }
            
            // Basic self-check to avoid loops (though Python logic handles this deeper)
            // If sender is me? logic in Python checks 'self.myid'. We need 'myid' in Cookie or User.
            // For now assume we process all incoming.
            
            log.info("【{}】Received from {}: {}", cookieId, sendUserName, content);
            
            // Determine Reply
            String reply = replyService.determineReply(cookieId, chatId, sendUserId, itemId, content);
            if (reply != null) {
                sendMessage(chatId, sendUserId, reply);
            }
            
        } catch (Exception e) {
            log.error("【{}】Error processing decrypted message", cookieId, e);
        }
    }

    public void sendMessage(String chatId, String toUserId, String content) {
        if (session == null || !session.isOpen()) return;

        try {
            // Construct text message payload matching Python's 'send_msg' structure
            // 1. Prepare Base64 encoded text object
            JSONObject textObj = new JSONObject();
            textObj.put("contentType", 1);
            JSONObject innerText = new JSONObject();
            innerText.put("text", content);
            textObj.put("text", innerText);

            String textBase64 = java.util.Base64.getEncoder().encodeToString(
                textObj.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );

            // 2. Prepare Body Item 1 (Message Content)
            JSONObject bodyItem1 = new JSONObject();
            bodyItem1.put("uuid", XianyuUtils.generateUuid());
            bodyItem1.put("cid", chatId + "@goofish");
            bodyItem1.put("conversationType", 1);
            
            JSONObject contentObj = new JSONObject();
            contentObj.put("contentType", 101);
            JSONObject customObj = new JSONObject();
            customObj.put("type", 1);
            customObj.put("data", textBase64);
            contentObj.put("custom", customObj);
            bodyItem1.put("content", contentObj);

            bodyItem1.put("redPointPolicy", 0);
            JSONObject extension = new JSONObject();
            extension.put("extJson", "{}");
            bodyItem1.put("extension", extension);
            
            JSONObject ctx = new JSONObject();
            ctx.put("appVersion", "1.0");
            ctx.put("platform", "web");
            bodyItem1.put("ctx", ctx);
            bodyItem1.put("mtags", new JSONObject());
            bodyItem1.put("msgReadStatusSetting", 1);

            // 3. Prepare Body Item 2 (Receivers)
            JSONObject bodyItem2 = new JSONObject();
            JSONArray receivers = new JSONArray();
            receivers.add(toUserId + "@goofish");
            receivers.add(myId + "@goofish");
            bodyItem2.put("actualReceivers", receivers);

            // 4. Assemble Final Payload
            JSONObject payload = new JSONObject();
            payload.put("lwp", "/r/MessageSend/sendByReceiverScope");
            JSONObject headers = new JSONObject();
            headers.put("mid", XianyuUtils.generateMid());
            payload.put("headers", headers);
            
            JSONArray body = new JSONArray();
            body.add(bodyItem1);
            body.add(bodyItem2);
            payload.put("body", body);

            session.sendMessage(new TextMessage(payload.toJSONString()));
            log.info("【{}】Sending reply to {}: {}", cookieId, chatId, content);
            
        } catch (Exception e) {
            log.error("【{}】Failed to send message", cookieId, e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("【{}】Transport error: {}", cookieId, exception.getMessage());
        connectionState = ConnectionState.FAILED;
        scheduleReconnect();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        log.info("【{}】Connection closed: {}", cookieId, closeStatus);
        connectionState = ConnectionState.DISCONNECTED;
        scheduleReconnect();
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
}
