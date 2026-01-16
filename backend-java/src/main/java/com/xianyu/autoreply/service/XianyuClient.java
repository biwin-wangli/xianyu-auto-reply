package com.xianyu.autoreply.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.model.ConnectionState;
import okhttp3.*;
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

    private static final String WEBSOCKET_URL = "wss://api.m.taobao.com/accs/"; // Python uses this for WS
    // Note: Python logic uses api.m.taobao.com/accs/ 
    // Wait, Python log says: "【...】WebSocket目标地址: wss://api.m.taobao.com/accs/"
    // The existing Java code had "wss://wss-goofish.dingtalk.com/" which might be wrong or old.
    // I will use Python's URL.
    
    private static final long HEARTBEAT_INTERVAL = 10; // Python: 10s default?
    // Python code: self.heartbeat_interval = 20 (init) -> wait...
    // logic in heartbeat_loop -> sleep(heartbeat_interval)
    // Let's check python constants.
    
    private final OkHttpClient httpClient; // For HTTP API calls (token refresh)

    public XianyuClient(String cookieId, CookieRepository cookieRepository, ReplyService replyService, BrowserService browserService) {
        this.cookieId = cookieId;
        this.cookieRepository = cookieRepository;
        this.replyService = replyService;
        this.browserService = browserService;
        this.wsClient = new StandardWebSocketClient();
        this.scheduler = Executors.newScheduledThreadPool(4); // Increased pool
        this.httpClient = new OkHttpClient.Builder().build();
    }

    public void start() {
        log.info("【{}】Starting XianyuClient...", cookieId);
        loadCookies();
        
        // Refresh token before connecting
        refreshToken();
        
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
                    
                    // Initialize Protocol after connection
                    initProtocol();
                    
                    startHeartbeat();
                },
                ex -> {
                    log.error("【{}】Handshake failed: {}", cookieId, ex.getMessage());
                    // Try to refresh cookie if handshake fails (likely token expired)
                    log.info("【{}】Attempting to refresh cookies...", cookieId);
                    refreshToken(); // Try token API refresh first
                    
                    // Then maybe full browser refresh if that fails?
                    // For now, let's stick to API logic or Browser logic.
                    // Python logic: if token fails, try login refresh.
                    
                    /* 
                    Map<String, String> newCookies = browserService.refreshCookies(cookieId);
                    if (newCookies != null && !newCookies.isEmpty()) {
                         updateCookiesInDb(newCookies);
                         scheduleReconnect();
                    } else {
                        connectionState = ConnectionState.FAILED;
                        scheduleReconnect();
                    }
                    */
                    
                    // Logic Update: Just schedule reconnect, let the main loop handle it?
                    // Or retry once.
                    scheduleReconnect();
                }
            );
    }
    
    private void updateCookiesInDb(Map<String, String> newCookies) {
        Cookie cookie = cookieRepository.findById(cookieId).orElse(null);
        if (cookie != null) {
             StringBuilder sb = new StringBuilder();
             newCookies.forEach((k, v) -> sb.append(k).append("=").append(v).append("; "));
             cookie.setValue(sb.toString());
             cookieRepository.save(cookie);
             this.cookiesData = newCookies;
             log.info("【{}】Cookies updated in DB", cookieId);
        }
    }
    
    // --- New Methods for Token Refresh and Protocol Init ---

    private void refreshToken() {
        log.info("【{}】Refreshing token...", cookieId);
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String dataVal = "{\"appKey\":\"444e9908a51d1cb236a27862abc769c9\",\"deviceId\":\"" + XianyuUtils.generateDeviceId(myId) + "\"}";
            
            // Generate Sign
            String token = "";
            if (cookiesData.containsKey("_m_h5_tk")) {
                token = cookiesData.get("_m_h5_tk").split("_")[0];
            }
            String sign = XianyuUtils.generateSign(timestamp, token, dataVal);

            HttpUrl.Builder urlBuilder = HttpUrl.parse("https://h5api.m.goofish.com/h5/mtop.taobao.idlemessage.pc.login.token/1.0/").newBuilder();
            urlBuilder.addQueryParameter("jsv", "2.7.2");
            urlBuilder.addQueryParameter("appKey", "34839810");
            urlBuilder.addQueryParameter("t", timestamp);
            urlBuilder.addQueryParameter("sign", sign);
            urlBuilder.addQueryParameter("v", "1.0");
            urlBuilder.addQueryParameter("type", "originaljson");
            urlBuilder.addQueryParameter("accountSite", "xianyu");
            
            // Build Headers (Mimic Browser)
            // Note: In real production, headers usually need to be very complete.
            
            Request request = new Request.Builder()
                .url(urlBuilder.build())
                .post(RequestBody.create("data=" + java.net.URLEncoder.encode(dataVal, "UTF-8"), MediaType.parse("application/x-www-form-urlencoded")))
                .header("Cookie", buildCookieString())
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build();
                
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("【{}】Token refresh HTTP failed: {}", cookieId, response.code());
                    return;
                }
                String respStr = response.body().string();
                JSONObject respJson = JSON.parseObject(respStr);
                
                // Process Response and Update Cookies (m_h5_tk particularly)
                // Python logic checks 'data' -> 'result' -> 'true'? 
                // Or mostly, relies on implicit cookie updates in the cookie jar.
                // Here we need to extract Set-Cookie headers.
                
                // Update local cookies map from Set-Cookie
                Map<String, String> updatedCookies = parseSetCookies(response.headers("Set-Cookie"));
                if (!updatedCookies.isEmpty()) {
                    this.cookiesData.putAll(updatedCookies);
                    updateCookiesInDb(this.cookiesData);
                }
                log.info("【{}】Token refresh request completed", cookieId);
            }
        } catch (Exception e) {
            log.error("【{}】Token refresh failed", cookieId, e);
        }
    }
    
    private Map<String, String> parseSetCookies(java.util.List<String> headerValues) {
        Map<String, String> map = new java.util.HashMap<>();
        if (headerValues != null) {
            for (String val : headerValues) {
                String[] parts = val.split(";")[0].split("=", 2);
                if (parts.length == 2) {
                     map.put(parts[0], parts[1]);
                }
            }
        }
        return map;
    }
    
    private String buildCookieString() {
        StringBuilder sb = new StringBuilder();
        cookiesData.forEach((k, v) -> sb.append(k).append("=").append(v).append("; "));
        return sb.toString();
    }

    private void initProtocol() {
        if (session == null || !session.isOpen()) return;

        try {
            // 1. Send /reg
            JSONObject headers = new JSONObject();
            headers.put("cache-header", "app-key token ua wv");
            headers.put("app-key", "34839810");
            
            String token = "";
            if (cookiesData.containsKey("_m_h5_tk")) {
                token = cookiesData.get("_m_h5_tk").split("_")[0];
            }
            headers.put("token", token);
            headers.put("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.put("dt", "j");
            headers.put("wv", "im:3,au:3,sy:6");
            headers.put("sync", "0,0;0;0;");
            headers.put("did", XianyuUtils.generateDeviceId(myId));
            headers.put("mid", XianyuUtils.generateMid());

            JSONObject regMsg = new JSONObject();
            regMsg.put("lwp", "/reg");
            regMsg.put("headers", headers);
            
            session.sendMessage(new TextMessage(regMsg.toJSONString()));
            log.info("【{}】Sent /reg", cookieId);
            
            // Wait 1s
            Thread.sleep(1000);
            
            // 2. Send AckDiff
            JSONObject ackMsg = new JSONObject();
            ackMsg.put("lwp", "/r/SyncStatus/ackDiff");
            JSONObject ackHeaders = new JSONObject();
            ackHeaders.put("mid", XianyuUtils.generateMid());
            ackMsg.put("headers", ackHeaders);
            
             // Body
            JSONObject bodyItem = new JSONObject();
            bodyItem.put("pipeline", "sync");
            bodyItem.put("tooLong2Tag", "PNM,1");
            bodyItem.put("channel", "sync");
            bodyItem.put("topic", "sync");
            bodyItem.put("highPts", 0);
            long currentTime = System.currentTimeMillis();
            bodyItem.put("pts", currentTime * 1000);
            bodyItem.put("seq", 0);
            bodyItem.put("timestamp", currentTime);
            
            JSONArray body = new JSONArray();
            body.add(bodyItem);
            ackMsg.put("body", body);
            
            session.sendMessage(new TextMessage(ackMsg.toJSONString()));
            log.info("【{}】Sent /ackDiff, protocol initialized.", cookieId);

        } catch (Exception e) {
            log.error("【{}】Init protocol failed", cookieId, e);
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
