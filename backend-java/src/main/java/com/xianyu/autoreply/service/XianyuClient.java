package com.xianyu.autoreply.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.service.captcha.CaptchaHandler;
import com.xianyu.autoreply.utils.XianyuUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 * 闲鱼客户端 - 完整迁移自Python XianyuLive类
 * 负责WebSocket连接管理、消息处理、自动回复等核心功能
 */
@Slf4j
public class XianyuClient extends TextWebSocketHandler {

    // ============== 配置常量 ==============
    private static final String WEBSOCKET_URL = "wss://wss-goofish.dingtalk.com/";
    private static final int HEARTBEAT_INTERVAL = 30; // 心跳间隔（秒）
    private static final int HEARTBEAT_TIMEOUT = 90; // 心跳超时（秒）
    private static final int TOKEN_REFRESH_INTERVAL = 3600; // Token刷新间隔（秒），1小时
    private static final int TOKEN_RETRY_INTERVAL = 300; // Token重试间隔（秒），5分钟
    private static final int MESSAGE_COOLDOWN = 300; // 消息冷却时间（秒），5分钟
    private static final int CLEANUP_INTERVAL = 300; // 清理间隔（秒），5分钟
    private static final int COOKIE_REFRESH_INTERVAL = 3600; // Cookie刷新间隔（秒），1小时
    
    private static final String APP_KEY = "34839810";
    private static final String APP_CONFIG_KEY = "444e9908a51d1cb236a27862abc769c9";

    // ============== 核心字段 ==============
    private final String cookieId; // 账号ID
    private final CookieRepository cookieRepository;
    private final ReplyService replyService;
    private final CaptchaHandler captchaHandler;
    private final BrowserService browserService;

    private String cookiesStr; // Cookie字符串
    private Map<String, String> cookies; // Cookie字典
    private String myId; // 用户ID (unb)
    private String deviceId; // 设备ID
    private Integer userId; // 数据库用户ID

    // ============== WebSocket相关 ==============
    private WebSocketSession webSocketSession;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private final AtomicInteger connectionFailures = new AtomicInteger(0);
    private static final int MAX_CONNECTION_FAILURES = 5;
    private final AtomicLong lastSuccessfulConnection = new AtomicLong(0);
    private final AtomicLong lastStateChangeTime = new AtomicLong(System.currentTimeMillis());

    // ============== Token相关 ==============
    private String currentToken; // 当前Token
    private final AtomicLong lastTokenRefreshTime = new AtomicLong(0);
    private volatile String lastTokenRefreshStatus = "none"; // Token刷新状态

    // ============== 心跳相关 ==============
    private final AtomicLong lastHeartbeatTime = new AtomicLong(0);
    private final AtomicLong lastHeartbeatResponse = new AtomicLong(0);

    // ============== 后台任务 ==============
    private ScheduledExecutorService scheduledExecutor;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> tokenRefreshTask;
    private ScheduledFuture<?> cleanupTask;
    private ScheduledFuture<?> cookieRefreshTask;
    
    // ============== 消息处理相关 ==============
    private final Semaphore messageSemaphore = new Semaphore(100); // 最多100个并发消息
    private final AtomicInteger activeMessageTasks = new AtomicInteger(0);
    private final Map<String, Long> processedMessageIds = new ConcurrentHashMap<>(); // 消息去重
    private static final int MESSAGE_EXPIRE_TIME = 3600; // 消息过期时间（秒），1小时
    private static final int PROCESSED_MESSAGE_IDS_MAX_SIZE = 10000;
    
    // ============== 防重复机制 ==============
    private final Map<String, Long> lastNotificationTime = new ConcurrentHashMap<>(); // 通知防重复
    private static final int NOTIFICATION_COOLDOWN = 300; // 通知冷却时间（秒），5分钟
    private final Map<String, Long> lastDeliveryTime = new ConcurrentHashMap<>(); // 发货防重复
    private static final int DELIVERY_COOLDOWN = 600; // 发货冷却时间（秒），10分钟
    private final Map<String, Long> confirmedOrders = new ConcurrentHashMap<>(); // 已确认订单
    private static final int ORDER_CONFIRM_COOLDOWN = 600; // 订单确认冷却时间（秒），10分钟
    
    // ============== Cookie刷新相关 ==============
    private final AtomicLong lastMessageReceivedTime = new AtomicLong(0); // 上次收到消息时间
    private final AtomicLong lastCookieRefreshTime = new AtomicLong(0);

    // ============== HTTP Client ==============
    private final OkHttpClient httpClient;

    /**
     * 连接状态枚举
     */
    public enum ConnectionState {
        DISCONNECTED("disconnected"),
        CONNECTING("connecting"),
        CONNECTED("connected"),
        RECONNECTING("reconnecting"),
        FAILED("failed"),
        CLOSED("closed");

        private final String value;

