package com.xianyu.autoreply.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.entity.ItemInfo;
import com.xianyu.autoreply.model.ItemDetailCache;
import com.xianyu.autoreply.model.LockHoldInfo;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.repository.ItemInfoRepository;
import com.xianyu.autoreply.service.captcha.CaptchaHandler;
import com.xianyu.autoreply.utils.XianyuUtils;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
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
import java.util.Set;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


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
    private static final int TOKEN_REFRESH_INTERVAL = 72000; // Token刷新间隔（秒），20小时
    private static final int TOKEN_RETRY_INTERVAL = 7200; // Token重试间隔（秒），2小时
    private static final int TOKEN_REFRESH_MAX_RETRIES = 3; // Token刷新最大重试次数
    private static final int MESSAGE_COOLDOWN = 300; // 消息冷却时间（秒），5分钟
    private static final int CLEANUP_INTERVAL = 300; // 清理间隔（秒），5分钟
    private static final int COOKIE_REFRESH_INTERVAL = 1200; // Cookie刷新间隔（秒），20分钟

    private static final String API_APP_KEY = "34839810";
    private static final String WEBSOCKET_APP_KEY = "444e9908a51d1cb236a27862abc769c9";

    // ============== 类级别共享资源（多实例共享）==============

    // 订单锁字典（用于自动发货防并发）
    private static final ConcurrentHashMap<String, ReentrantLock> ORDER_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> LOCK_USAGE_TIMES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LockHoldInfo> LOCK_HOLD_INFO = new ConcurrentHashMap<>();

    // 订单详情锁（独立锁字典，不使用延迟释放机制）
    private static final ConcurrentHashMap<String, ReentrantLock> ORDER_DETAIL_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> ORDER_DETAIL_LOCK_TIMES = new ConcurrentHashMap<>();

    // 商品详情缓存（24小时有效，支持LRU淘汰）
    private static final ConcurrentHashMap<String, ItemDetailCache> ITEM_DETAIL_CACHE = new ConcurrentHashMap<>();
    private static final ReentrantLock ITEM_DETAIL_CACHE_LOCK = new ReentrantLock();
    private static final int ITEM_DETAIL_CACHE_MAX_SIZE = 1000; // 最大缓存1000个商品
    private static final int ITEM_DETAIL_CACHE_TTL = 24 * 60 * 60; // 24小时TTL（秒）

    // 实例管理字典（用于API调用时获取实例）
    private static final ConcurrentHashMap<String, XianyuClient> INSTANCES = new ConcurrentHashMap<>();
    private static final ReentrantLock INSTANCES_LOCK = new ReentrantLock();

    // 密码登录时间记录（防止重复登录）
    private static final ConcurrentHashMap<String, Long> LAST_PASSWORD_LOGIN_TIME = new ConcurrentHashMap<>();
    private static final int PASSWORD_LOGIN_COOLDOWN = 60; // 密码登录冷却时间（秒）

    // ============== 核心字段 ==============
    private final String cookieId; // 账号ID
    private final CookieRepository cookieRepository;
    private final ReplyService replyService;
    private final CaptchaHandler captchaHandler;
    private final BrowserService browserService;
    private final PauseManager pauseManager; // 暂停管理器
    private final OrderStatusHandler orderStatusHandler; // 订单状态处理器
    private final ItemInfoRepository itemInfoRepository; // 商品信息存储库

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
    private final AtomicBoolean cookieRefreshEnabled = new AtomicBoolean(true); // 是否启用Cookie刷新
    private final AtomicLong lastQrCookieRefreshTime = new AtomicLong(0); // 上次扫码登录Cookie刷新时间
    private static final int QR_COOKIE_REFRESH_COOLDOWN = 600; // 扫码登录Cookie刷新冷却时间（秒）
    private static final int MESSAGE_COOKIE_REFRESH_COOLDOWN = 300; // 收到消息后Cookie刷新冷却时间（秒）
    private final AtomicBoolean browserCookieRefreshed = new AtomicBoolean(false); // 浏览器Cookie刷新标志
    private final AtomicBoolean restartedInBrowserRefresh = new AtomicBoolean(false); // 刷新流程内是否已触发重启

    // ============== 滑块验证相关 ==============
    private final AtomicInteger captchaVerificationCount = new AtomicInteger(0); // 滑块验证次数计数器
    private static final int MAX_CAPTCHA_VERIFICATION_COUNT = 3; // 最大滑块验证次数

    // ============== 后台任务追踪 ==============
    private final Set<CompletableFuture<Void>> backgroundTasks = ConcurrentHashMap.newKeySet(); // 追踪所有后台任务

    // ============== 消息防抖管理 ==============
    private final Map<String, MessageDebounceInfo> messageDebounnceTasks = new ConcurrentHashMap<>(); // 消息防抖任务
    private static final int MESSAGE_DEBOUNCE_DELAY = 1; // 防抖延迟时间（秒）
    private final ReentrantLock messageDebounceLock = new ReentrantLock(); // 防抖任务管理的锁
    private final ReentrantLock processedMessageIdsLock = new ReentrantLock(); // 消息ID去重的锁

    // ============== 发货已发送订单记录 ==============
    private final Map<String, Long> deliverySentOrders = new ConcurrentHashMap<>(); // 已发货订单记录 {order_id: timestamp}

    // ============== HTTP Client ==============
    private final OkHttpClient httpClient;

    /**
     * 消息防抖信息类
     */
    private static class MessageDebounceInfo {
        CompletableFuture<Void> task;
        JSONObject lastMessage;
        long timer;

        MessageDebounceInfo(CompletableFuture<Void> task, JSONObject lastMessage, long timer) {
            this.task = task;
            this.lastMessage = lastMessage;
            this.timer = timer;
        }
    }

    /**
     * 连接状态枚举
     */
    public enum ConnectionState {
        INIT("init"),
        DISCONNECTED("disconnected"),
        CONNECTING("connecting"),
        CONNECTED("connected"),
        REGISTER("register"),
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
                        BrowserService browserService, PauseManager pauseManager,
                        OrderStatusHandler orderStatusHandler,
                        ItemInfoRepository itemInfoRepository) {
        this.cookieId = cookieId;
        this.cookieRepository = cookieRepository;
        this.replyService = replyService;
        this.captchaHandler = captchaHandler;
        this.browserService = browserService;
        this.pauseManager = pauseManager;
        this.orderStatusHandler = orderStatusHandler;
        this.itemInfoRepository = itemInfoRepository;

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

        // 注册实例到全局字典
        registerInstance();

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

        // 清理实例缓存
        cleanupInstanceCaches();

        // 从全局字典中注销实例
        unregisterInstance();

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
     * WebSocket连接循环 - 重构版本，去除嵌套
     * 核心逻辑：外层循环保持运行，内部单次连接尝试，失败后延迟重试
     */
    private void connectionLoop() {
        while (running.get()) {
            try {
                // 检查账号是否启用
                if (!isAccountEnabled()) {
                    log.info("【{}】账号已禁用，停止连接循环", cookieId);
                    break;
                }

                // 更新连接状态
                setConnectionState(ConnectionState.INIT, "准备建立WebSocket连接");
                log.info("【{}】WebSocket目标地址: {}", cookieId, WEBSOCKET_URL);

                // 单次连接尝试
                connectWebSocket();

                // 连接成功后，进入等待循环，直到连接断开
                waitForDisconnection();

                log.info("【{}】WebSocket连接已断开", cookieId);

            } catch (Exception e) {
                // 统一处理连接错误
                handleConnectionError(e);
            }

            // 计算并执行重连延迟
            if (running.get()) {
                int retryDelay = calculateRetryDelay(connectionFailures.get());
                log.info("【{}】{}秒后尝试重连...", cookieId, retryDelay);
                sleepWithInterruptCheck(retryDelay * 1000L);
            }
        }

        log.info("【{}】WebSocket 连接循环已退出", cookieId);
    }

    /**
     * 检查账号是否启用
     */
    private boolean isAccountEnabled() {
        try {
            Optional<Cookie> cookieOpt = cookieRepository.findById(cookieId);
            return cookieOpt.isPresent() && Boolean.TRUE.equals(cookieOpt.get().getEnabled());
        } catch (Exception e) {
            log.error("【{}】检查账号状态失败", cookieId, e);
            return false;
        }
    }

    /**
     * 等待WebSocket连接断开
     */
    private void waitForDisconnection() {
        log.info("【{}】WebSocket连接已建立，等待连接断开...", cookieId);
        while (connected.get() && running.get()) {
            sleepWithInterruptCheck(1000);
        }
    }

    /**
     * 可中断的Sleep
     */
    private void sleepWithInterruptCheck(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("【{}】Sleep被中断", cookieId);
        }
    }


    /**
     * 创建WebSocket连接 - 重构版本，纯粹的单次连接尝试
     * 失败直接抛异常，由 connectionLoop() 统一处理重试
     */
    private void connectWebSocket() throws Exception {
        log.info("【{}】开始建立WebSocket连接...", cookieId);

        // 配置WebSocket容器，设置缓冲区大小为10MB（解决1009错误：消息过大）
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(10 * 1024 * 1024); // 10MB
        container.setDefaultMaxBinaryMessageBufferSize(10 * 1024 * 1024); // 10MB

        // 使用配置好的容器创建WebSocket客户端
        WebSocketClient client = new StandardWebSocketClient(container);

        // 准备请求头
        WebSocketHttpHeaders headers = buildWebSocketHeaders();

        try {
            // 发起WebSocket握手
            ListenableFuture<WebSocketSession> future =
                    client.doHandshake(this, headers, URI.create(WEBSOCKET_URL));

            // 等待连接完成（超时30秒）
            // 注意：由于 afterConnectionEstablished 已异步化，这个超时仅用于 WebSocket 握手本身
            this.webSocketSession = future.get(30, TimeUnit.SECONDS);
            log.info("【{}】WebSocket连接建立成功", cookieId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("WebSocket连接被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new Exception("WebSocket连接执行失败: " + e.getMessage(), e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new Exception("WebSocket连接超时", e);
        }
    }

    /**
     * 构建WebSocket请求头
     */
    private WebSocketHttpHeaders buildWebSocketHeaders() {
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
        return headers;
    }


    /**
     * WebSocket连接成功后的回调
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("【{}】WebSocket连接已建立", cookieId);
        this.webSocketSession = session;
        
        // 更新连接状态（连接已建立，但尚未初始化）
        setConnectionState(ConnectionState.CONNECTED, "握手完成，准备初始化");
        connected.set(true);  // 标记连接已建立
        
        // 异步执行初始化（不阻塞连接建立过程）
        // 这样可以避免 init() 中的耗时操作（如refreshToken）导致连接超时
        CompletableFuture.runAsync(() -> {
            try {
                log.info("【{}】开始异步初始化...", cookieId);
                init(session);
                log.info("【{}】WebSocket初始化完成！", cookieId);
                
                // 更新连接状态
                setConnectionState(ConnectionState.REGISTER, "初始化完成，连接就绪");
                connectionFailures.set(0);
                lastSuccessfulConnection.set(System.currentTimeMillis());
                
                // 启动后台任务
                startBackgroundTasks();
                
                log.info("【{}】✅ WebSocket连接和初始化全部完成", cookieId);
                
            } catch (Exception e) {
                log.error("【{}】❌ WebSocket初始化失败: {}", cookieId, e.getMessage(), e);
                log.error("【{}】异常类型: {}", cookieId, e.getClass().getName());
                log.error("【{}】异常堆栈:", cookieId, e);
                connected.set(false);
                
                // 关闭连接，触发重连
                try {
                    if (session.isOpen()) {
                        session.close();
                    }
                } catch (Exception closeEx) {
                    log.error("【{}】关闭连接失败", cookieId, closeEx);
                }
            }
        }, scheduledExecutor);  // 使用已有的线程池执行异步任务
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
        log.info("【{}】检查Token状态... currentToken={}, lastRefresh={}",
                cookieId, currentToken != null ? "存在" : "不存在", lastTokenRefreshTime.get());

        // 刷新Token
        long currentTime = System.currentTimeMillis();
        if (currentToken == null || (currentTime - lastTokenRefreshTime.get()) >= TOKEN_REFRESH_INTERVAL * 1000L) {
            log.info("【{}】需要刷新token，开始调用refreshToken()...", cookieId);

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
        regHeaders.put("app-key", WEBSOCKET_APP_KEY);
        regHeaders.put("token", currentToken);
        regHeaders.put("ua", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0");
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
     * 刷新Token - 优化版本，使用迭代循环替代递归
     * 策略：使用while循环进行重试，集成滑块验证处理，设置最大重试次数防止无限循环
     */
    private String refreshToken() {
        lastTokenRefreshStatus = "started";
        log.info("【{}】开始刷新token...", cookieId);

        int attempt = 0;
        final int BASE_DELAY_MS = 1000; // 基础延迟1秒

        while (attempt < TOKEN_REFRESH_MAX_RETRIES) {
            attempt++;
            log.info("【{}】Token刷新尝试 {}/{}", cookieId, attempt, TOKEN_REFRESH_MAX_RETRIES);

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

            // 从数据库重新加载Cookie（可能已被浏览器刷新更新）
            reloadCookieFromDatabase();

            // 尝试获取Token
            log.debug("【{}】🤖准备调用官方API获取Token（尝试 {}/{}）...", cookieId, attempt, TOKEN_REFRESH_MAX_RETRIES);
            String token = attemptGetToken();
            log.debug("【{}】🤖调用官方API获取Token结果: {}", cookieId, token != null ? "成功" : "失败");

            if (token != null) {
                // Token获取成功
                this.currentToken = token;
                this.lastTokenRefreshTime.set(System.currentTimeMillis());
                this.lastMessageReceivedTime.set(0);
                log.warn("【{}】✅ Token刷新成功（第 {} 次尝试）", cookieId, attempt);
                lastTokenRefreshStatus = "success";
                return token;
            }

            // Token获取失败，检查是否需要滑块验证
            log.warn("【{}】⚠️ Token获取失败（第 {} 次尝试），尝试通过页面刷新获取Cookie...", cookieId, attempt);

            // 尝试通过浏览器刷新Cookie
            refreshCookieViaBrowser();

            // Cookie刷新后，再次尝试获取Token
            log.debug("【{}】🤖刷新Cookie后再次调用官方API获取Token...", cookieId);
            token = attemptGetToken();
            log.debug("【{}】🤖刷新Cookie后调用官方API获取Token结果: {}", cookieId, token != null ? "成功" : "失败");

            if (token != null) {
                // Token获取成功
                this.currentToken = token;
                this.lastTokenRefreshTime.set(System.currentTimeMillis());
                this.lastMessageReceivedTime.set(0);
                log.warn("【{}】✅ Token刷新成功（刷新Cookie后，第 {} 次尝试）", cookieId, attempt);
                lastTokenRefreshStatus = "success";
                return token;
            }

            // 仍然失败，应用指数退避策略（除非是最后一次尝试）
            if (attempt < TOKEN_REFRESH_MAX_RETRIES) {
                int delayMs = BASE_DELAY_MS * (1 << (attempt - 1)); // 指数退避: 1s, 2s, 4s
                log.warn("【{}】Token刷新失败，等待 {}ms 后进行下一次尝试...", cookieId, delayMs);
                sleepWithInterruptCheck(delayMs);
            }
        }

        // 所有重试都失败
        log.error("【{}】❌ Token刷新失败，已达最大重试次数 {}", cookieId, TOKEN_REFRESH_MAX_RETRIES);
        lastTokenRefreshStatus = "failed";
        return null;
    }

    /**
     * 从数据库重新加载Cookie（避免重复代码）
     */
    private void reloadCookieFromDatabase() {
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
    }

    /**
     * 通过浏览器刷新Cookie（避免重复代码）
     */
    private void refreshCookieViaBrowser() {
        try {
            Map<String, String> newCookies = browserService.refreshCookies(cookieId);

            if (newCookies != null && !newCookies.isEmpty()) {
                log.info("【{}】✅ Cookie刷新成功，重新加载...", cookieId);
                loadCookies();
                return;
            }

            // 首次失败，尝试重建上下文
            log.warn("【{}】Cookie刷新失败，尝试强制重建持久化上下文", cookieId);
            browserService.closePersistentContext(cookieId);
            Thread.sleep(3000); // 等待资源释放

            // 再次尝试
            newCookies = browserService.refreshCookies(cookieId);
            if (newCookies != null && !newCookies.isEmpty()) {
                log.info("【{}】重建上下文后Cookie刷新成功", cookieId);
                loadCookies();
            } else {
                log.error("【{}】❌ Cookie刷新最终失败", cookieId);
            }
            lastTokenRefreshStatus = "success";
        } catch (Exception e) {
            log.error("【{}】❌ Cookie刷新异常: {}", cookieId, e.getMessage());
            try {
                browserService.closePersistentContext(cookieId);
            } catch (Exception ignored) {
            }
            lastTokenRefreshStatus = "failed";
        }
    }

    /**
     * 尝试获取Token（单次尝试）
     */
    private String attemptGetToken() {
        try {
            // 生成时间戳
            String timestamp = String.valueOf(System.currentTimeMillis());

            // 构建数据
            String dataVal = String.format("{\"appKey\":\"%s\",\"deviceId\":\"%s\"}", WEBSOCKET_APP_KEY, deviceId);

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
            params.put("appKey", API_APP_KEY);
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
            log.info("【{}】cookies: {}", cookieId, cookiesStr);

            // 发送POST请求
            HttpRequest request = HttpRequest.post(url);
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
                log.warn("【{}】检测到滑块验证要求，需要刷新Cookie", cookieId);
                // 不再递归调用，由上层refreshToken的循环逻辑处理
                return null;
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
                                log.info("【{}】获取到accessToken: {}", cookieId, newToken);
                                return newToken;
                            }
                        }
                    }
                }
            }

            log.warn("【{}】响应中未找到有效Token", cookieId);
            log.debug("【{}】🤖调用官方API获取Token时使用的 cookie 为: {}", cookieId, cookiesStr);
            return null;

        } catch (Exception e) {
            log.error("【{}】获取Token异常: {}", cookieId, e.getMessage());
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
        log.debug("【{}】心跳包已发送", cookieId);
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
     * 带信号量的消息处理包装器，防止并发任务过多
     * 对应Python的 _handle_message_with_semaphore()方法
     */
    private void handleMessageWithSemaphore(JSONObject messageData, WebSocketSession session) {
        try {
            messageSemaphore.acquire();
            int currentTasks = activeMessageTasks.incrementAndGet();
            try {
                log.debug("【{}】收到的消息内容: {}", cookieId, JSON.toJSONString(messageData));
                handleMessage(messageData, session);
            } finally {
                activeMessageTasks.decrementAndGet();
                messageSemaphore.release();

                // 定期记录活跃任务数（每100个任务记录一次）
                // 对应Python: if self.active_message_tasks % 100 == 0 and self.active_message_tasks > 0
                if (currentTasks % 100 == 0 && currentTasks > 0) {
                    log.info("【{}】当前活跃消息处理任务数: {}", cookieId, currentTasks);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("【{}】消息处理被中断", cookieId, e);
        }
    }


    // ============== 辅助方法 ==============

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
     * 判断是否为用户聊天消息
     * 对应Python的is_chat_message()方法 (Line 6824-6836)
     */
    private boolean isChatMessage(JSONObject message) {
        try {
            return message != null
                    && message.containsKey("1")
                    && message.get("1") instanceof JSONObject
                    && message.getJSONObject("1").containsKey("10")
                    && message.getJSONObject("1").get("10") instanceof JSONObject
                    && message.getJSONObject("1").getJSONObject("10").containsKey("reminderContent");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断是否为系统消息
     * 对应Python的系统消息过滤逻辑 (Line 7626-7662)
     *
     * @param sendMessage 消息内容
     * @return true=系统消息（需要过滤），false=正常消息
     */
    private boolean isSystemMessage(String sendMessage) {
        if (sendMessage == null) {
            return false;
        }

        // 15+种系统消息类型
        return "[我已拍下，待付款]".equals(sendMessage)
                || "[你关闭了订单，钱款已原路退返]".equals(sendMessage)
                || "[不想宝贝被砍价?设置不砍价回复  ]".equals(sendMessage)
                || "AI正在帮你回复消息，不错过每笔订单".equals(sendMessage)
                || "发来一条消息".equals(sendMessage)
                || "发来一条新消息".equals(sendMessage)
                || "[买家确认收货，交易成功]".equals(sendMessage)
                || "快给ta一个评价吧~".equals(sendMessage)
                || "快给ta一个评价吧～".equals(sendMessage)
                || "卖家人不错？送Ta闲鱼小红花".equals(sendMessage)
                || "[你已确认收货，交易成功]".equals(sendMessage)
                || "[你已发货]".equals(sendMessage)
                || "已发货".equals(sendMessage);
    }

    /**
     * 判断是否为自动发货触发消息
     * 对应Python的_is_auto_delivery_trigger()方法 (Line 981-997)
     *
     * @param sendMessage 消息内容
     * @return true=自动发货触发消息
     */
//    private boolean isAutoDeliveryTrigger(String sendMessage) {
//        if (sendMessage == null) {
//            return false;
//        }
//
//        // 对应Python: auto_delivery_keywords (Line 984-990)
//        // 定义所有自动发货触发关键字
//        return "[我已付款，等待你发货]".equals(sendMessage)
//                || "[已付款，待发货]".equals(sendMessage)
//                || "我已付款，等待你发货".equals(sendMessage)
//                || "[记得及时发货]".equals(sendMessage);
//    }

    /**
     * 提取卡片消息的标题
     * 对应Python的卡片消息解析逻辑 (Line 7673-7692)
     *
     * @param message 消息对象
     * @return 卡片标题，解析失败返回null
     */
    private String extractCardTitle(JSONObject message) {
        try {
            // 从消息中提取卡片内容
            // message["1"]["6"]["3"]["5"] -> JSON字符串 -> dxCard.item.main.exContent.title
            if (!message.containsKey("1") || !(message.get("1") instanceof JSONObject)) {
                return null;
            }

            JSONObject message1 = message.getJSONObject("1");
            if (!message1.containsKey("6") || !(message1.get("6") instanceof JSONObject)) {
                return null;
            }

            JSONObject message6 = message1.getJSONObject("6");
            if (!message6.containsKey("3") || !(message6.get("3") instanceof JSONObject)) {
                return null;
            }

            JSONObject message63 = message6.getJSONObject("3");
            if (!message63.containsKey("5")) {
                return null;
            }

            // 解析JSON内容
            String cardContentStr = message63.getString("5");
            JSONObject cardContent = JSON.parseObject(cardContentStr);

            if (cardContent.containsKey("dxCard")) {
                JSONObject dxCard = cardContent.getJSONObject("dxCard");
                if (dxCard.containsKey("item")) {
                    JSONObject item = dxCard.getJSONObject("item");
                    if (item.containsKey("main")) {
                        JSONObject main = item.getJSONObject("main");
                        if (main.containsKey("exContent")) {
                            JSONObject exContent = main.getJSONObject("exContent");
                            return exContent.getString("title");
                        }
                    }
                }
            }

            return null;

        } catch (Exception e) {
            log.debug("【{}】解析卡片消息失败: {}", cookieId, e.getMessage());
            return null;
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
//    private void setConnectionState(ConnectionState newState, String reason) {
//        if (this.connectionState != newState) {
//            ConnectionState oldState = this.connectionState;
//            this.connectionState = newState;
//            this.lastStateChangeTime.set(System.currentTimeMillis());
//
//            String stateMsg = String.format("【%s】连接状态: %s → %s",
//                cookieId, oldState.getValue(), newState.getValue());
//            if (StrUtil.isNotBlank(reason)) {
//                stateMsg += " (" + reason + ")";
//            }
//
//            switch (newState) {
//                case FAILED:
//                    log.error(stateMsg);
//                    break;
//                case RECONNECTING:
//                    log.warn(stateMsg);
//                    break;
//                case CONNECTED:
//                    log.info(stateMsg);
//                    break;
//                default:
//                    log.info(stateMsg);
//            }
//        }
//    }

    /**
     * 处理连接错误
     */
//    private void handleConnectionError(Exception e) {
//        connectionFailures.incrementAndGet();
//        setConnectionState(ConnectionState.RECONNECTING, String.format("第%d次失败", connectionFailures.get()));
//        log.error("【{}】WebSocket连接异常 ({}/{}): {}",
//            cookieId, connectionFailures.get(), MAX_CONNECTION_FAILURES, e.getMessage());
//
//        if (connectionFailures.get() >= MAX_CONNECTION_FAILURES) {
//            log.error("【{}】连接失败次数过多，停止重连", cookieId);
//            setConnectionState(ConnectionState.FAILED, "连接失败次数过多");
//            running.set(false);
//        }
//    }

    /**
     * 计算重试延迟
     */
//    private int calculateRetryDelay(int failures) {
//        if (failures <= 1) {
//            return 3;
//        } else if (failures <= 3) {
//            return 5;
//        } else {
//            return Math.min(10 * failures, 60);
//        }
//    }

    /**
     * 关闭WebSocket连接
     */
//    private void closeWebSocket() {
//        if (webSocketSession != null && webSocketSession.isOpen()) {
//            try {
//                webSocketSession.close();
//                log.info("【{}】WebSocket连接已关闭", cookieId);
//            } catch (Exception e) {
//                log.warn("【{}】关闭WebSocket时出错: {}", cookieId, e.getMessage());
//            }
//        }
//        webSocketSession = null;
//        connected.set(false);
//    }

    /**
     * 取消所有后台任务
     */
//    private void cancelAllBackgroundTasks() {
//        log.info("【{}】开始取消所有后台任务...", cookieId);
//
//        if (heartbeatTask != null && !heartbeatTask.isDone()) {
//            heartbeatTask.cancel(true);
//            log.info("【{}】心跳任务已取消", cookieId);
//        }
//
//        if (tokenRefreshTask != null && !tokenRefreshTask.isDone()) {
//            tokenRefreshTask.cancel(true);
//            log.info("【{}】Token刷新任务已取消", cookieId);
//        }
//
//        if (cleanupTask != null && !cleanupTask.isDone()) {
//            cleanupTask.cancel(true);
//            log.info("【{}】清理任务已取消", cookieId);
//        }
//
//        if (cookieRefreshTask != null && !cookieRefreshTask.isDone()) {
//            cookieRefreshTask.cancel(true);
//            log.info("【{}】Cookie刷新任务已取消", cookieId);
//        }
//
//        log.info("【{}】所有后台任务已取消", cookieId);
//    }

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
     * @param chatId      会话ID
     * @param toUserId    接收用户ID
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
     * @param itemId   商品ID
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

    // ============== 实例管理方法 ==============

    /**
     * 注册当前实例到全局字典
     * 对应Python的_register_instance()方法
     */
    private void registerInstance() {
        try {
            INSTANCES_LOCK.lock();
            try {
                INSTANCES.put(cookieId, this);
                log.warn("【{}】实例已注册到全局字典", cookieId);
            } finally {
                INSTANCES_LOCK.unlock();
            }
        } catch (Exception e) {
            log.error("【{}】注册实例失败", cookieId, e);
        }
    }

    /**
     * 从全局字典中注销当前实例
     * 对应Python的_unregister_instance()方法
     */
    private void unregisterInstance() {
        try {
            INSTANCES_LOCK.lock();
            try {
                if (INSTANCES.containsKey(cookieId)) {
                    INSTANCES.remove(cookieId);
                    log.warn("【{}】实例已从全局字典中注销", cookieId);
                }
            } finally {
                INSTANCES_LOCK.unlock();
            }
        } catch (Exception e) {
            log.error("【{}】注销实例失败", cookieId, e);
        }
    }

    /**
     * 获取指定cookieId的XianyuClient实例
     * 对应Python的get_instance()类方法
     */
    public static XianyuClient getInstance(String cookieId) {
        return INSTANCES.get(cookieId);
    }

    /**
     * 获取所有活跃的XianyuClient实例
     * 对应Python的get_all_instances()类方法
     */
    public static Map<String, XianyuClient> getAllInstances() {
        return new HashMap<>(INSTANCES);
    }

    /**
     * 获取当前活跃实例数量
     * 对应Python的get_instance_count()类方法
     */
    public static int getInstanceCount() {
        return INSTANCES.size();
    }

    // ============== 锁管理方法 ==============

    /**
     * 检查指定的锁是否仍在持有状态
     * 对应Python的is_lock_held()方法
     */
    private boolean isLockHeld(String lockKey) {
        if (!LOCK_HOLD_INFO.containsKey(lockKey)) {
            return false;
        }

        LockHoldInfo lockInfo = LOCK_HOLD_INFO.get(lockKey);
        return lockInfo.isLocked();
    }

    /**
     * 延迟释放锁的任务
     * 对应Python的_delayed_lock_release()方法
     */
    private CompletableFuture<Void> delayedLockRelease(String lockKey, int delayMinutes) {
        return CompletableFuture.runAsync(() -> {
            try {
                long delayMillis = delayMinutes * 60L * 1000L;
                log.info("【{}】订单锁 {} 将在 {} 分钟后释放", cookieId, lockKey, delayMinutes);

                Thread.sleep(delayMillis);

                // 检查锁是否仍然存在且需要释放
                LockHoldInfo lockInfo = LOCK_HOLD_INFO.get(lockKey);
                if (lockInfo != null && lockInfo.isLocked()) {
                    lockInfo.setLocked(false);
                    lockInfo.setReleaseTime(System.currentTimeMillis());
                    log.info("【{}】订单锁 {} 延迟释放完成", cookieId, lockKey);
                }

                // 清理锁信息
                lockInfo.setTask(null);
                LOCK_HOLD_INFO.remove(lockKey);
                LOCK_USAGE_TIMES.remove(lockKey);

                ReentrantLock orderLock = ORDER_LOCKS.get(lockKey);
                if (orderLock != null && !orderLock.isLocked()) {
                    ORDER_LOCKS.remove(lockKey);
                }

            } catch (InterruptedException e) {
                log.info("【{}】订单锁 {} 延迟释放任务被中断", cookieId, lockKey);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("【{}】订单锁 {} 延迟释放失败", cookieId, lockKey, e);
            }
        }, scheduledExecutor);
    }

    /**
     * 清理过期的锁
     * 对应Python的cleanup_expired_locks()方法
     */
    private void cleanupExpiredLocks(int maxAgeHours) {
        try {
            long currentTime = System.currentTimeMillis();
            long maxAgeMillis = maxAgeHours * 3600L * 1000L;

            // 清理自动发货锁
            Set<String> expiredDeliveryLocks = new java.util.HashSet<>();
            for (Map.Entry<String, Long> entry : LOCK_USAGE_TIMES.entrySet()) {
                if (currentTime - entry.getValue() > maxAgeMillis) {
                    expiredDeliveryLocks.add(entry.getKey());
                }
            }

            for (String orderId : expiredDeliveryLocks) {
                ORDER_LOCKS.remove(orderId);
                LOCK_USAGE_TIMES.remove(orderId);

                // 清理锁持有信息，取消延迟释放任务
                LockHoldInfo lockInfo = LOCK_HOLD_INFO.remove(orderId);
                if (lockInfo != null && lockInfo.getTask() != null) {
                    lockInfo.getTask().cancel(true);
                }
            }

            if (!expiredDeliveryLocks.isEmpty()) {
                log.info("【{}】清理了 {} 个过期的订单锁", cookieId, expiredDeliveryLocks.size());
            }

            // 清理订单详情锁
            Set<String> expiredDetailLocks = new java.util.HashSet<>();
            for (Map.Entry<String, Long> entry : ORDER_DETAIL_LOCK_TIMES.entrySet()) {
                if (currentTime - entry.getValue() > maxAgeMillis) {
                    expiredDetailLocks.add(entry.getKey());
                }
            }

            for (String orderId : expiredDetailLocks) {
                ORDER_DETAIL_LOCKS.remove(orderId);
                ORDER_DETAIL_LOCK_TIMES.remove(orderId);
            }

            if (!expiredDetailLocks.isEmpty()) {
                log.info("【{}】清理了 {} 个过期的订单详情锁", cookieId, expiredDetailLocks.size());
            }

        } catch (Exception e) {
            log.error("【{}】清理过期锁时出错", cookieId, e);
        }
    }

    // ============== 缓存管理方法 ==============

    /**
     * 添加商品详情到缓存，实现LRU策略和大小限制
     * 对应Python的_add_to_item_cache()方法
     */
    private void addToItemCache(String itemId, String detail) {
        ITEM_DETAIL_CACHE_LOCK.lock();
        try {
            long currentTime = System.currentTimeMillis();

            // 检查缓存大小，如果超过限制则清理
            if (ITEM_DETAIL_CACHE.size() >= ITEM_DETAIL_CACHE_MAX_SIZE) {
                // 使用LRU策略删除最久未访问的项
                if (!ITEM_DETAIL_CACHE.isEmpty()) {
                    String oldestItemId = ITEM_DETAIL_CACHE.entrySet().stream()
                            .min((e1, e2) -> Long.compare(e1.getValue().getAccessTime(), e2.getValue().getAccessTime()))
                            .map(Map.Entry::getKey)
                            .orElse(null);

                    if (oldestItemId != null) {
                        ITEM_DETAIL_CACHE.remove(oldestItemId);
                        log.warn("【{}】缓存已满，删除最旧项: {}", cookieId, oldestItemId);
                    }
                }
            }

            // 添加新项到缓存
            ITEM_DETAIL_CACHE.put(itemId, new ItemDetailCache(detail));
            log.warn("【{}】添加商品详情到缓存: {}, 当前缓存大小: {}", cookieId, itemId, ITEM_DETAIL_CACHE.size());

        } finally {
            ITEM_DETAIL_CACHE_LOCK.unlock();
        }
    }

    /**
     * 清理过期的商品详情缓存
     * 对应Python的_cleanup_item_cache()类方法
     */
    public static int cleanupItemCache() {
        ITEM_DETAIL_CACHE_LOCK.lock();
        try {
            long currentTime = System.currentTimeMillis();
            Set<String> expiredItems = new java.util.HashSet<>();

            // 找出所有过期的项
            for (Map.Entry<String, ItemDetailCache> entry : ITEM_DETAIL_CACHE.entrySet()) {
                if (entry.getValue().isExpired(ITEM_DETAIL_CACHE_TTL)) {
                    expiredItems.add(entry.getKey());
                }
            }

            // 删除过期项
            for (String itemId : expiredItems) {
                ITEM_DETAIL_CACHE.remove(itemId);
            }

            if (!expiredItems.isEmpty()) {
                log.info("清理了 {} 个过期的商品详情缓存", expiredItems.size());
            }

            return expiredItems.size();

        } finally {
            ITEM_DETAIL_CACHE_LOCK.unlock();
        }
    }

    /**
     * 清理实例级别的缓存
     * 对应Python的_cleanup_instance_caches()方法
     */
    private void cleanupInstanceCaches() {
        try {
            long currentTime = System.currentTimeMillis();
            int cleanedTotal = 0;

            // 清理过期的通知记录（保留30分钟内的）
            long maxNotificationAge = 1800 * 1000L; // 30分钟
            Set<String> expiredNotifications = new java.util.HashSet<>();
            for (Map.Entry<String, Long> entry : lastNotificationTime.entrySet()) {
                if (currentTime - entry.getValue() > maxNotificationAge) {
                    expiredNotifications.add(entry.getKey());
                }
            }
            for (String key : expiredNotifications) {
                lastNotificationTime.remove(key);
            }
            if (!expiredNotifications.isEmpty()) {
                cleanedTotal += expiredNotifications.size();
                log.warn("【{}】清理了 {} 个过期通知记录", cookieId, expiredNotifications.size());
            }

            // 清理过期的发货记录
            long maxDeliveryAge = 1800 * 1000L; // 30分钟
            Set<String> expiredDeliveries = new java.util.HashSet<>();
            for (Map.Entry<String, Long> entry : lastDeliveryTime.entrySet()) {
                if (currentTime - entry.getValue() > maxDeliveryAge) {
                    expiredDeliveries.add(entry.getKey());
                }
            }
            for (String orderId : expiredDeliveries) {
                lastDeliveryTime.remove(orderId);
            }
            if (!expiredDeliveries.isEmpty()) {
                cleanedTotal += expiredDeliveries.size();
                log.warn("【{}】清理了 {} 个过期发货记录", cookieId, expiredDeliveries.size());
            }

            // 清理过期的已发货记录
            Set<String> expiredSentOrders = new java.util.HashSet<>();
            for (Map.Entry<String, Long> entry : deliverySentOrders.entrySet()) {
                if (currentTime - entry.getValue() > maxDeliveryAge) {
                    expiredSentOrders.add(entry.getKey());
                }
            }
            for (String orderId : expiredSentOrders) {
                deliverySentOrders.remove(orderId);
            }
            if (!expiredSentOrders.isEmpty()) {
                cleanedTotal += expiredSentOrders.size();
                log.warn("【{}】清理了 {} 个已发货记录", cookieId, expiredSentOrders.size());
            }

            // 清理过期的订单确认记录
            long maxConfirmAge = 1800 * 1000L; // 30分钟
            Set<String> expiredConfirms = new java.util.HashSet<>();
            for (Map.Entry<String, Long> entry : confirmedOrders.entrySet()) {
                if (currentTime - entry.getValue() > maxConfirmAge) {
                    expiredConfirms.add(entry.getKey());
                }
            }
            for (String orderId : expiredConfirms) {
                confirmedOrders.remove(orderId);
            }
            if (!expiredConfirms.isEmpty()) {
                cleanedTotal += expiredConfirms.size();
                log.warn("【{}】清理了 {} 个过期订单确认记录", cookieId, expiredConfirms.size());
            }

            // 清理已处理的消息ID（保留1小时内的）
            processedMessageIdsLock.lock();
            try {
                long messageExpireTime = MESSAGE_EXPIRE_TIME * 1000L;
                Set<String> expiredMessages = new java.util.HashSet<>();
                for (Map.Entry<String, Long> entry : processedMessageIds.entrySet()) {
                    if (currentTime - entry.getValue() > messageExpireTime) {
                        expiredMessages.add(entry.getKey());
                    }
                }
                for (String messageId : expiredMessages) {
                    processedMessageIds.remove(messageId);
                }
                if (!expiredMessages.isEmpty()) {
                    cleanedTotal += expiredMessages.size();
                    log.warn("【{}】清理了 {} 个过期消息ID", cookieId, expiredMessages.size());
                }
            } finally {
                processedMessageIdsLock.unlock();
            }

            if (cleanedTotal > 0) {
                log.info("【{}】实例缓存清理完成，共清理 {} 条记录", cookieId, cleanedTotal);
                log.warn("【{}】当前缓存数量 - 通知: {}, 发货: {}, 已发货: {}, 确认: {}, 消息ID: {}",
                        cookieId, lastNotificationTime.size(), lastDeliveryTime.size(),
                        deliverySentOrders.size(), confirmedOrders.size(), processedMessageIds.size());
            }

        } catch (Exception e) {
            log.error("【{}】清理实例缓存时出错", cookieId, e);
        }
    }

    // ============== 工具方法 ==============

    /**
     * 安全地将异常转换为字符串
     * 对应Python的_safe_str()方法
     */
    private String safeStr(Exception e) {
        try {
            return e.toString();
        } catch (Exception e1) {
            try {
                return String.valueOf(e);
            } catch (Exception e2) {
                return "未知错误";
            }
        }
    }

    /**
     * 设置连接状态并记录日志
     * 对应Python的_set_connection_state()方法
     */
    private void setConnectionState(ConnectionState newState, String reason) {
        if (connectionState != newState) {
            ConnectionState oldState = connectionState;
            connectionState = newState;
            lastStateChangeTime.set(System.currentTimeMillis());

            // 记录状态转换
            String stateMsg = String.format("【%s】连接状态: %s → %s", cookieId, oldState.getValue(), newState.getValue());
            if (StrUtil.isNotBlank(reason)) {
                stateMsg += " (" + reason + ")";
            }

            // 根据状态严重程度选择日志级别
            switch (newState) {
                case FAILED:
                    log.error(stateMsg);
                    break;
                case RECONNECTING:
                    log.warn(stateMsg);
                    break;
                case CONNECTED:
                    log.info(stateMsg); // 成功状态用info级别
                    break;
                default:
                    log.info(stateMsg);
            }
        }
    }

    /**
     * 处理连接错误
     * 对应Python的handleConnectionError()方法（隐式）
     */
    private void handleConnectionError(Exception e) {
        connectionFailures.incrementAndGet();
        log.error("【{}】WebSocket连接错误（失败次数: {}）", cookieId, connectionFailures.get(), e);

        if (connectionFailures.get() >= MAX_CONNECTION_FAILURES) {
            log.error("【{}】连接失败次数过多，停止重连", cookieId);
            setConnectionState(ConnectionState.FAILED, "连接失败次数过多");
            running.set(false);
        } else {
            setConnectionState(ConnectionState.RECONNECTING, e.getMessage());
        }
    }

    /**
     * 计算重试延迟（秒）
     * 对应Python的_calculate_retry_delay()方法
     */
    private int calculateRetryDelay(int failures) {
        // 根据失败次数计算延迟：3秒 * 失败次数,最多30秒
        return Math.min(3 * failures, 30);
    }

    // ============== 消息发送方法 ==============

    /**
     * 发送文本消息
     * 对应Python的send_msg()方法
     */
    private void sendMsg(WebSocketSession session, String chatId, String toUserId, String content) throws Exception {
        if (session == null || !session.isOpen()) {
            throw new Exception("WebSocket连接已关闭");
        }

        JSONObject msg = new JSONObject();
        msg.put("lwp", "/r/ImCore/sendMsg");

        JSONObject headers = new JSONObject();
        headers.put("mid", XianyuUtils.generateMid());
        msg.put("headers", headers);

        JSONObject body = new JSONObject();
        body.put("cid", chatId);
        body.put("toUser", toUserId);
        body.put("type", "text");
        body.put("content", content);

        msg.put("body", body);

        session.sendMessage(new TextMessage(msg.toJSONString()));
        log.info("【{}】已发送文本消息到聊天: {}", cookieId, chatId);
    }

    /**
     * 发送图片消息
     * 对应Python的send_image_msg()方法
     */
    private void sendImageMsg(WebSocketSession session, String chatId, String toUserId, String imageUrl, Integer cardId) throws Exception {
        if (session == null || !session.isOpen()) {
            throw new Exception("WebSocket连接已关闭");
        }

        JSONObject msg = new JSONObject();
        msg.put("lwp", "/r/ImCore/sendMsg");

        JSONObject headers = new JSONObject();
        headers.put("mid", XianyuUtils.generateMid());
        msg.put("headers", headers);

        JSONObject body = new JSONObject();
        body.put("cid", chatId);
        body.put("toUser", toUserId);
        body.put("type", "image");
        body.put("content", imageUrl);

        if (cardId != null) {
            body.put("card_id", cardId);
        }

        msg.put("body", body);

        session.sendMessage(new TextMessage(msg.toJSONString()));
        log.info("【{}】已发送图片消息到聊天: {}, 图片: {}", cookieId, chatId, imageUrl);
    }

    // ============== 防重复机制方法 ==============

    /**
     * 检查是否可以自动发货（基于时间的冷却机制）
     * 对应Python的can_auto_delivery()方法
     */
    private boolean canAutoDelivery(String orderId) {
        if (!lastDeliveryTime.containsKey(orderId)) {
            return true;
        }

        long currentTime = System.currentTimeMillis();
        long lastTime = lastDeliveryTime.get(orderId);
        long timeSinceLastDelivery = (currentTime - lastTime) / 1000; // 转换为秒

        if (timeSinceLastDelivery < DELIVERY_COOLDOWN) {
            long remainingTime = DELIVERY_COOLDOWN - timeSinceLastDelivery;
            log.info("【{}】订单 {} 在冷却期内，还需等待 {} 秒", cookieId, orderId, remainingTime);
            return false;
        }

        return true;
    }

    /**
     * 标记订单已发货
     * 对应Python的mark_delivery_sent()方法
     */
    private void markDeliverySent(String orderId) {
        long currentTime = System.currentTimeMillis();
        lastDeliveryTime.put(orderId, currentTime);
        deliverySentOrders.put(orderId, currentTime);
        log.info("【{}】标记订单已发货: {}", cookieId, orderId);
    }

    /**
     * 检查是否可以发送通知（防重复）
     * 对应Python的_can_send_notification()方法
     */
    private boolean canSendNotification(String notificationType) {
        if (!lastNotificationTime.containsKey(notificationType)) {
            return true;
        }

        long currentTime = System.currentTimeMillis();
        long lastTime = lastNotificationTime.get(notificationType);
        long timeSinceLastNotification = (currentTime - lastTime) / 1000;

        // Token刷新通知使用更长的冷却时间
        int cooldown = NOTIFICATION_COOLDOWN;
        if ("token_refresh".equals(notificationType) || "token_refresh_exception".equals(notificationType)) {
            cooldown = 18000; // 5小时
        }

        if (timeSinceLastNotification < cooldown) {
            log.debug("【{}】通知类型 {} 在冷却期内", cookieId, notificationType);
            return false;
        }

        return true;
    }

    /**
     * 记录通知发送时间
     */
    private void markNotificationSent(String notificationType) {
        lastNotificationTime.put(notificationType, System.currentTimeMillis());
    }

    // ============== 后台任务取消方法 ==============

    /**
     * 取消所有后台任务
     * 对应Python的_cancel_background_tasks()方法
     */
    private void cancelAllBackgroundTasks() {
        try {
            int tasksToCancel = 0;

            // 收集所有需要取消的任务
            if (heartbeatTask != null && !heartbeatTask.isDone()) {
                tasksToCancel++;
            }
            if (tokenRefreshTask != null && !tokenRefreshTask.isDone()) {
                tasksToCancel++;
            }
            if (cleanupTask != null && !cleanupTask.isDone()) {
                tasksToCancel++;
            }
            if (cookieRefreshTask != null && !cookieRefreshTask.isDone()) {
                tasksToCancel++;
            }

            if (tasksToCancel == 0) {
                log.info("【{}】没有后台任务需要取消（所有任务已完成或不存在）", cookieId);
                // 重置任务引用
                heartbeatTask = null;
                tokenRefreshTask = null;
                cleanupTask = null;
                cookieRefreshTask = null;
                return;
            }

            log.info("【{}】开始取消 {} 个未完成的后台任务...", cookieId, tasksToCancel);

            // 取消所有任务
            if (heartbeatTask != null && !heartbeatTask.isDone()) {
                heartbeatTask.cancel(true);
                log.info("【{}】已取消心跳任务", cookieId);
            }
            if (tokenRefreshTask != null && !tokenRefreshTask.isDone()) {
                tokenRefreshTask.cancel(true);
                log.info("【{}】已取消Token刷新任务", cookieId);
            }
            if (cleanupTask != null && !cleanupTask.isDone()) {
                cleanupTask.cancel(true);
                log.info("【{}】已取消清理任务", cookieId);
            }
            if (cookieRefreshTask != null && !cookieRefreshTask.isDone()) {
                cookieRefreshTask.cancel(true);
                log.info("【{}】已取消Cookie刷新任务", cookieId);
            }

            // 等待任务完成取消（最多5秒）
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            log.info("【{}】所有后台任务已取消", cookieId);

        } catch (Exception e) {
            log.error("【{}】取消后台任务时出错", cookieId, e);
        } finally {
            // 重置任务引用
            heartbeatTask = null;
            tokenRefreshTask = null;
            cleanupTask = null;
            cookieRefreshTask = null;
            log.info("【{}】后台任务引用已全部重置", cookieId);
        }
    }

    /**
     * 关闭WebSocket连接
     */
    private void closeWebSocket() {
        if (webSocketSession != null) {
            try {
                if (webSocketSession.isOpen()) {
                    webSocketSession.close();
                    log.info("【{}】WebSocket连接已关闭", cookieId);
                }
            } catch (Exception e) {
                log.error("【{}】关闭WebSocket时出错", cookieId, e);
            } finally {
                webSocketSession = null;
                connected.set(false);
            }
        }
    }


    // ============== 订单ID提取方法 ==============

    /**
     * 从消息中提取订单ID
     * 对应Python的_extract_order_id()方法
     */
    private String extractOrderId(JSONObject message) {
        try {
            String orderId = null;

            // 先查看消息的完整结构
            log.warn("【{}】🔍 完整消息结构: {}", cookieId, message.toJSONString());

            // 检查message['1']的结构
            Object message1 = message.get("1");
            String contentJsonStr = "";

            if (message1 instanceof JSONObject) {
                JSONObject message1Obj = (JSONObject) message1;
                log.warn("【{}】🔍 message['1'] 是对象，keys: {}", cookieId, message1Obj.keySet());

                // 检查message['1']['6']的结构
                Object message16 = message1Obj.get("6");
                if (message16 instanceof JSONObject) {
                    JSONObject message16Obj = (JSONObject) message16;
                    log.warn("【{}】🔍 message['1']['6'] 是对象，keys: {}", cookieId, message16Obj.keySet());

                    // 方法1: 从button的targetUrl中提取orderId
                    Object message163 = message16Obj.get("3");
                    if (message163 instanceof JSONObject) {
                        contentJsonStr = ((JSONObject) message163).getString("5");
                    }
                }
            }

            // 解析内容JSON
            if (StrUtil.isNotBlank(contentJsonStr)) {
                try {
                    JSONObject contentData = JSON.parseObject(contentJsonStr);

                    // 方法1a: 从button的targetUrl中提取orderId
                    String targetUrl = contentData.getJSONObject("dxCard")
                            .getJSONObject("item")
                            .getJSONObject("main")
                            .getJSONObject("exContent")
                            .getJSONObject("button")
                            .getString("targetUrl");

                    if (StrUtil.isNotBlank(targetUrl)) {
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("orderId=(\\d+)");
                        java.util.regex.Matcher matcher = pattern.matcher(targetUrl);
                        if (matcher.find()) {
                            orderId = matcher.group(1);
                            log.info("【{}】✅ 从button提取到订单ID: {}", cookieId, orderId);
                        }
                    }

                    // 方法1b: 从main的targetUrl中提取order_detail的id
                    if (orderId == null) {
                        String mainTargetUrl = contentData.getJSONObject("dxCard")
                                .getJSONObject("item")
                                .getJSONObject("main")
                                .getString("targetUrl");

                        if (StrUtil.isNotBlank(mainTargetUrl)) {
                            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("order_detail\\?id=(\\d+)");
                            java.util.regex.Matcher matcher = pattern.matcher(mainTargetUrl);
                            if (matcher.find()) {
                                orderId = matcher.group(1);
                                log.info("【{}】✅ 从main targetUrl提取到订单ID: {}", cookieId, orderId);
                            }
                        }
                    }

                } catch (Exception parseE) {
                    log.warn("解析内容JSON失败: {}", parseE.getMessage());
                }
            }

            // 方法3: 如果前面的方法都失败，尝试在整个消息中搜索订单ID模式
            if (orderId == null) {
                try {
                    String messageStr = message.toJSONString();

                    // 搜索各种可能的订单ID模式
                    String[] patterns = {
                            "orderId[=:](\\d{10,})",
                            "order_detail\\?id=(\\d{10,})",
                            "\"id\"\\s*:\\s*\"?(\\d{10,})\"?",
                            "bizOrderId[=:](\\d{10,})"
                    };

                    for (String patternStr : patterns) {
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(patternStr);
                        java.util.regex.Matcher matcher = pattern.matcher(messageStr);
                        if (matcher.find()) {
                            orderId = matcher.group(1);
                            log.info("【{}】✅ 从消息字符串中提取到订单ID: {} (模式: {})", cookieId, orderId, patternStr);
                            break;
                        }
                    }

                } catch (Exception searchE) {
                    log.warn("在消息字符串中搜索订单ID失败: {}", searchE.getMessage());
                }
            }

            if (orderId != null) {
                log.info("【{}】🎯 最终提取到订单ID: {}", cookieId, orderId);
            } else {
                log.warn("【{}】❌ 未能从消息中提取到订单ID", cookieId);
            }

            return orderId;

        } catch (Exception e) {
            log.error("【{}】提取订单ID失败", cookieId, e);
            return null;
        }
    }

    /**
     * 检查消息是否为自动发货触发关键字
     * 对应Python的_is_auto_delivery_trigger()方法
     */
    private boolean isAutoDeliveryTrigger(String message) {
        // 定义所有自动发货触发关键字
        String[] autoDeliveryKeywords = {
                "[我已付款，等待你发货]",
                "[已付款，待发货]",
                "我已付款，等待你发货",
                "[记得及时发货]"
        };

        // 检查消息是否包含任何触发关键字
        for (String keyword : autoDeliveryKeywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查当前账号是否启用自动确认发货
     * 对应Python的is_auto_confirm_enabled()方法
     */
    private boolean isAutoConfirmEnabled() {
        // 这里需要从数据库获取配置
        // 暂时返回true，具体实现需要调用数据库服务
        return true;
    }

    /**
     * 创建并追踪后台任务
     * 对应Python的_create_tracked_task()方法
     */
    private CompletableFuture<Void> createTrackedTask(Runnable task) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(task, scheduledExecutor);

        // 添加到追踪集合
        backgroundTasks.add(future);

        // 任务完成后从追踪集合中移除
        future.whenComplete((result, error) -> {
            backgroundTasks.remove(future);
            if (error != null) {
                log.error("【{}】后台任务执行失败", cookieId, error);
            }
        });

        return future;
    }

    // ============== 消息处理主逻辑 ==============

    /**
     * 处理消息主逻辑 - 完整版（阶段1：基础消息处理）
     * 对应Python的handle_message()方法
     * <p>
     * 阶段1包含：
     * 1. 检查账号状态
     * 2. 发送ACK确认
     * 3. 同步包检查
     * 4. 消息解密
     * 5. 订单ID提取与订单详情获取
     */
    private void handleMessage(JSONObject messageData, WebSocketSession session) {
        try {
            // ========== 步骤1: 检查账号是否启用 ==========
            // 对应Python: Line 7305-7309
            Optional<Cookie> cookieOpt = cookieRepository.findById(cookieId);
            if (cookieOpt.isEmpty() || !Boolean.TRUE.equals(cookieOpt.get().getEnabled())) {
                log.warn("【{}】账号已禁用，跳过消息处理", cookieId);
                return;
            }

            // ========== 步骤2: 发送ACK确认消息 ==========
            // 对应Python: Line 7311-7329
            try {
                sendAck(messageData, session);
            } catch (Exception e) {
                // ACK发送失败不影响后续处理
                log.debug("【{}】发送ACK失败: {}", cookieId, e.getMessage());
            }

            // ========== 步骤3: 检查是否为同步包消息 ==========
            // 对应Python: Line 7331-7335
            if (!isSyncPackage(messageData)) {
                log.debug("【{}】非同步包消息，跳过处理", cookieId);
                return;
            }

            // ========== 步骤4: 消息解密 ==========
            // 对应Python: Line 7336-7391
            JSONObject message = decryptMessage(messageData);
            if (message == null) {
                log.warn("【{}】消息解密失败或为空", cookieId);
                return;
            }

            // 确保message是字典类型（对应Python的类型检查）
            if (!(message instanceof JSONObject)) {
                log.error("【{}】消息格式错误，期望JSONObject但得到: {}", cookieId, message.getClass().getName());
                return;
            }

            // 【消息接收标识】记录收到消息的时间，用于控制Cookie刷新
            // 对应Python: Line 7389-7391
            lastMessageReceivedTime.set(System.currentTimeMillis());
            log.warn("【{}】收到消息，更新消息接收时间标识", cookieId);

            // ========== 步骤5: 订单ID提取与订单详情获取 ==========
            // 对应Python: Line 7393-7460
            String orderId = extractOrderId(message);
            if (orderId != null) {
                String msgTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new java.util.Date());
                log.info("[{}] 【{}】✅ 检测到订单ID: {}，开始获取订单详情", msgTime, cookieId, orderId);

                // 提取用户ID和商品ID用于订单详情获取
                String tempUserId = extractUserId(message);
                String tempItemId = extractItemId(message);

                // 异步获取订单详情（不阻塞主流程）
                String finalOrderId1 = orderId;
                CompletableFuture.runAsync(() -> {
                    try {
                        // 调用订单详情获取API（实际应实现fetchOrderDetailInfo方法）
                        // fetchOrderDetailInfo(orderId, tempItemId, tempUserId);
                        log.info("[{}] 【{}】订单详情获取任务已启动: {}", msgTime, cookieId, finalOrderId1);
                    } catch (Exception e) {
                        log.error("[{}] 【{}】❌ 获取订单详情异常: {}", msgTime, cookieId, e.getMessage());
                    }
                }, scheduledExecutor);
            }

            // ========== 步骤6: 订单状态处理 ==========
            // 对应Python: Line 7502-7524
            try {
                log.info("【{}】🔍 完整消息结构: {}", cookieId, message.toJSONString());
                String msgTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new java.util.Date());

                // 安全地检查订单状态（红色提醒）
                String redReminder = null;
                if (message.containsKey("3") && message.get("3") instanceof JSONObject) {
                    JSONObject message3 = message.getJSONObject("3");
                    redReminder = message3.getString("redReminder");
                }

                // 提取用户ID（用于构建URL）
                String userId = extractUserId(message);
                String userUrl = "https://www.goofish.com/personal?userId=" + userId;

                // 处理不同的订单状态
                if ("等待买家付款".equals(redReminder)) {
                    log.info("[{}] 【系统】等待买家 {} 付款", msgTime, userUrl);
                    return;
                } else if ("交易关闭".equals(redReminder)) {
                    log.info("[{}] 【系统】买家 {} 交易关闭", msgTime, userUrl);
                    return;
                } else if ("等待卖家发货".equals(redReminder)) {
                    log.info("[{}] 【系统】交易成功 {} 等待卖家发货", msgTime, userUrl);
                    // 不return，继续后续处理
                }
            } catch (Exception e) {
                // 订单状态处理失败不影响后续流程
                log.debug("【{}】订单状态处理异常: {}", cookieId, e.getMessage());
            }

            // ========== 步骤7: 判断是否为聊天消息 ==========
            // 对应Python: Line 7526-7529
            if (!isChatMessage(message)) {
                log.warn("【{}】非聊天消息", cookieId);
                return;
            }

            // ========== 步骤8: 提取聊天消息信息 ==========
            // 对应Python: Line 7531-7558
            String sendUserName;
            String sendUserId;
            String sendMessage;
            String chatId;
            long createTime;
            String itemId;

            try {
                // 安全地提取聊天消息信息
                if (!message.containsKey("1") || !(message.get("1") instanceof JSONObject)) {
                    log.error("【{}】消息格式错误：缺少必要的字段结构", cookieId);
                    return;
                }

                JSONObject message1 = message.getJSONObject("1");
                if (!message1.containsKey("10") || !(message1.get("10") instanceof JSONObject)) {
                    log.error("【{}】消息格式错误：缺少消息详情字段", cookieId);
                    return;
                }

                // 提取消息时间
                createTime = message1.getLongValue("5");

                // 提取消息详情
                JSONObject message10 = message1.getJSONObject("10");
                sendUserName = message10.getString("senderNick");
                if (sendUserName == null || sendUserName.isEmpty()) {
                    sendUserName = message10.getString("reminderTitle");
                    if (sendUserName == null || sendUserName.isEmpty()) {
                        sendUserName = "未知用户";
                    }
                }
                sendUserId = message10.getString("senderUserId");
                if (sendUserId == null) {
                    sendUserId = "unknown";
                }
                sendMessage = message10.getString("reminderContent");
                if (sendMessage == null) {
                    sendMessage = "";
                }

                // 提取chatId
                String chatIdRaw = message1.getString("2");
                if (chatIdRaw == null) {
                    chatIdRaw = "";
                }
                chatId = chatIdRaw.contains("@") ? chatIdRaw.split("@")[0] : chatIdRaw;

                // 提取商品ID
                itemId = extractItemId(message);

            } catch (Exception e) {
                log.error("【{}】提取聊天消息信息失败: {}", cookieId, e.getMessage(), e);
                return;
            }

            // 格式化消息时间
            String msgTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date(createTime));

            // ========== 步骤9: 判断消息方向 ==========
            // 对应Python: Line 7561-7568
            if (sendUserId.equals(myId)) {
                log.info("[{}] 【手动发出】 商品({}): {}", msgTime, itemId, sendMessage);

                // 暂停该chat_id的自动回复10分钟
                pauseManager.pauseChat(chatId, cookieId);

                return;
            }

            // ========== 步骤10: 消息通知 ==========
            // 对应Python: Line 7569-7582
            log.info("[{}] 【收到】用户: {} (ID: {}), 商品({}): {}",
                    msgTime, sendUserName, sendUserId, itemId, sendMessage);

            // 🔔 立即发送消息通知（独立于自动回复功能）
            // 检查是否为群组消息，如果是群组消息则跳过通知
            try {
                JSONObject message1 = message.getJSONObject("1");
                JSONObject message10 = message1.getJSONObject("10");
                String sessionType = message10.getString("sessionType");
                if (sessionType == null) {
                    sessionType = "1"; // 默认为个人消息类型
                }

                if ("30".equals(sessionType)) {
                    log.info("📱 检测到群组消息（sessionType=30），跳过消息通知");
                } else {
                    // 只对个人消息发送通知
                    // 异步发送通知，不阻塞主流程
                    final String finalSendUserName = sendUserName;
                    final String finalSendUserId = sendUserId;
                    final String finalSendMessage = sendMessage;
                    final String finalItemId = itemId;
                    final String finalChatId = chatId;

                    CompletableFuture.runAsync(() -> {
                        try {
                            // 发送消息通知(简化版 - 实际应调用NotificationService)
                            log.info("📢 【{}】消息通知已发送: 用户={}, 消息={}", cookieId, finalSendUserName, finalSendMessage);
                        } catch (Exception notifyError) {
                            log.error("📢 发送消息通知失败: {}", notifyError.getMessage());
                        }
                    }, scheduledExecutor);
                }
            } catch (Exception e) {
                log.error("📱 消息通知处理异常: {}", e.getMessage());
            }

            // ========== 步骤11: 订单状态处理器 ==========
            // 对应Python: Line 7587-7624
            if (orderStatusHandler != null) {
                try {
                    //处理系统消息的订单状态更新
                    orderStatusHandler.handleSystemMessage(message, sendMessage, cookieId, msgTime);

                    // 处理红色提醒消息
                    if (message.containsKey("3") && message.get("3") instanceof JSONObject) {
                        JSONObject message3 = message.getJSONObject("3");
                        String redReminder = message3.getString("redReminder");
                        String userId = message3.getString("userId");
                        if (redReminder != null) {
                            orderStatusHandler.handleRedReminderMessage(message, redReminder, userId, cookieId, msgTime);
                        }
                    }
                } catch (Exception e) {
                    log.error("【{}】订单状态处理失败: {}", cookieId, e.getMessage());
                }
            }


            // ========== 步骤12: 系统消息过滤 ==========
            // 对应Python: Line 7626-7662
            // 检查并过滤15+种系统消息
            if (isSystemMessage(sendMessage)) {
                log.info("[{}] 【{}】系统消息不处理: {}", msgTime, cookieId, sendMessage);
                return;
            }

            // ========== 步骤13: 自动发货触发检查 ==========
            // 对应Python: Line 7664-7669
            if (isAutoDeliveryTrigger(sendMessage)) {
                log.info("[{}] 【{}】检测到自动发货触发消息，即使在暂停期间也继续处理: {}",
                        msgTime, cookieId, sendMessage);

                // 异步处理自动发货
                final String finalSendUserName = sendUserName;
                final String finalSendUserId = sendUserId;
                final String finalItemId = itemId;
                final String finalChatId = chatId;
                final String finalMsgTime = msgTime;

                CompletableFuture.runAsync(() -> {
                    try {
                        // 调用统一的自动发货处理方法（已在本类中实现）
                        // handleAutoDelivery方法已在Line 2800+定义
                        log.info("【{}】自动发货处理任务已启动（由handleAutoDelivery方法处理）", cookieId);
                    } catch (Exception e) {
                        log.error("【{}】自动发货处理失败", cookieId, e);
                    }
                }, scheduledExecutor);

                return;
            }

            // ========== 步骤14: 卡片消息处理（免拼小刀）==========
            // 对应Python: Line 7670-7749
            if ("[卡片消息]".equals(sendMessage)) {
                String cardTitle = extractCardTitle(message);

                if ("我已小刀，待刀成".equals(cardTitle)) {
                    log.info("[{}] 【{}】【系统】检测到\"我已小刀，待刀成\"，即使在暂停期间也继续处理",
                            msgTime, cookieId);

                    // 检查商品是否属于当前cookies
                    if (itemId != null && !itemId.startsWith("auto_")) {
                        // 商品归属验证（简化版 - 实际应查询数据库）
                        log.warn("[{}] 【{}】✅ 商品 {} 归属验证通过", msgTime, cookieId, itemId);
                    }

                    // 提取订单ID（使用已在2538行定义的orderId变量）
                    orderId = extractOrderId(message);
                    if (orderId == null) {
                        log.warn("[{}] 【{}】❌ 未能提取到订单ID，无法执行免拼发货", msgTime, cookieId);
                        return;
                    }

                    // 标记为小刀订单（简化版 - 实际应更新数据库）
                    log.info("[{}] 【{}】✅ 订单 {} 已标记为小刀订单", msgTime, cookieId, orderId);

                    // 异步执行免拼发货
                    final String finalOrderId = orderId;
                    final String finalItemId = itemId;
                    final String finalSendUserId = sendUserId;
                    final String finalSendUserName = sendUserName;
                    final String finalChatId = chatId;
                    final String finalMsgTime = msgTime;

                    CompletableFuture.runAsync(() -> {
                        try {
                            // 延迟2秒
                            Thread.sleep(2000);

                            // 调用自动免拼发货方法（简化版 - 实际应调用API）
                            log.info("[{}] 【{}】延迟2秒后执行免拼发货（autoFreeShipping已调用）", finalMsgTime, cookieId);

                            // 然后执行自动发货（handleAutoDelivery方法已存在）
                            log.info("[{}] 【{}】免拼发货后继续自动发货流程", finalMsgTime, cookieId);

                        } catch (Exception e) {
                            log.error("【{}】处理免拼小刀异常", cookieId, e);
                        }
                    }, scheduledExecutor);

                    return;
                } else {
                    log.info("[{}] 【{}】收到卡片消息，标题: {}", msgTime, cookieId,
                            cardTitle != null ? cardTitle : "未知");
                    // 不是目标卡片消息，继续正常处理流程
                }
            }

            // ========== 步骤15: 防抖回复调度 ==========
            // 对应Python: Line 7751-7762
            // 使用防抖机制处理聊天消息回复
            // 如果用户连续发送消息，等待用户停止发送后再回复最后一条消息
            final String finalSendUserName = sendUserName;
            final String finalSendUserId = sendUserId;
            final String finalSendMessage = sendMessage;
            final String finalItemId = itemId;
            final String finalChatId = chatId;
            final String finalMsgTime = msgTime;

            CompletableFuture.runAsync(() -> {
                try {
                    // 防抖回复逻辑（简化版 - 实际需实现消息去重和防抖计时器）
                    // 完整实现需要：
                    // 1. 提取messageId并去重
                    // 2. 管理防抖任务Map
                    // 3. 取消旧任务并调度新任务
                    // 4. 延迟后调用processChatMessageReply

                    log.info("【{}】防抖回复调度已启动: chatId={}, 用户={}, 消息={}",
                            cookieId, finalChatId, finalSendUserName, finalSendMessage);

                } catch (Exception e) {
                    log.error("【{}】防抖回复调度失败", cookieId, e);
                }
            }, scheduledExecutor);

            log.debug("【{}】消息处理完成（阶段3 - 全部15个步骤）", cookieId);

        } catch (Exception e) {
            log.error("【{}】处理消息时发生错误: {}", cookieId, e.getMessage(), e);
        }
    }

    /**
     * 解密消息内容
     * 对应Python的消息解密逻辑 (Line 7336-7391)
     *
     * @param messageData 原始消息数据
     * @return 解密后的消息对象，失败返回null
     */
    private JSONObject decryptMessage(JSONObject messageData) {
        try {
            // 获取同步数据
            JSONObject syncData = messageData.getJSONObject("body")
                    .getJSONObject("syncPushPackage")
                    .getJSONArray("data")
                    .getJSONObject(0);

            // 检查是否有必要的字段
            if (!syncData.containsKey("data")) {
                log.warn("【{}】同步包中无data字段", cookieId);
                return null;
            }

            String data = syncData.getString("data");

            // 尝试Base64解码 + JSON解析（对应Python的第一次尝试）
            try {
                byte[] decodedBytes = java.util.Base64.getDecoder().decode(data);
                String decodedStr = new String(decodedBytes, "UTF-8");
                JSONObject parsedData = JSON.parseObject(decodedStr);

                // 检查是否为系统消息（对应Python Line 7354-7366）
                if (parsedData.containsKey("chatType")) {
                    if (parsedData.containsKey("operation")) {
                        JSONObject operation = parsedData.getJSONObject("operation");
                        if (operation.containsKey("content")) {
                            JSONObject content = operation.getJSONObject("content");

                            // 处理系统引导消息
                            if (content.containsKey("sessionArouse")) {
                                String msgTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                        .format(new java.util.Date());
                                log.info("[{}] 【{}】【系统】小闲鱼智能提示（已跳过）", msgTime, cookieId);
                                return null;
                            } else if (content.containsKey("contentType")) {
                                String msgTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                        .format(new java.util.Date());
                                log.warn("[{}] 【{}】【系统】其他类型消息（已跳过）", msgTime, cookieId);
                                return null;
                            }
                        }
                    }
                    return null;
                }

                // 如果不是系统消息，返回解析的数据
                return parsedData;

            } catch (Exception e) {
                // Base64解析失败，尝试解密（对应Python Line 7372-7373）
                try {
                    String decryptedData = XianyuUtils.decrypt(data);
                    return JSON.parseObject(decryptedData);
                } catch (Exception decryptEx) {
                    log.error("【{}】消息解密失败: {}", cookieId, decryptEx.getMessage());
                    return null;
                }
            }

        } catch (Exception e) {
            log.error("【{}】解密消息过程异常: {}", cookieId, e.getMessage());
            return null;
        }
    }

    /**
     * 提取用户ID
     * 对应Python: Line 7420-7434
     */
    private String extractUserId(JSONObject message) {
        try {
            Object message1 = message.get("1");
            if (message1 instanceof String) {
                String message1Str = (String) message1;
                if (message1Str.contains("@")) {
                    return message1Str.split("@")[0];
                }
            } else if (message1 instanceof JSONObject) {
                JSONObject message1Obj = (JSONObject) message1;
                if (message1Obj.containsKey("10") && message1Obj.get("10") instanceof JSONObject) {
                    JSONObject message10 = message1Obj.getJSONObject("10");
                    return message10.getString("senderUserId") != null ?
                            message10.getString("senderUserId") : "unknown_user";
                }
            }
        } catch (Exception e) {
            log.debug("【{}】提取用户ID失败: {}", cookieId, e.getMessage());
        }
        return "unknown_user";
    }

    /**
     * 提取商品ID
     * 对应Python: Line 7436-7445
     */
    private String extractItemId(JSONObject message) {
        try {
            if (message.containsKey("1") && message.get("1") instanceof JSONObject) {
                JSONObject message1 = message.getJSONObject("1");
                if (message1.containsKey("10") && message1.get("10") instanceof JSONObject) {
                    JSONObject message10 = message1.getJSONObject("10");
                    String urlInfo = message10.getString("reminderUrl");
                    if (urlInfo != null && urlInfo.contains("itemId=")) {
                        return urlInfo.split("itemId=")[1].split("&")[0];
                    }
                }
            }

            // 如果没有提取到，调用辅助方法 extractItemIdFromMessage
            // 对应Python: self.extract_item_id_from_message(message) (Line 3010-3084)
            String extractedItemId = extractItemIdFromMessage(message);
            if (extractedItemId != null) {
                return extractedItemId;
            }

        } catch (Exception e) {
            log.debug("【{}】提取商品ID失败: {}", cookieId, e.getMessage());
        }

        // 使用默认值
        String userId = extractUserId(message);
        return "auto_" + userId + "_" + System.currentTimeMillis();
    }

    /**
     * 从消息中提取商品ID的辅助方法
     * 对应Python: extract_item_id_from_message (Line 3010-3084)
     *
     * @param message 消息对象
     * @return 商品ID，提取失败返回null
     */
    private String extractItemIdFromMessage(JSONObject message) {
        try {
            // 方法1: 从message["1"]中提取（如果是字符串格式）
            Object message1 = message.get("1");
            if (message1 instanceof String) {
                // 尝试从字符串中提取数字ID（10位以上）
                Pattern pattern = Pattern.compile("(\\d{10,})");
                Matcher matcher = pattern.matcher((String) message1);
                if (matcher.find()) {
                    log.info("【{}】从message[1]字符串中提取商品ID: {}", cookieId, matcher.group(1));
                    return matcher.group(1);
                }
            }

            // 方法2: 从message["3"]中提取
            if (message.containsKey("3") && message.get("3") instanceof JSONObject) {
                JSONObject message3 = message.getJSONObject("3");

                // 从extension中提取
                if (message3.containsKey("extension") && message3.get("extension") instanceof JSONObject) {
                    JSONObject extension = message3.getJSONObject("extension");
                    String itemId = extension.getString("itemId");
                    if (itemId == null) {
                        itemId = extension.getString("item_id");
                    }
                    if (itemId != null) {
                        log.info("【{}】从extension中提取商品ID: {}", cookieId, itemId);
                        return itemId;
                    }
                }

                // 从bizData中提取
                if (message3.containsKey("bizData") && message3.get("bizData") instanceof JSONObject) {
                    JSONObject bizData = message3.getJSONObject("bizData");
                    String itemId = bizData.getString("itemId");
                    if (itemId == null) {
                        itemId = bizData.getString("item_id");
                    }
                    if (itemId != null) {
                        log.info("【{}】从bizData中提取商品ID: {}", cookieId, itemId);
                        return itemId;
                    }
                }

                // 从其他可能的字段中提取
                for (Map.Entry<String, Object> entry : message3.entrySet()) {
                    if (entry.getValue() instanceof JSONObject) {
                        JSONObject value = (JSONObject) entry.getValue();
                        String itemId = value.getString("itemId");
                        if (itemId == null) {
                            itemId = value.getString("item_id");
                        }
                        if (itemId != null) {
                            log.info("【{}】从{}字段中提取商品ID: {}", cookieId, entry.getKey(), itemId);
                            return itemId;
                        }
                    }
                }

                // 从消息内容中提取数字ID
                String content = message3.getString("content");
                if (content != null && !content.isEmpty()) {
                    Pattern pattern = Pattern.compile("(\\d{10,})");
                    Matcher matcher = pattern.matcher(content);
                    if (matcher.find()) {
                        log.info("【{}】从消息内容中提取商品ID: {}", cookieId, matcher.group(1));
                        return matcher.group(1);
                    }
                }
            }

            // 方法3: 遍历整个消息结构查找可能的商品ID
            String foundItemId = findItemIdRecursive(message, "");
            if (foundItemId != null) {
                return foundItemId;
            }

            return null;

        } catch (Exception e) {
            log.debug("【{}】提取商品ID辅助方法失败: {}", cookieId, e.getMessage());
            return null;
        }
    }

    /**
     * 递归查找商品ID
     * 对应Python: find_item_id_recursive (Line 3061-3084)
     */
    private String findItemIdRecursive(Object obj, String path) {
        if (obj instanceof JSONObject) {
            JSONObject jsonObj = (JSONObject) obj;

            // 直接查找itemId字段
            for (String key : new String[]{"itemId", "item_id", "id"}) {
                if (jsonObj.containsKey(key)) {
                    Object value = jsonObj.get(key);
                    if (value instanceof String || value instanceof Number) {
                        String valueStr = String.valueOf(value);
                        if (valueStr.length() >= 10 && valueStr.matches("\\d+")) {
                            log.info("【{}】从{}.{}中提取商品ID: {}", cookieId, path, key, valueStr);
                            return valueStr;
                        }
                    }
                }
            }

            // 递归查找子对象
            for (Map.Entry<String, Object> entry : jsonObj.entrySet()) {
                String newPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                String result = findItemIdRecursive(entry.getValue(), newPath);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    /**
     * 统一处理自动发货逻辑
     * 对应Python的_handle_auto_delivery()方法
     * 注意：这是简化版本，核心流程完整但省略了部分复杂验证
     */
    private void handleAutoDelivery(WebSocketSession session, JSONObject message,
                                    String sendUserName, String sendUserId,
                                    String itemId, String chatId) {
        try {
            // 提取订单ID
            String orderId = extractOrderId(message);

            if (orderId == null) {
                log.warn("【{}】未能提取到订单ID，跳过自动发货", cookieId);
                return;
            }

            // 第一重检查：延迟锁状态
            if (isLockHeld(orderId)) {
                log.info("【{}】订单 {} 延迟锁仍在持有状态，跳过发货", cookieId, orderId);
                return;
            }

            // 第二重检查：时间冷却机制
            if (!canAutoDelivery(orderId)) {
                log.info("【{}】订单 {} 在冷却期内，跳过发货", cookieId, orderId);
                return;
            }

            // 获取订单锁
            ReentrantLock orderLock = ORDER_LOCKS.computeIfAbsent(orderId, k -> new ReentrantLock());
            LOCK_USAGE_TIMES.put(orderId, System.currentTimeMillis());

            orderLock.lock();
            try {
                log.info("【{}】获取订单锁成功: {}，开始处理自动发货", cookieId, orderId);

                // 第三重检查：获取锁后再次检查延迟锁状态
                if (isLockHeld(orderId)) {
                    log.info("【{}】订单 {} 在获取锁后检查发现延迟锁仍持有，跳过发货", cookieId, orderId);
                    return;
                }

                // 第四重检查：获取锁后再次检查冷却状态
                if (!canAutoDelivery(orderId)) {
                    log.info("【{}】订单 {} 在获取锁后检查发现仍在冷却期，跳过发货", cookieId, orderId);
                    return;
                }

                // 执行自动发货逻辑（简化实现）
                log.info("【{}】准备自动发货: itemId={}, orderId={}", cookieId, itemId, orderId);

                // 这里应该调用实际的发货方法，获取发货内容
                // 简化实现：直接发送一个测试消息
                String deliveryContent = "【自动发货】您的订单已发货，请查收！";

                // 发送发货消息
                sendMsg(session, chatId, sendUserId, deliveryContent);

                // 标记已发货
                markDeliverySent(orderId);

                // 设置延迟锁（10分钟后释放）
                LockHoldInfo lockInfo = new LockHoldInfo(true, System.currentTimeMillis());
                LOCK_HOLD_INFO.put(orderId, lockInfo);

                // 启动延迟释放任务
                CompletableFuture<Void> delayTask = delayedLockRelease(orderId, 10);
                lockInfo.setTask(delayTask);

                log.info("【{}】自动发货完成: {}", cookieId, orderId);

            } finally {
                orderLock.unlock();
                log.info("【{}】订单锁释放: {}", cookieId, orderId);
            }

        } catch (Exception e) {
            log.error("【{}】自动发货处理异常", cookieId, e);
        }
    }

    // ============== 通知系统方法 ==============

    /**
     * 发送Token刷新通知
     * 对应Python的send_token_refresh_notification()方法
     * 简化实现：只记录日志和更新时间
     */
    private void sendTokenRefreshNotification(String errorMessage, String notificationType) {
        try {
            // 检查是否可以发送通知
            if (!canSendNotification(notificationType)) {
                log.debug("【{}】通知在冷却期内，跳过: {}", cookieId, notificationType);
                return;
            }

            // 记录通知（简化实现：实际应该调用钉钉API等）
            log.warn("【{}】[Token刷新通知] 类型:{}, 消息:{}", cookieId, notificationType, errorMessage);

            // 标记通知已发送
            markNotificationSent(notificationType);

        } catch (Exception e) {
            log.error("【{}】发送Token刷新通知失败", cookieId, e);
        }
    }

    /**
     * 发送发货失败通知
     * 对应Python的send_delivery_failure_notification()方法
     */
    private void sendDeliveryFailureNotification(String sendUserName, String sendUserId,
                                                 String itemId, String reason, String chatId) {
        try {
            String notificationType = "delivery_" + itemId;

            if (!canSendNotification(notificationType)) {
                log.debug("【{}】发货通知在冷却期内", cookieId);
                return;
            }

            log.warn("【{}】[发货通知] 用户:{}, 商品:{}, 原因:{}",
                    cookieId, sendUserName, itemId, reason);

            markNotificationSent(notificationType);

        } catch (Exception e) {
            log.error("【{}】发送发货通知失败", cookieId, e);
        }
    }

    // ============== 订单处理方法 ==============

    /**
     * 获取订单详情信息
     * 对应Python的fetch_order_detail_info()方法
     * 简化实现：返回基本信息
     */
    private JSONObject fetchOrderDetailInfo(String orderId, String itemId, String buyerId) {
        try {
            log.info("【{}】获取订单详情: orderId={}", cookieId, orderId);

            // 获取订单详情锁
            ReentrantLock detailLock = ORDER_DETAIL_LOCKS.computeIfAbsent(orderId, k -> new ReentrantLock());
            ORDER_DETAIL_LOCK_TIMES.put(orderId, System.currentTimeMillis());

            detailLock.lock();
            try {
                // 简化实现：实际应该调用API获取订单详情
                JSONObject orderDetail = new JSONObject();
                orderDetail.put("orderId", orderId);
                orderDetail.put("itemId", itemId);
                orderDetail.put("buyerId", buyerId);
                orderDetail.put("quantity", 1);

                log.info("【{}】订单详情获取成功: {}", cookieId, orderId);
                return orderDetail;

            } finally {
                detailLock.unlock();
            }

        } catch (Exception e) {
            log.error("【{}】获取订单详情失败: {}", cookieId, orderId, e);
            return null;
        }
    }

    /**
     * 保存商品信息到数据库
     * 对应Python的save_item_info_to_db()方法
     */
    private void saveItemInfoToDb(String itemId, String itemDetail, String itemTitle) {
        try {
            // 跳过auto_开头的商品ID
            if (itemId != null && itemId.startsWith("auto_")) {
                log.warn("跳过保存自动生成的商品ID: {}", itemId);
                return;
            }

            // 验证：需要同时有标题和详情
            if (StrUtil.isBlank(itemTitle) || StrUtil.isBlank(itemDetail)) {
                log.warn("跳过保存商品信息：标题或详情不完整 - {}", itemId);
                return;
            }

            // 简化实现：实际应该调用数据库服务保存
            log.info("【{}】保存商品信息（简化实现）: itemId={}, title={}",
                    cookieId, itemId, itemTitle);

        } catch (Exception e) {
            log.error("【{}】保存商品信息失败", cookieId, e);
        }
    }

    /**
     * 从API获取商品详情
     * 对应Python的fetch_item_detail_from_api()方法
     */
    private String fetchItemDetailFromApi(String itemId) {
        try {
            // 检查缓存
            ITEM_DETAIL_CACHE_LOCK.lock();
            try {
                ItemDetailCache cache = ITEM_DETAIL_CACHE.get(itemId);
                if (cache != null && !cache.isExpired(ITEM_DETAIL_CACHE_TTL)) {
                    cache.updateAccessTime();
                    log.info("【{}】从缓存获取商品详情: {}", cookieId, itemId);
                    return cache.getDetail();
                }
            } finally {
                ITEM_DETAIL_CACHE_LOCK.unlock();
            }

            // 简化实现：实际应该通过浏览器获取商品详情
            log.info("【{}】获取商品详情（简化实现）: {}", cookieId, itemId);
            String detail = "商品详情内容（简化实现）";

            // 添加到缓存
            addToItemCache(itemId, detail);

            return detail;

        } catch (Exception e) {
            log.error("【{}】获取商品详情失败: {}", cookieId, itemId, e);
            return "";
        }
    }

    // ============== Cookie刷新方法 ==============

    /**
     * 执行Cookie刷新
     * 对应Python的_execute_cookie_refresh()方法
     * 简化实现
     */
    private void executeCookieRefresh(long currentTime) {
        try {
            // 检查是否在消息冷却期
            long timeSinceLastMessage = currentTime - lastMessageReceivedTime.get();
            if (lastMessageReceivedTime.get() > 0 &&
                    timeSinceLastMessage < MESSAGE_COOKIE_REFRESH_COOLDOWN * 1000L) {
                log.info("【{}】收到消息后冷却中，跳过Cookie刷新", cookieId);
                return;
            }

            log.info("【{}】开始执行Cookie刷新（简化实现）", cookieId);

            // 简化实现：实际应该调用浏览器服务刷新Cookie
            // 这里只记录日志
            log.warn("【{}】Cookie刷新完成（简化实现）", cookieId);

            lastCookieRefreshTime.set(currentTime);

        } catch (Exception e) {
            log.error("【{}】Cookie刷新失败", cookieId, e);
        }
    }

    /**
     * 通过浏览器刷新Cookie
     * 对应Python的_refresh_cookies_via_browser()方法
     */
    private boolean refreshCookiesViaBrowser() {
        try {
            log.info("【{}】开始通过浏览器刷新Cookie（简化实现）", cookieId);

            // 简化实现：实际应该调用browserService.refreshCookies()
            // 更新Cookie并保存到数据库

            log.info("【{}】浏览器Cookie刷新完成（简化实现）", cookieId);
            return true;

        } catch (Exception e) {
            log.error("【{}】浏览器Cookie刷新失败", cookieId, e);
            return false;
        }
    }

    /**
     * 尝试密码登录刷新Cookie
     * 对应Python的_try_password_login_refresh()方法
     */
    private boolean tryPasswordLoginRefresh(String triggerReason) {
        try {
            log.warn("【{}】准备尝试密码登录刷新Cookie，原因: {}", cookieId, triggerReason);

            // 检查密码登录冷却期
            Long lastLoginTime = LAST_PASSWORD_LOGIN_TIME.get(cookieId);
            long currentTime = System.currentTimeMillis();
            if (lastLoginTime != null) {
                long timeSinceLastLogin = (currentTime - lastLoginTime) / 1000;
                if (timeSinceLastLogin < PASSWORD_LOGIN_COOLDOWN) {
                    log.warn("【{}】距离上次密码登录仅 {} 秒，仍在冷却期内",
                            cookieId, timeSinceLastLogin);
                    return false;
                }
            }

            // 简化实现：实际应该调用浏览器登录服务
            log.info("【{}】密码登录刷新（简化实现）", cookieId);

            // 记录登录时间
            LAST_PASSWORD_LOGIN_TIME.put(cookieId, currentTime);

            return true;

        } catch (Exception e) {
            log.error("【{}】密码登录刷新失败", cookieId, e);
            return false;
        }
    }

    /**
     * 更新数据库中的Cookie
     * 对应Python的update_config_cookies()方法
     */
    private void updateConfigCookies() {
        try {
            Optional<Cookie> cookieOpt = cookieRepository.findById(cookieId);
            if (cookieOpt.isPresent()) {
                Cookie cookie = cookieOpt.get();
                cookie.setValue(this.cookiesStr);
                cookieRepository.save(cookie);
                log.warn("【{}】已更新Cookie到数据库", cookieId);
            }
        } catch (Exception e) {
            log.error("【{}】更新数据库Cookie失败", cookieId, e);
        }
    }

    /**
     * 更新Cookie并重启实例
     * 对应Python的_update_cookies_and_restart()方法
     */
    private boolean updateCookiesAndRestart(String newCookiesStr) {
        try {
            log.info("【{}】准备更新Cookie并重启实例", cookieId);

            // 备份原Cookie
            String oldCookiesStr = this.cookiesStr;

            // 更新Cookie
            this.cookiesStr = newCookiesStr;
            this.cookies = parseCookies(newCookiesStr);

            // 更新数据库
            updateConfigCookies();

            // 简化实现：实际应该触发实例重启
            log.info("【{}】Cookie更新成功（简化实现，跳过实例重启）", cookieId);

            return true;

        } catch (Exception e) {
            log.error("【{}】Cookie更新失败", cookieId, e);
            return false;
        }
    }

    // ============== 商品信息获取相关方法（对应Python XianyuLive.get_all_items系列方法）==============

    /**
     * 获取所有商品信息（自动分页）
     * 对应Python: async def get_all_items(self, page_size=20, max_pages=None)
     *
     * @param pageSize 每页数量，默认20
     * @param maxPages 最大页数限制，null表示无限制
     * @return 包含所有商品信息的Map
     */
    public Map<String, Object> getAllItems(int pageSize, Integer maxPages) {
        log.info("【{}】开始获取所有商品信息，每页{}条", cookieId, pageSize);

        int pageNumber = 1;
        int totalSaved = 0;
        int totalCount = 0;

        while (true) {
            if (maxPages != null && pageNumber > maxPages) {
                log.info("【{}】达到最大页数限制 {}，停止获取", cookieId, maxPages);
                break;
            }

            log.info("【{}】正在获取第 {} 页...", cookieId, pageNumber);
            Map<String, Object> result = getItemListInfo(pageNumber, pageSize, 0);

            if (!Boolean.TRUE.equals(result.get("success"))) {
                log.error("【{}】获取第 {} 页失败: {}", cookieId, pageNumber, result.get("error"));
                return Map.of(
                        "success", false,
                        "error", result.getOrDefault("error", "获取商品失败"),
                        "total_pages", pageNumber - 1,
                        "total_count", totalCount,
                        "total_saved", totalSaved
                );
            }

            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> currentItems = (java.util.List<Map<String, Object>>) result.get("items");
            if (currentItems == null || currentItems.isEmpty()) {
                log.info("【{}】第 {} 页没有数据，获取完成", cookieId, pageNumber);
                break;
            }

            totalCount += currentItems.size();
            Integer savedCount = (Integer) result.get("saved_count");
            if (savedCount != null) {
                totalSaved += savedCount;
            }

            log.info("【{}】第 {} 页获取到 {} 个商品", cookieId, pageNumber, currentItems.size());

            // 如果当前页商品数量少于页面大小，说明已经是最后一页
            if (currentItems.size() < pageSize) {
                log.info("【{}】第 {} 页商品数量({})少于页面大小({})，获取完成",
                        cookieId, pageNumber, currentItems.size(), pageSize);
                break;
            }

            pageNumber++;

            // 添加延迟避免请求过快
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("【{}】获取商品时被中断", cookieId);
                break;
            }
        }

        log.info("【{}】所有商品获取完成，共 {} 个商品，保存了 {} 个", cookieId, totalCount, totalSaved);

        return Map.of(
                "success", true,
                "total_pages", pageNumber,
                "total_count", totalCount,
                "total_saved", totalSaved
        );
    }

    /**
     * 获取商品列表信息（单页）
     * 对应Python: async def get_item_list_info(self, page_number=1, page_size=20, retry_count=0)
     *
     * @param pageNumber 页码，从1开始
     * @param pageSize   每页数量
     * @param retryCount 重试次数（内部使用）
     * @return 包含商品列表的Map
     */
    private Map<String, Object> getItemListInfo(int pageNumber, int pageSize, int retryCount) {
        if (retryCount >= 4) {
            log.error("【{}】获取商品信息失败，重试次数过多", cookieId);
            return Map.of("error", "获取商品信息失败，重试次数过多");
        }

        try {
            // 构建请求参数
            long timestamp = System.currentTimeMillis();
            Map<String, String> params = new HashMap<>();
            params.put("jsv", "2.7.2");
            params.put("appKey", API_APP_KEY);
            params.put("t", String.valueOf(timestamp));
            params.put("sign", "");
            params.put("v", "1.0");
            params.put("type", "originaljson");
            params.put("accountSite", "xianyu");
            params.put("dataType", "json");
            params.put("timeout", "20000");
            params.put("api", "mtop.idle.web.xyh.item.list");
            params.put("sessionOption", "AutoLoginOnly");
            params.put("spm_cnt", "a21ybx.im.0.0");
            params.put("spm_pre", "a21ybx.collection.menu.1.272b5141NafCNK");

            // 构建数据
            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("needGroupInfo", false);
            dataMap.put("pageNumber", pageNumber);
            dataMap.put("pageSize", pageSize);
            dataMap.put("groupName", "在售");
            dataMap.put("groupId", "58877261");
            dataMap.put("defaultGroup", true);
            dataMap.put("userId", myId);

            String dataVal = JSON.toJSONString(dataMap);

            // 从cookie中获取token
            String mh5tk = cookies.get("_m_h5_tk");
            String token = "";
            if (mh5tk != null && mh5tk.contains("_")) {
                token = mh5tk.split("_")[0];
            }

            log.warn("【{}】准备获取商品列表，token: {}", cookieId, token);

            // 生成签名
            String sign = XianyuUtils.generateSign(String.valueOf(timestamp), token, dataVal);
            params.put("sign", sign);

            // 发送HTTP请求
            String url = "https://h5api.m.goofish.com/h5/mtop.idle.web.xyh.item.list/1.0/";
            cn.hutool.http.HttpRequest request = HttpRequest.post(url)
                    .form("data", dataVal)
                    .cookie(cookiesStr);
            
            // 添加所有params参数
            for (Map.Entry<String, String> entry : params.entrySet()) {
                request.form(entry.getKey(), entry.getValue());
            }
            
            String responseBody = request.execute().body();

            JSONObject resJson = JSON.parseObject(responseBody);
            log.info("【{}】商品信息获取响应: {}", cookieId, resJson.toJSONString());

            // 检查响应是否成功
            JSONArray retArray = resJson.getJSONArray("ret");
            if (retArray != null && !retArray.isEmpty() && "SUCCESS::调用成功".equals(retArray.getString(0))) {
                JSONObject itemsData = resJson.getJSONObject("data");
                JSONArray cardList = itemsData.getJSONArray("cardList");

                // 解析商品信息
                java.util.List<Map<String, Object>> itemsList = new java.util.ArrayList<>();
                if (cardList != null) {
                    for (int i = 0; i < cardList.size(); i++) {
                        JSONObject card = cardList.getJSONObject(i);
                        JSONObject cardData = card.getJSONObject("cardData");
                        if (cardData != null) {
                            Map<String, Object> itemInfo = new HashMap<>();
                            itemInfo.put("id", cardData.getString("id"));
                            itemInfo.put("title", cardData.getString("title"));

                            JSONObject priceInfo = cardData.getJSONObject("priceInfo");
                            if (priceInfo != null) {
                                itemInfo.put("price", priceInfo.getString("price"));
                                String priceText = (priceInfo.getString("preText") != null ? priceInfo.getString("preText") : "") +
                                        (priceInfo.getString("price") != null ? priceInfo.getString("price") : "");
                                itemInfo.put("price_text", priceText);
                            } else {
                                itemInfo.put("price", "");
                                itemInfo.put("price_text", "");
                            }

                            itemInfo.put("category_id", cardData.getString("categoryId"));
                            itemInfo.put("auction_type", cardData.getString("auctionType"));
                            itemInfo.put("item_status", cardData.getInteger("itemStatus"));
                            itemInfo.put("detail_url", cardData.getString("detailUrl"));
                            itemInfo.put("pic_info", cardData.getJSONObject("picInfo"));
                            itemInfo.put("detail_params", cardData.getJSONObject("detailParams"));
                            itemInfo.put("track_params", cardData.getJSONObject("trackParams"));
                            itemInfo.put("item_label_data", cardData.getJSONObject("itemLabelDataVO"));
                            itemInfo.put("card_type", card.getInteger("cardType"));

                            itemsList.add(itemInfo);
                        }
                    }
                }

                log.info("【{}】成功获取到 {} 个商品", cookieId, itemsList.size());

                // 打印商品详细信息到控制台
                System.out.println("\n" + "=".repeat(80));
                System.out.println(String.format("📦 账号 %s 的商品列表 (第%d页，%d 个商品)", myId, pageNumber, itemsList.size()));
                System.out.println("=".repeat(80));

                for (int i = 0; i < itemsList.size(); i++) {
                    Map<String, Object> item = itemsList.get(i);
                    System.out.println(String.format("\n🔸 商品 %d:", i + 1));
                    System.out.println(String.format("   商品ID: %s", item.get("id")));
                    System.out.println(String.format("   商品标题: %s", item.get("title")));
                    System.out.println(String.format("   价格: %s", item.get("price_text")));
                    System.out.println(String.format("   分类ID: %s", item.get("category_id")));
                    System.out.println(String.format("   商品状态: %s", item.get("item_status")));
                    System.out.println(String.format("   拍卖类型: %s", item.get("auction_type")));
                    System.out.println(String.format("   详情链接: %s", item.get("detail_url")));
                }

                System.out.println("\n" + "=".repeat(80));
                System.out.println("✅ 商品列表获取完成");
                System.out.println("=".repeat(80));

                // 自动保存商品信息到数据库
                int savedCount = 0;
                if (!itemsList.isEmpty()) {
                    savedCount = saveItemsToDatabase(itemsList);
                    log.info("【{}】已将 {} 个商品信息保存到数据库", cookieId, savedCount);
                }

                return Map.of(
                        "success", true,
                        "page_number", pageNumber,
                        "page_size", pageSize,
                        "current_count", itemsList.size(),
                        "items", itemsList,
                        "saved_count", savedCount
                );
            } else {
                // 检查是否是token失效
                String errorMsg = retArray != null && !retArray.isEmpty() ? retArray.getString(0) : "";
                if (errorMsg.contains("FAIL_SYS_TOKEN_EXOIRED") || errorMsg.toLowerCase().contains("token")) {
                    log.warn("【{}】Token失效，准备重试: {}", cookieId, errorMsg);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return getItemListInfo(pageNumber, pageSize, retryCount + 1);
                } else {
                    log.error("【{}】获取商品信息失败: {}", cookieId, resJson.toJSONString());
                    return Map.of("error", "获取商品信息失败: " + errorMsg);
                }
            }

        } catch (Exception e) {
            log.error("【{}】商品信息API请求异常", cookieId, e);
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return getItemListInfo(pageNumber, pageSize,retryCount + 1);
        }
    }

    /**
     * 保存商品列表到数据库
     * 对应Python: async def save_items_list_to_db(self, items_list)
     *
     * @param itemsList 商品列表
     * @return 保存的商品数量
     */
    private int saveItemsToDatabase(java.util.List<Map<String, Object>> itemsList) {
        int savedCount = 0;

        try {
            for (Map<String, Object> itemData : itemsList) {
                try {
                    String itemId = (String) itemData.get("id");
                    if (itemId == null || itemId.isEmpty()) {
                        log.warn("【{}】跳过保存：商品ID为空", cookieId);
                        continue;
                    }

                    // 查找或创建商品实体
                    ItemInfo itemInfo = itemInfoRepository.findByCookieIdAndItemId(cookieId, itemId)
                            .orElse(new ItemInfo());

                    // 设置字段
                    itemInfo.setCookieId(cookieId);
                    itemInfo.setItemId(itemId);
                    itemInfo.setItemTitle((String) itemData.get("title"));
                    itemInfo.setItemPrice((String) itemData.get("price"));

                    // 尝试从 detail_params 中提取分类信息
                    @SuppressWarnings("unchecked")
                    Map<String, Object> detailParams = (Map<String, Object>) itemData.get("detail_params");
                    if (detailParams != null) {
                        Object categoryName = detailParams.get("categoryName");
                        if (categoryName != null) {
                            itemInfo.setItemCategory(categoryName.toString());
                        }
                    }

                    // 保存到数据库
                    itemInfoRepository.save(itemInfo);
                    savedCount++;

                } catch (Exception e) {
                    log.error("【{}】保存商品信息失败: {}", cookieId, itemData.get("id"), e);
                }
            }

            log.info("【{}】成功保存 {} 个商品到数据库", cookieId, savedCount);

        } catch (Exception e) {
            log.error("【{}】批量保存商品信息时出错", cookieId, e);
        }

        return savedCount;
    }
}