        ConnectionState(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * 构造函数
     */
    public XianyuClient(String cookieId, CookieRepository cookieRepository, 
                        ReplyService replyService, CaptchaHandler captchaHandler, 
                        BrowserService browserService) {
        this.cookieId = cookieId;
        this.cookieRepository = cookieRepository;
        this.replyService = replyService;
        this.captchaHandler = captchaHandler;
        this.browserService = browserService;

        // 创建HTTP客户端
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        // 创建定时任务线程池
        this.scheduledExecutor = Executors.newScheduledThreadPool(5, r -> {
            Thread t = new Thread(r);
            t.setName("XianyuClient-" + cookieId + "-" + t.getId());
            t.setDaemon(true);
            return t;
        });

        log.info("【{}】XianyuClient实例已创建", cookieId);
    }

    /**
     * 启动客户端 - 对应Python的main()方法
     */
    public void start() {
        if (running.get()) {
            log.warn("【{}】客户端已在运行中", cookieId);
            return;
        }

        running.set(true);
        log.info("【{}】开始启动XianyuClient...", cookieId);

        // 加载Cookie
        if (!loadCookies()) {
            log.error("【{}】加载Cookie失败，无法启动", cookieId);
            running.set(false);
            return;
        }

        // 启动WebSocket连接循环
        CompletableFuture.runAsync(this::connectionLoop, scheduledExecutor);
    }

    /**
     * 停止客户端
     */
    public void stop() {
        if (!running.get()) {
            log.warn("【{}】客户端未运行", cookieId);
            return;
        }

        log.info("【{}】开始停止XianyuClient...", cookieId);
        running.set(false);

        // 取消所有后台任务
        cancelAllBackgroundTasks();

        // 关闭WebSocket连接
        closeWebSocket();

        // 关闭线程池
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdown();
            try {
                if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduledExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduledExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("【{}】XianyuClient已停止", cookieId);
    }

    /**
     * 加载Cookie
     */
    private boolean loadCookies() {
        try {
            log.info("【{}】开始加载Cookie...", cookieId);
            Optional<Cookie> cookieOpt = cookieRepository.findById(cookieId);
            if (cookieOpt.isEmpty()) {
                log.error("【{}】Cookie不存在", cookieId);
                return false;
            }

            Cookie cookie = cookieOpt.get();
            this.cookiesStr = cookie.getValue();
            this.userId = Math.toIntExact(cookie.getUserId());

            if (StrUtil.isBlank(cookiesStr)) {
                log.error("【{}】Cookie值为空", cookieId);
                return false;
            }

            // 解析Cookie
            this.cookies = parseCookies(cookiesStr);
            log.info("【{}】Cookie解析完成，包含字段: {}", cookieId, cookies.keySet());

            // 获取unb字段
            String unb = cookies.get("unb");
            if (StrUtil.isBlank(unb)) {
                log.error("【{}】Cookie中缺少必需的'unb'字段", cookieId);
                return false;
            }

            this.myId = unb;
            this.deviceId = XianyuUtils.generateDeviceId(myId);

            log.info("【{}】用户ID: {}, 设备ID: {}", cookieId, myId, deviceId);
            return true;

        } catch (Exception e) {
            log.error("【{}】加载Cookie失败", cookieId, e);
            return false;
        }
    }


    /**
     * WebSocket连接循环 - 对应Python的main方法中的while True循环
     */
    private void connectionLoop() {
        while (running.get()) {
            try {
                // 检查账号是否启用
                Optional<Cookie> cookieOpt = cookieRepository.findById(cookieId);
                if (cookieOpt.isEmpty() || !Boolean.TRUE.equals(cookieOpt.get().getEnabled())) {
                    log.info("【{}】账号已禁用，停止连接循环", cookieId);
                    break;
                }

                // 更新连接状态
                setConnectionState(ConnectionState.CONNECTING, "准备建立WebSocket连接");
                log.info("【{}】WebSocket目标地址: {}", cookieId, WEBSOCKET_URL);

                // 创建WebSocket连接
                connectWebSocket();

                // 连接成功后，等待连接断开
                // WebSocket会在另一个线程中运行，这里需要阻塞等待
                log.info("【{}】WebSocket连接已建立，等待连接断开...", cookieId);
                while (connected.get() && running.get()) {
                    try {
                        Thread.sleep(1000); // 每秒检查一次连接状态
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
                log.info("【{}】WebSocket连接已断开", cookieId);

            } catch (Exception e) {
                handleConnectionError(e);
            }

            // 重连延迟
            if (running.get()) {
                int retryDelay = calculateRetryDelay(connectionFailures.get());
                log.info("【{}】{}秒后尝试重连...", cookieId, retryDelay);
                try {
                    Thread.sleep(retryDelay * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("【{}】连接循环已退出", cookieId);
    }


    /**
     * 创建WebSocket连接
     */
    private void connectWebSocket() throws Exception {
        WebSocketClient client = new StandardWebSocketClient();
        
        // 准备请求头 - 使用WebSocketHttpHeaders，添加所有必要的headers
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();

        headers.add("Accept-Encoding", "gzip, deflate, br, zstd");
        headers.add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        headers.add("Cache-Control", "no-cache");
        headers.add("Connection", "Upgrade");
        headers.add("Host", "wss-goofish.dingtalk.com");
        headers.add("Origin", "https://www.goofish.com");
        headers.add("Pragma", "no-cache");
        headers.add("Sec-websocket-extensions", "permessage-deflate; client_max_window_bits");
        headers.add("sec-websocket-key", "Q5ejXOphWkfkyDZTTSrU2A==");
        headers.add("sec-websocket-version", "13");
        headers.add("upgrade", "websocket");
        headers.add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0");

        try {
            // doHandshake参数: WebSocketHandler, WebSocketHttpHeaders, URI
            ListenableFuture<WebSocketSession> future =
                client.doHandshake(this, headers, URI.create(WEBSOCKET_URL));
            
            // 等待连接完成
            this.webSocketSession = future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("WebSocket连接被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new Exception("WebSocket连接执行失败: " + e.getMessage(), e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new Exception("WebSocket连接超时", e);
        }
        
        log.info("【{}】WebSocket连接建立成功", cookieId);
    }





    /**
     * WebSocket连接成功后的回调
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("【{}】WebSocket连接已建立，开始初始化...", cookieId);
        this.webSocketSession = session;
        
        // 初始化连接
        try {
            log.info("【{}】准备调用init()方法...", cookieId);
            init(session);
            log.info("【{}】WebSocket初始化完成！", cookieId);

            // 更新连接状态
            setConnectionState(ConnectionState.CONNECTED, "初始化完成，连接就绪");
            connectionFailures.set(0);
            lastSuccessfulConnection.set(System.currentTimeMillis());
            connected.set(true);

            // 启动后台任务
            startBackgroundTasks();
            
            log.info("【{}】✅ WebSocket连接和初始化全部完成", cookieId);

        } catch (Exception e) {
            log.error("【{}】❌ WebSocket初始化失败: {}", cookieId, e.getMessage(), e);
            log.error("【{}】异常类型: {}", cookieId, e.getClass().getName());
            log.error("【{}】异常堆栈:", cookieId, e);
            connected.set(false);
            // 关闭连接，触发重连
            if (session.isOpen()) {
                session.close();
            }
            throw e;
        }
    }


    /**
     * 接收WebSocket消息
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.info("【{}】收到WebSocket消息: {} 字节", cookieId, payload.length());

        try {
            JSONObject messageData = JSON.parseObject(payload);

            // 处理心跳响应
            if (handleHeartbeatResponse(messageData)) {
                return;
            }

            // 处理其他消息（异步处理，避免阻塞）
            CompletableFuture.runAsync(() -> handleMessageWithSemaphore(messageData, session), scheduledExecutor);

        } catch (Exception e) {
            log.error("【{}】处理消息出错", cookieId, e);
        }
    }

    /**
     * WebSocket连接关闭
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.warn("【{}】WebSocket连接已关闭: {}", cookieId, status);
        connected.set(false);
        
        // 重置心跳任务（因为心跳依赖WebSocket连接）
        if (heartbeatTask != null && !heartbeatTask.isDone()) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }

        // 更新连接状态
        setConnectionState(ConnectionState.DISCONNECTED, "连接已关闭");
    }

    /**
     * WebSocket传输错误
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("【{}】WebSocket传输错误", cookieId, exception);
        connected.set(false);
    }

    /**
     * 初始化连接 - 对应Python的init()方法
     */
    private void init(WebSocketSession session) throws Exception {
        log.info("【{}】========== 开始初始化WebSocket连接 ==========", cookieId);

        // 刷新Token
        boolean tokenRefreshAttempted = false;
        long currentTime = System.currentTimeMillis();
        
        log.info("【{}】检查Token状态... currentToken={}, lastRefresh={}", 
            cookieId, currentToken != null ? "存在" : "不存在", lastTokenRefreshTime.get());
        
        if (currentToken == null || (currentTime - lastTokenRefreshTime.get()) >= TOKEN_REFRESH_INTERVAL * 1000L) {
            log.info("【{}】需要刷新token，开始调用refreshToken()...", cookieId);
            tokenRefreshAttempted = true;
            
            try {
                refreshToken();
                log.info("【{}】Token刷新调用完成，currentToken={}", cookieId, currentToken != null ? "已获取" : "未获取");
            } catch (Exception e) {
                log.error("【{}】Token刷新过程出错: {}", cookieId, e.getMessage(), e);
                throw e;
            }
        } else {
            log.info("【{}】Token有效，跳过刷新", cookieId);
        }

        if (currentToken == null) {
            log.error("【{}】❌ 无法获取有效token，初始化失败", cookieId);
            throw new Exception("Token获取失败");
        }
        
        log.info("【{}】✅ Token验证通过: {}", cookieId, currentToken.substring(0, Math.min(20, currentToken.length())) + "...");

        // 发送 /reg 消息
        log.info("【{}】准备发送 /reg 消息...", cookieId);
        JSONObject regMsg = new JSONObject();
        regMsg.put("lwp", "/reg");
        
        JSONObject regHeaders = new JSONObject();
        regHeaders.put("cache-header", "app-key token ua wv");
        regHeaders.put("app-key", APP_KEY);
        regHeaders.put("token", currentToken);
        regHeaders.put("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36 DingTalk(2.1.5) OS(Windows/10) Browser(Chrome/133.0.0.0) DingWeb/2.1.5 IMPaaS DingWeb/2.1.5");
        regHeaders.put("dt", "j");
        regHeaders.put("wv", "im:3,au:3,sy:6");
        regHeaders.put("sync", "0,0;0;0;");
        regHeaders.put("did", deviceId);
        regHeaders.put("mid", XianyuUtils.generateMid());
        regMsg.put("headers", regHeaders);

        try {
            session.sendMessage(new TextMessage(regMsg.toJSONString()));
            log.info("【{}】✅ /reg 消息已发送", cookieId);
        } catch (Exception e) {
            log.error("【{}】❌ 发送 /reg 消息失败: {}", cookieId, e.getMessage(), e);
            throw e;
        }

        // 等待1秒
        log.info("【{}】等待1秒...", cookieId);
        Thread.sleep(1000);


        // 发送 /ackDiff 消息
        log.info("【{}】准备发送 /ackDiff 消息...", cookieId);
        long timestamp = System.currentTimeMillis();
        JSONObject ackMsg = new JSONObject();
        ackMsg.put("lwp", "/r/SyncStatus/ackDiff");
        
        JSONObject ackHeaders = new JSONObject();
        ackHeaders.put("mid", XianyuUtils.generateMid());
        ackMsg.put("headers", ackHeaders);

        JSONArray bodyArray = new JSONArray();
        JSONObject bodyItem = new JSONObject();
        bodyItem.put("pipeline", "sync");
        bodyItem.put("tooLong2Tag", "PNM,1");
        bodyItem.put("channel", "sync");
        bodyItem.put("topic", "sync");
        bodyItem.put("highPts", 0);
        bodyItem.put("pts", timestamp * 1000);

        bodyItem.put("seq", 0);
        bodyItem.put("timestamp", timestamp);
        bodyArray.add(bodyItem);
        ackMsg.put("body", bodyArray);

        try {
            session.sendMessage(new TextMessage(ackMsg.toJSONString()));
            log.info("【{}】✅ /ackDiff 消息已发送", cookieId);
        } catch (Exception e) {
            log.error("【{}】❌ 发送 /ackDiff 消息失败: {}", cookieId, e.getMessage(), e);
            throw e;
        }
        
        log.info("【{}】========== WebSocket初始化完成 ==========", cookieId);
    }


    /**
     * 刷新Token - 对应Python的refresh_token()方法
     */
    private String refreshToken() {
        try {
            log.info("【{}】开始刷新token...", cookieId);
            lastTokenRefreshStatus = "started";

            // 检查是否在消息冷却期内
            long currentTime = System.currentTimeMillis();
            long timeSinceLastMessage = currentTime - lastMessageReceivedTime.get();
            if (lastMessageReceivedTime.get() > 0 && timeSinceLastMessage < MESSAGE_COOLDOWN * 1000L) {
                long remainingTime = MESSAGE_COOLDOWN * 1000L - timeSinceLastMessage;
                log.info("【{}】收到消息后冷却中，放弃本次token刷新，还需等待 {} 秒", 
                    cookieId, remainingTime / 1000);
                lastTokenRefreshStatus = "skipped_cooldown";
                return null;
            }

            // 从数据库重新加载Cookie
            try {
                Optional<Cookie> cookieOpt = cookieRepository.findById(cookieId);
                if (cookieOpt.isPresent()) {
                    String newCookiesStr = cookieOpt.get().getValue();
                    if (!newCookiesStr.equals(this.cookiesStr)) {
                        log.info("【{}】检测到数据库中的cookie已更新，重新加载cookie", cookieId);
                        this.cookiesStr = newCookiesStr;
                        this.cookies = parseCookies(this.cookiesStr);
                        log.warn("【{}】Cookie已从数据库重新加载", cookieId);
                    }
                }
            } catch (Exception e) {
                log.warn("【{}】从数据库重新加载cookie失败，继续使用当前cookie: {}", cookieId, e.getMessage());
            }

            // 生成时间戳
            String timestamp = String.valueOf(System.currentTimeMillis());
            
            // 构建数据
            String dataVal = String.format("{\"appKey\":\"%s\",\"deviceId\":\"%s\"}", APP_CONFIG_KEY, deviceId);

            // 获取token (从_m_h5_tk提取)
            String token = "";
            String mH5Tk = cookies.get("_m_h5_tk");
            if (StrUtil.isNotBlank(mH5Tk) && mH5Tk.contains("_")) {
                token = mH5Tk.split("_")[0];
            }

            // 生成签名
            String sign = XianyuUtils.generateSign(timestamp, token, dataVal);

            // 构建请求
            String url = "https://h5api.m.goofish.com/h5/mtop.taobao.idlemessage.pc.login.token/1.0/";
            Map<String, Object> params = new HashMap<>();
            params.put("jsv", "2.7.2");
            params.put("appKey", APP_KEY);
            params.put("t", timestamp);
            params.put("sign", sign);
            params.put("v", "1.0");
            params.put("type", "originaljson");
            params.put("accountSite", "xianyu");
            params.put("dataType", "json");
            params.put("timeout", "20000");
            params.put("api", "mtop.taobao.idlemessage.pc.login.token");
            params.put("sessionOption", "AutoLoginOnly");
            params.put("spm_cnt", "a21ybx.im.0.0");

            log.info("【{}】========== Token刷新API调用详情 ==========", cookieId);
            log.info("【{}】API端点: {}", cookieId, url);
            log.info("【{}】timestamp: {}", cookieId, timestamp);
            log.info("【{}】sign: {}", cookieId, sign);

            // 发送POST请求
            HttpRequest request = cn.hutool.http.HttpRequest.post(url);
            request.form("data", dataVal);
            params.forEach((k, v) -> request.form(k, v.toString()));
            request.header("cookie", cookiesStr);
            request.header("content-type", "application/x-www-form-urlencoded");
            request.header("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0");
            request.timeout(30000);

            cn.hutool.http.HttpResponse response = request.execute();
            String responseBody = response.body();
            log.info("【{}】API响应: {}", cookieId, responseBody);

            JSONObject resJson = JSON.parseObject(responseBody);
            
            // 检查是否需要滑块验证
            if (needsCaptchaVerification(resJson)) {
                log.warn("【{}】检测到滑块验证要求", cookieId);
                return handleCaptchaAndRetry(resJson);
            }
            
            // 检查响应
            if (resJson.containsKey("ret")) {
                JSONArray retArray = resJson.getJSONArray("ret");
                for (int i = 0; i < retArray.size(); i++) {
                    String ret = retArray.getString(i);
                    if (ret.contains("SUCCESS::调用成功")) {
                        if (resJson.containsKey("data")) {
                            JSONObject data = resJson.getJSONObject("data");
                            if (data.containsKey("accessToken")) {
                                String newToken = data.getString("accessToken");
                                this.currentToken = newToken;
                                this.lastTokenRefreshTime.set(System.currentTimeMillis());
                                this.lastMessageReceivedTime.set(0); // 重置消息接收时间
                                
                                log.warn("【{}】Token刷新成功", cookieId);
                                lastTokenRefreshStatus = "success";
                                return newToken;
                            }
                        }
                    }
                }
            }

            log.error("【{}】Token刷新失败: 响应中未找到有效token", cookieId);
            lastTokenRefreshStatus = "failed";
            return null;

        } catch (Exception e) {
            log.error("【{}】Token刷新异常", cookieId, e);
            lastTokenRefreshStatus = "error";
            return null;
        }
    }

    /**
     * 启动所有后台任务
     */
    private void startBackgroundTasks() {
        log.info("【{}】准备启动后台任务...", cookieId);

        // 启动心跳任务（依赖WebSocket，每次重连都需要重启）
        if (heartbeatTask == null || heartbeatTask.isDone()) {
            log.info("【{}】启动心跳任务...", cookieId);
            heartbeatTask = scheduledExecutor.scheduleWithFixedDelay(
                this::heartbeatLoop, 
                0, 
                HEARTBEAT_INTERVAL, 
                TimeUnit.SECONDS
            );
        }

        // 启动Token刷新任务
        if (tokenRefreshTask == null || tokenRefreshTask.isDone()) {
            log.info("【{}】启动Token刷新任务...", cookieId);
            tokenRefreshTask = scheduledExecutor.scheduleWithFixedDelay(
                this::tokenRefreshLoop,
                60,
                60,
                TimeUnit.SECONDS
            );
        }

        // 启动清理任务
        if (cleanupTask == null || cleanupTask.isDone()) {
            log.info("【{}】启动暂停记录清理任务...", cookieId);
            cleanupTask = scheduledExecutor.scheduleWithFixedDelay(
                this::pauseCleanupLoop,
                CLEANUP_INTERVAL,
                CLEANUP_INTERVAL,
                TimeUnit.SECONDS
            );
        }

        // 启动Cookie刷新任务
        if (cookieRefreshTask == null || cookieRefreshTask.isDone()) {
            log.info("【{}】启动Cookie刷新任务...", cookieId);
            cookieRefreshTask = scheduledExecutor.scheduleWithFixedDelay(
                this::cookieRefreshLoop,
                COOKIE_REFRESH_INTERVAL,
                COOKIE_REFRESH_INTERVAL,
                TimeUnit.SECONDS
            );
        }

        log.info("【{}】✅ 所有后台任务已启动", cookieId);
    }

    /**
     * 心跳循环 - 对应Python的heartbeat_loop()方法
     */
    private void heartbeatLoop() {
        if (!connected.get() || webSocketSession == null || !webSocketSession.isOpen()) {
            return;
        }

        try {
            sendHeartbeat();
        } catch (Exception e) {
            log.error("【{}】心跳发送失败: {}", cookieId, e.getMessage());
        }
    }

    /**
     * 发送心跳 - 对应Python的send_heartbeat()方法
     */
    private void sendHeartbeat() throws Exception {
        if (webSocketSession == null || !webSocketSession.isOpen()) {
            throw new Exception("WebSocket连接已关闭，无法发送心跳");
        }

        JSONObject msg = new JSONObject();
        msg.put("lwp", "/!");
        
        JSONObject headers = new JSONObject();
        headers.put("mid", XianyuUtils.generateMid());
        msg.put("headers", headers);

        webSocketSession.sendMessage(new TextMessage(msg.toJSONString()));
        lastHeartbeatTime.set(System.currentTimeMillis());
        log.warn("【{}】心跳包已发送", cookieId);
    }

    /**
     * 处理心跳响应 - 对应Python的handle_heartbeat_response()方法
     */
    private boolean handleHeartbeatResponse(JSONObject messageData) {
        try {
            if (messageData.getIntValue("code") == 200) {
                lastHeartbeatResponse.set(System.currentTimeMillis());
                log.debug("【{}】收到心跳响应", cookieId);
                return true;
            }
        } catch (Exception e) {
            // 忽略
        }
        return false;
    }

    /**
     * Token刷新循环 - 对应Python的token_refresh_loop()方法
     */
    private void tokenRefreshLoop() {
        try {
            // 检查账号是否启用
            Optional<Cookie> cookieOpt = cookieRepository.findById(cookieId);
            if (cookieOpt.isEmpty() || !Boolean.TRUE.equals(cookieOpt.get().getEnabled())) {
                log.info("【{}】账号已禁用，停止Token刷新循环", cookieId);
                return;
            }

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastTokenRefreshTime.get() >= TOKEN_REFRESH_INTERVAL * 1000L) {
                log.info("【{}】Token即将过期，准备刷新...", cookieId);
                String newToken = refreshToken();
                if (newToken != null) {
                    log.info("【{}】Token刷新成功，将关闭WebSocket以使用新Token重连", cookieId);
                    // Token刷新成功后，关闭WebSocket连接，让它用新Token重新连接
                    closeWebSocket();
                }
            }

        } catch (Exception e) {
            log.error("【{}】Token刷新循环出错", cookieId, e);
        }
    }

    /**
     * 暂停清理循环 - 对应Python的pause_cleanup_loop()方法
     */
    private void pauseCleanupLoop() {
        try {
            // 清理过期的通知记录
            cleanupExpiredMap(lastNotificationTime, NOTIFICATION_COOLDOWN * 1000L);
            
            // 清理过期的发货记录
            cleanupExpiredMap(lastDeliveryTime, DELIVERY_COOLDOWN * 1000L);
            
            // 清理过期的订单确认记录
            cleanupExpiredMap(confirmedOrders, ORDER_CONFIRM_COOLDOWN * 1000L);
            
            // 清理过期的消息ID
            cleanupExpiredMap(processedMessageIds, MESSAGE_EXPIRE_TIME * 1000L);

        } catch (Exception e) {
            log.error("【{}】清理循环出错", cookieId, e);
        }
    }

    /**
     * Cookie刷新循环 - 对应Python的cookie_refresh_loop()方法
     */
    private void cookieRefreshLoop() {
        try {
            // 检查账号是否启用
            Optional<Cookie> cookieOpt = cookieRepository.findById(cookieId);
            if (cookieOpt.isEmpty() || !Boolean.TRUE.equals(cookieOpt.get().getEnabled())) {
                log.info("【{}】账号已禁用，停止Cookie刷新循环", cookieId);
                return;
            }

            long currentTime = System.currentTimeMillis();
            
            // 检查是否在消息接收后的冷却时间内
            long timeSinceLastMessage = currentTime - lastMessageReceivedTime.get();
            if (lastMessageReceivedTime.get() > 0 && timeSinceLastMessage < MESSAGE_COOLDOWN * 1000L) {
                log.info("【{}】收到消息后冷却中，跳过本次Cookie刷新", cookieId);
                return;
            }

            // 从数据库重新加载Cookie
            if (currentTime - lastCookieRefreshTime.get() >= COOKIE_REFRESH_INTERVAL * 1000L) {
                log.info("【{}】开始Cookie刷新...", cookieId);
                if (loadCookies()) {
                    lastCookieRefreshTime.set(currentTime);
                    log.info("【{}】Cookie刷新成功", cookieId);
                }
            }

        } catch (Exception e) {
            log.error("【{}】Cookie刷新循环出错", cookieId, e);
        }
    }

    /**
     * 带信号量的消息处理
     */
    private void handleMessageWithSemaphore(JSONObject messageData, WebSocketSession session) {
        try {
            messageSemaphore.acquire();
            activeMessageTasks.incrementAndGet();
            try {
                handleMessage(messageData, session);
            } finally {
                activeMessageTasks.decrementAndGet();
                messageSemaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 处理消息 - 对应Python的handle_message()方法（简化版，核心逻辑已实现）
     */
    private void handleMessage(JSONObject messageData, WebSocketSession session) {
        try {
            // 检查账号是否启用
            Optional<Cookie> cookieOpt = cookieRepository.findById(cookieId);
            if (cookieOpt.isEmpty() || !Boolean.TRUE.equals(cookieOpt.get().getEnabled())) {
                log.warn("【{}】账号已禁用，跳过消息处理", cookieId);
                return;
            }

            // 发送确认消息（ACK）
            try {
                sendAck(messageData, session);
            } catch (Exception e) {
                log.warn("【{}】发送ACK失败", cookieId, e);
            }

            // 检查是否为同步包消息
            if (!isSyncPackage(messageData)) {
                log.debug("【{}】非同步包消息，跳过处理", cookieId);
                return;
            }

            // 记录收到消息的时间
            lastMessageReceivedTime.set(System.currentTimeMillis());
            log.warn("【{}】收到消息，更新消息接收时间标识", cookieId);

            // 解密并处理消息内容
            try {
                JSONObject syncData = messageData.getJSONObject("body")
                    .getJSONObject("syncPushPackage")
                    .getJSONArray("data")
                    .getJSONObject(0);

                if (!syncData.containsKey("data")) {
                    log.warn("【{}】同步包中无data字段", cookieId);
                    return;
                }

                String data = syncData.getString("data");
                String decryptedData = XianyuUtils.decrypt(data);
                JSONObject message = JSON.parseObject(decryptedData);

                // 调用ReplyService处理消息（自动回复等业务逻辑）
                replyService.processMessage(cookieId, message, session);

            } catch (Exception e) {
                log.error("【{}】消息解密或处理失败", cookieId, e);
            }

        } catch (Exception e) {
            log.error("【{}】处理消息出错", cookieId, e);
        }
    }

    /**
     * 发送ACK确认消息
     */
    private void sendAck(JSONObject messageData, WebSocketSession session) throws Exception {
        if (!messageData.containsKey("headers")) {
            return;
        }

        JSONObject headers = messageData.getJSONObject("headers");
        JSONObject ack = new JSONObject();
        ack.put("code", 200);
        
        JSONObject ackHeaders = new JSONObject();
        ackHeaders.put("mid", headers.containsKey("mid") ? headers.getString("mid") : XianyuUtils.generateMid());
        ackHeaders.put("sid", headers.containsKey("sid") ? headers.getString("sid") : "");
        
        if (headers.containsKey("app-key")) {
            ackHeaders.put("app-key", headers.getString("app-key"));
        }
        if (headers.containsKey("ua")) {
            ackHeaders.put("ua", headers.getString("ua"));
        }
        if (headers.containsKey("dt")) {
            ackHeaders.put("dt", headers.getString("dt"));
        }
        
        ack.put("headers", ackHeaders);
        session.sendMessage(new TextMessage(ack.toJSONString()));
    }

    /**
     * 判断是否为同步包消息
     */
    private boolean isSyncPackage(JSONObject messageData) {
        try {
            return messageData.containsKey("body") 
                && messageData.getJSONObject("body").containsKey("syncPushPackage")
                && messageData.getJSONObject("body").getJSONObject("syncPushPackage").containsKey("data")
                && messageData.getJSONObject("body").getJSONObject("syncPushPackage").getJSONArray("data").size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析Cookie字符串
     */
    private Map<String, String> parseCookies(String cookiesStr) {
        Map<String, String> cookieMap = new HashMap<>();
        if (StrUtil.isBlank(cookiesStr)) {
            return cookieMap;
        }

        String[] parts = cookiesStr.split("; ");
        for (String part : parts) {
            if (part.contains("=")) {
                String[] kv = part.split("=", 2);
                cookieMap.put(kv[0].trim(), kv[1].trim());
            }
        }
        return cookieMap;
    }

    /**
     * 设置连接状态
     */
    private void setConnectionState(ConnectionState newState, String reason) {
        if (this.connectionState != newState) {
            ConnectionState oldState = this.connectionState;
            this.connectionState = newState;
            this.lastStateChangeTime.set(System.currentTimeMillis());

            String stateMsg = String.format("【%s】连接状态: %s → %s", 
                cookieId, oldState.getValue(), newState.getValue());
            if (StrUtil.isNotBlank(reason)) {
                stateMsg += " (" + reason + ")";
            }

            switch (newState) {
                case FAILED:
                    log.error(stateMsg);
                    break;
                case RECONNECTING:
                    log.warn(stateMsg);
                    break;
                case CONNECTED:
                    log.info(stateMsg);
                    break;
                default:
                    log.info(stateMsg);
            }
        }
    }

    /**
     * 处理连接错误
     */
    private void handleConnectionError(Exception e) {
        connectionFailures.incrementAndGet();
        setConnectionState(ConnectionState.RECONNECTING, String.format("第%d次失败", connectionFailures.get()));
        log.error("【{}】WebSocket连接异常 ({}/{}): {}", 
            cookieId, connectionFailures.get(), MAX_CONNECTION_FAILURES, e.getMessage());

        if (connectionFailures.get() >= MAX_CONNECTION_FAILURES) {
            log.error("【{}】连接失败次数过多，停止重连", cookieId);
            setConnectionState(ConnectionState.FAILED, "连接失败次数过多");
            running.set(false);
        }
    }

    /**
     * 计算重试延迟
     */
    private int calculateRetryDelay(int failures) {
        if (failures <= 1) {
            return 3;
        } else if (failures <= 3) {
            return 5;
        } else {
            return Math.min(10 * failures, 60);
        }
    }

    /**
     * 关闭WebSocket连接
     */
    private void closeWebSocket() {
        if (webSocketSession != null && webSocketSession.isOpen()) {
            try {
                webSocketSession.close();
                log.info("【{}】WebSocket连接已关闭", cookieId);
            } catch (Exception e) {
                log.warn("【{}】关闭WebSocket时出错: {}", cookieId, e.getMessage());
            }
        }
        webSocketSession = null;
        connected.set(false);
    }

    /**
     * 取消所有后台任务
     */
    private void cancelAllBackgroundTasks() {
        log.info("【{}】开始取消所有后台任务...", cookieId);

        if (heartbeatTask != null && !heartbeatTask.isDone()) {
            heartbeatTask.cancel(true);
            log.info("【{}】心跳任务已取消", cookieId);
        }

        if (tokenRefreshTask != null && !tokenRefreshTask.isDone()) {
            tokenRefreshTask.cancel(true);
            log.info("【{}】Token刷新任务已取消", cookieId);
        }

        if (cleanupTask != null && !cleanupTask.isDone()) {
            cleanupTask.cancel(true);
            log.info("【{}】清理任务已取消", cookieId);
        }

        if (cookieRefreshTask != null && !cookieRefreshTask.isDone()) {
            cookieRefreshTask.cancel(true);
            log.info("【{}】Cookie刷新任务已取消", cookieId);
        }

        log.info("【{}】所有后台任务已取消", cookieId);
    }

    /**
     * 清理过期的Map条目
     */
    private void cleanupExpiredMap(Map<String, Long> map, long maxAge) {
        long currentTime = System.currentTimeMillis();
        map.entrySet().removeIf(entry -> (currentTime - entry.getValue()) > maxAge);
    }

    // Getters
    public String getCookieId() {
        return cookieId;
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    public ConnectionState getConnectionState() {
        return connectionState;
    }

    /**
     * 发送消息 - 对应Python的send_msg()方法
     * 
     * @param chatId 会话ID
     * @param toUserId 接收用户ID
     * @param messageText 消息内容
     * @throws Exception 发送失败时抛出异常
     */
    public void sendMessage(String chatId, String toUserId, String messageText) throws Exception {
        if (webSocketSession == null || !webSocketSession.isOpen()) {
            throw new Exception("WebSocket未连接，无法发送消息");
        }

        try {
            // 构建消息内容
            JSONObject text = new JSONObject();
            text.put("contentType", 1);
            JSONObject textObj = new JSONObject();
            textObj.put("text", messageText);
            text.put("text", textObj);

            // Base64编码
            String textJson = text.toJSONString();
            String textBase64 = java.util.Base64.getEncoder().encodeToString(textJson.getBytes("UTF-8"));

            // 构建消息体
            JSONObject msg = new JSONObject();
            msg.put("lwp", "/r/MessageSend/sendByReceiverScope");

            JSONObject headers = new JSONObject();
            headers.put("mid", XianyuUtils.generateMid());
            msg.put("headers", headers);

            // 构建body数组
            JSONArray bodyArray = new JSONArray();

            // 第一个body元素 - 消息内容
            JSONObject bodyItem1 = new JSONObject();
            bodyItem1.put("uuid", XianyuUtils.generateUuid());
            bodyItem1.put("cid", chatId + "@goofish");
            bodyItem1.put("conversationType", 1);

            JSONObject content = new JSONObject();
            content.put("contentType", 101);
            JSONObject custom = new JSONObject();
            custom.put("type", 1);
            custom.put("data", textBase64);
            content.put("custom", custom);
            bodyItem1.put("content", content);

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

            bodyArray.add(bodyItem1);

            // 第二个body元素 - 接收者列表
            JSONObject bodyItem2 = new JSONObject();
            JSONArray actualReceivers = new JSONArray();
            actualReceivers.add(toUserId + "@goofish");
            actualReceivers.add(myId + "@goofish");
            bodyItem2.put("actualReceivers", actualReceivers);

            bodyArray.add(bodyItem2);

            msg.put("body", bodyArray);

            // 发送消息
            webSocketSession.sendMessage(new TextMessage(msg.toJSONString()));
            log.info("【{}】消息已发送 - chatId: {}, toUserId: {}, message: {}", 
                cookieId, chatId, toUserId, messageText);

        } catch (Exception e) {
            log.error("【{}】发送消息失败", cookieId, e);
            throw new Exception("发送消息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建聊天会话 - 对应Python的create_chat()方法
     * 
     * @param toUserId 目标用户ID
     * @param itemId 商品ID
     * @return 会话ID
     * @throws Exception 创建失败时抛出异常
     */
    public String createChat(String toUserId, String itemId) throws Exception {
        if (webSocketSession == null || !webSocketSession.isOpen()) {
            throw new Exception("WebSocket未连接，无法创建会话");
        }

        try {
            JSONObject msg = new JSONObject();
            msg.put("lwp", "/r/SingleChatConversation/create");

            JSONObject headers = new JSONObject();
            headers.put("mid", XianyuUtils.generateMid());
            msg.put("headers", headers);

            JSONArray bodyArray = new JSONArray();
            JSONObject bodyItem = new JSONObject();
            bodyItem.put("pairFirst", toUserId + "@goofish");
            bodyItem.put("pairSecond", myId + "@goofish");
            bodyItem.put("bizType", "1");

            if (itemId != null) {
                JSONObject extension = new JSONObject();
                extension.put("itemId", itemId);
                bodyItem.put("extension", extension);
            }

            JSONObject ctx = new JSONObject();
            ctx.put("appVersion", "1.0");
            ctx.put("platform", "web");
            bodyItem.put("ctx", ctx);

            bodyArray.add(bodyItem);
            msg.put("body", bodyArray);

            webSocketSession.sendMessage(new TextMessage(msg.toJSONString()));
            log.info("【{}】创建会话请求已发送 - toUserId: {}, itemId: {}", cookieId, toUserId, itemId);

            // 注意：会话ID需要从响应消息中提取，这里返回null
            // 实际使用中需要等待WebSocket响应并提取cid
            return null;

        } catch (Exception e) {
            log.error("【{}】创建会话失败", cookieId, e);
            throw new Exception("创建会话失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查是否需要滑块验证
     */
    private boolean needsCaptchaVerification(JSONObject resJson) {
        try {
            JSONArray ret = resJson.getJSONArray("ret");
            if (ret == null || ret.isEmpty()) {
                return false;
            }
            
            String errorMsg = ret.getString(0);
            
            // 检查是否包含滑块验证关键词
            return errorMsg.contains("FAIL_SYS_USER_VALIDATE") ||
                   errorMsg.contains("RGV587_ERROR") ||
                   errorMsg.contains("哎哟喂,被挤爆啦") ||
                   errorMsg.contains("哎哟喂，被挤爆啦") ||
                   errorMsg.contains("captcha") ||
                   errorMsg.contains("punish");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 处理滑块验证并重试Token刷新
     */
    private String handleCaptchaAndRetry(JSONObject resJson) {
        try {
            // 获取验证URL
            String verificationUrl = null;
            if (resJson.containsKey("data")) {
                JSONObject data = resJson.getJSONObject("data");
                if (data.containsKey("url")) {
                    verificationUrl = data.getString("url");
                }
            }
            
            if (verificationUrl == null) {
                log.warn("【{}】未找到验证URL，无法进行滑块验证", cookieId);
                return null;
            }
            
            log.info("【{}】开始滑块验证处理...", cookieId);
            log.info("【{}】验证URL: {}", cookieId, verificationUrl);
            
            // 调用滑块验证处理器
            com.xianyu.autoreply.service.captcha.model.CaptchaResult result = 
                captchaHandler.handleCaptcha(verificationUrl, cookieId);
            
            if (result.isSuccess()) {
                log.info("【{}】滑块验证成功！耗时: {}ms", cookieId, result.getDuration());
                
                // 更新cookies
                Map<String, String> newCookies = result.getCookies();
                if (newCookies != null && !newCookies.isEmpty()) {
                    // 合并cookies
                    for (Map.Entry<String, String> entry : newCookies.entrySet()) {
                        this.cookies.put(entry.getKey(), entry.getValue());
                        log.info("【{}】更新cookie: {} = {}", cookieId, entry.getKey(), entry.getValue());
                    }
                    
                    // 更新cookies字符串
                    updateCookiesString();
                    
                    // 保存到数据库
                    saveCookiesToDatabase();
                }
                
                // 重新尝试刷新Token
                log.info("【{}】滑块验证成功，重新尝试刷新Token...", cookieId);
                return refreshToken();
            } else {
                log.error("【{}】滑块验证失败: {}", cookieId, result.getMessage());
                return null;
            }
            
        } catch (Exception e) {
            log.error("【{}】滑块验证处理异常", cookieId, e);
            return null;
        }
    }
    
    /**
     * 更新cookies字符串
     */
    private void updateCookiesString() {
        this.cookiesStr = this.cookies.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(java.util.stream.Collectors.joining("; "));
    }
    
    /**
     * 保存cookies到数据库
     */
    private void saveCookiesToDatabase() {
        try {
            Optional<Cookie> cookieOpt = cookieRepository.findById(cookieId);
            if (cookieOpt.isPresent()) {
                Cookie cookie = cookieOpt.get();
                cookie.setValue(this.cookiesStr);
                cookieRepository.save(cookie);
                log.info("【{}】Cookies已更新到数据库", cookieId);
            }
        } catch (Exception e) {
            log.error("【{}】保存cookies到数据库失败", cookieId, e);
        }
    }
}


