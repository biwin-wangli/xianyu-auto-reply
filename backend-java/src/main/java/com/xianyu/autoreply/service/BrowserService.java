package com.xianyu.autoreply.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.utils.BrowserStealth;
import com.xianyu.autoreply.utils.BrowserTrajectoryUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class BrowserService {

    private final CookieRepository cookieRepository;
    private final ResourceUrlProvider resourceUrlProvider;
    private Playwright playwright;
    private Browser browser;

    // 为每个账号维护持久化浏览器上下文（用于Cookie刷新）
    private final Map<String, BrowserContext> persistentContexts = new ConcurrentHashMap<>();

    // 为每个账号维护同步锁，防止并发创建持久化上下文
    private final Map<String, Object> contextLocks = new ConcurrentHashMap<>();

    @Autowired
    public BrowserService(CookieRepository cookieRepository, ResourceUrlProvider resourceUrlProvider) {
        this.cookieRepository = cookieRepository;
        this.resourceUrlProvider = resourceUrlProvider;
    }

    @PostConstruct
    private void initPlaywright() {
        try {
            log.info("Initializing Playwright...");
            playwright = Playwright.create();
            log.info("Playwright created.");

            // Initialize global browser for refreshCookies usages
            List<String> args = new ArrayList<>();
            args.add("--no-sandbox");
            args.add("--disable-setuid-sandbox");
            args.add("--disable-dev-shm-usage");
            args.add("--disable-gpu");
            args.add("--no-first-run");
            args.add("--disable-extensions");
            args.add("--mute-audio");
            args.add("--disable-blink-features=AutomationControlled");

            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setArgs(args);

            String osName = System.getProperty("os.name").toLowerCase();
            String osArch = System.getProperty("os.arch").toLowerCase();
            if (osName.contains("mac") && osArch.contains("aarch64")) {
                Path chromePath = Paths.get("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
                if (chromePath.toFile().exists()) {
                    launchOptions.setExecutablePath(chromePath);
                }
            }
            browser = playwright.chromium().launch(launchOptions);

        } catch (Exception e) {
            log.error("Failed to initialize Playwright", e);
            throw new RuntimeException("Failed to initialize Playwright", e);
        }
    }

    @PreDestroy
    private void close() {
        log.info("Releasing Playwright resources...");

        // 关闭所有持久化上下文
        closeAllPersistentContexts();

        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        log.info("Playwright resources released.");
    }

    /**
     * 获取共享的Browser实例
     * 供CaptchaHandler等服务复用，避免多实例冲突
     */
    public Browser getSharedBrowser() {
        if (browser == null) {
            throw new IllegalStateException("Browser尚未初始化，请检查Playwright初始化逻辑");
        }
        return browser;
    }

    // ---------------- Password Login Logic ----------------

    private final Map<String, Map<String, Object>> passwordLoginSessions = new ConcurrentHashMap<>();

    public String startPasswordLogin(String accountId, String account, String password, boolean showBrowser, Long userId) {
        String sessionId = UUID.randomUUID().toString();
        Map<String, Object> sessionData = new ConcurrentHashMap<>();
        sessionData.put("status", "running");
        sessionData.put("message", "正在初始化浏览器...");
        passwordLoginSessions.put(sessionId, sessionData);

        CompletableFuture.runAsync(() -> processPasswordLogin(sessionId, accountId, account, password, showBrowser, userId));

        return sessionId;
    }

    public Map<String, Object> checkPasswordLoginStatus(String sessionId) {
        return passwordLoginSessions.getOrDefault(sessionId, Map.of("status", "unknown", "message", "任务不存在"));
    }

    private void processPasswordLogin(String sessionId, String accountId, String account, String password, boolean showBrowser, Long userId) {
        Map<String, Object> session = passwordLoginSessions.get(sessionId);
        BrowserContext context = null;
        try {
            log.info("【Login Task】Starting password login for session: {}, accountId: {}, showBrowser: {}", sessionId, accountId, showBrowser);
            session.put("message", "正在启动浏览器...");

            String userDataDir = "browser_data/user_" + accountId;
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(userDataDir));

            List<String> args = new ArrayList<>();
            args.add("--no-sandbox");
            args.add("--disable-setuid-sandbox");
            args.add("--disable-dev-shm-usage");
            args.add("--disable-blink-features=AutomationControlled");
            args.add("--disable-web-security");
            args.add("--disable-features=VizDisplayCompositor");
            args.add("--lang=zh-CN");
            args.add("--start-maximized");

            BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                    .setHeadless(!showBrowser)
                    .setArgs(args)
//                    .setViewportSize(1920, 1080)
                    // Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                    .setLocale("zh-CN")
                    .setAcceptDownloads(true)
                    .setIgnoreHTTPSErrors(true);

            String osName = System.getProperty("os.name").toLowerCase();
            String osArch = System.getProperty("os.arch").toLowerCase();
            if (osName.contains("mac") && osArch.contains("aarch64")) {
                Path chromePath = Paths.get("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
                if (chromePath.toFile().exists()) {
                    options.setExecutablePath(chromePath);
                }
            }

            log.info("【Login Task】Launching browser context with userDataDir: {}", userDataDir);
            context = playwright.chromium().launchPersistentContext(java.nio.file.Paths.get(userDataDir), options);

            Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);

            page.addInitScript(BrowserStealth.STEALTH_SCRIPT);

            session.put("message", "正在导航至登录页...");
            log.info("【Login Task】Navigating to https://www.goofish.com/im");
            page.navigate("https://www.goofish.com/im");

            // Wait for network idle to ensure frames loaded
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10000));
            } catch (Exception e) {
                log.warn("【Login Task】Network idle timeout, proceeding...");
            }
            Thread.sleep(2000);

            // 1. Check if already logged in
            if (checkLoginSuccessByElement(page)) {
                log.info("【Login Task】Already logged in detected immediately.");
                handleLoginSuccess(page, context, accountId, account, password, showBrowser, userId, session);
                return;
            }

            session.put("message", "正在查找登录表单...");
            log.info("【Login Task】Searching for login frame...");

            // 2. Robust Frame Search (Main Page OR Frames)
            Frame loginFrame = findLoginFrame(page);

            // Retry logic for finding frame
            if (loginFrame == null) {
                log.info("【Login Task】Login frame not found, waiting 3s and retrying...");
                Thread.sleep(3000); // Wait more
                loginFrame = findLoginFrame(page);
            }

            if (loginFrame != null) {
                log.info("【Login Task】Found login form in frame: {}", loginFrame.url());
                // Switch to password login
                try {
                    ElementHandle switchLink = loginFrame.querySelector("i.iconfont.icon-mimadenglu");
                    // Sometimes selector is a.password-login-tab-item
                    if (switchLink == null || !switchLink.isVisible()) {
                        switchLink = loginFrame.querySelector("a.password-login-tab-item");
                    }

                    if (switchLink != null && switchLink.isVisible()) {
                        log.info("【Login Task】Clicking password switch link...");
                        switchLink.click();
                        Thread.sleep(1000);
                    } else {
                        log.info("【Login Task】Password switch link not found or not visible, assuming possibly already on password tab or different layout.");
                    }
                } catch (Exception e) {
                    log.warn("【Login Task】Error switching to password tab: {}", e.getMessage());
                }

                session.put("message", "正在输入账号密码...");
                log.info("【Login Task】Inputting credentials for user: {}", account);

                // Clear and Fill with human delay
                loginFrame.fill("#fm-login-id", "");
                Thread.sleep(200);
                loginFrame.type("#fm-login-id", account, new Frame.TypeOptions().setDelay(100)); // Type like human

                Thread.sleep(500 + new Random().nextInt(500));

                loginFrame.fill("#fm-login-password", "");
                Thread.sleep(200);
                loginFrame.type("#fm-login-password", password, new Frame.TypeOptions().setDelay(100));

                Thread.sleep(500 + new Random().nextInt(500));

                try {
                    ElementHandle agreement = loginFrame.querySelector("#fm-agreement-checkbox");
                    if (agreement != null && !agreement.isChecked()) {
                        log.info("【Login Task】Checking agreement checkbox...");
                        agreement.click();
                    }
                } catch (Exception e) {
                }

                session.put("message", "正在点击登录...");
                log.info("【Login Task】Clicking submit button...");
                loginFrame.click("button.fm-button.fm-submit.password-login");
                Thread.sleep(3000);
            } else {
                if (checkLoginSuccessByElement(page)) {
                    log.info("【Login Task】Login frame not found but seems logged in.");
                    handleLoginSuccess(page, context, accountId, account, password, showBrowser, userId, session);
                    return;
                }
                log.error("【Login Task】Login form NOT found after retries. Page title: {}, URL: {}", page.title(), page.url());
                session.put("status", "failed");
                session.put("message", "无法找到登录框 (URL: " + page.url() + ")");
                // Capture screenshot for debugging if possible? (not easy to send back via session map safely)
                return;
            }

            // Post-login / Slider Loop
            session.put("message", "正在检测登录状态与滑块...");
            log.info("【Login Task】Entering post-submission monitor loop...");

            long startTime = System.currentTimeMillis();
            long maxWaitTime = 450 * 1000L;
            if (!showBrowser) maxWaitTime = 60 * 1000L;

            boolean success = false;

            while (System.currentTimeMillis() - startTime < maxWaitTime) {
                if (checkLoginSuccessByElement(page)) {
                    log.info("【Login Task】Login Success Detected!");
                    success = true;
                    break;
                }

                boolean sliderFound = solveSliderRecursively(page);
                if (sliderFound) {
                    session.put("message", "正在处理滑块验证...");
                    log.info("【Login Task】Slider solved, page reloading for check...");
                    Thread.sleep(3000);
                    page.reload();
                    Thread.sleep(2000);
                    continue;
                }

                String content = page.content();
                if (content.contains("验证") || content.contains("安全检测") || content.contains("security-check")) {
                    log.warn("【Login Task】Security verification required (SMS/Face).");
                    session.put("status", "verification_required");
                    session.put("message", "需要二次验证(短信/人脸)，请手动在浏览器中完成");
                    if (!showBrowser) {
                        session.put("status", "failed");
                        session.put("message", "需要验证但处于无头模式，无法手动处理");
                        return;
                    }
                }

                if (content.contains("账号名或登录密码不正确") || content.contains("账密错误")) {
                    log.error("【Login Task】Invalid credentials detected.");
                    session.put("status", "failed");
                    session.put("message", "账号名或登录密码不正确");
                    return;
                }

                Thread.sleep(2000);
            }

            if (success) {
                handleLoginSuccess(page, context, accountId, account, password, showBrowser, userId, session);
            } else {
                log.error("【Login Task】Timeout waiting for login success.");
                session.put("status", "failed");
                session.put("message", "登录超时或失败");
            }

        } catch (Exception e) {
            log.error("【Login Task】Password login exception", e);
            session.put("status", "failed");
            session.put("message", "异常: " + e.getMessage());
        } finally {
            if (context != null) {
                log.info("【Login Task】Closing browser context.");
                context.close();
            }
        }
    }

    // Updated robust findFrame logic matching Python's selector list
    private Frame findLoginFrame(Page page) {
        String[] selectors = {
                "#fm-login-id",
                "input[name='fm-login-id']",
                "input[placeholder*='手机号']",
                "input[placeholder*='邮箱']",
                ".fm-login-id",
                "#J_LoginForm input[type='text']"
        };

        // 1. Check Main Frame First
        for (String s : selectors) {
            try {
                if (page.isVisible(s)) {
                    log.info("【Login Task】Found login element in Main Frame: {}", s);
                    return page.mainFrame();
                }
                if (page.querySelector(s) != null) { // Fallback check availability even if not visible yet
                    log.info("【Login Task】Found login element in Main Frame (hidden?): {}", s);
                    return page.mainFrame();
                }
            } catch (Exception e) {
            }
        }

        // 2. Check All Frames
        for (Frame frame : page.frames()) {
            for (String s : selectors) {
                try {
                    if (frame.isVisible(s)) {
                        log.info("【Login Task】Found login element in Frame ({}): {}", frame.url(), s);
                        return frame;
                    }
                    if (frame.querySelector(s) != null) {
                        log.info("【Login Task】Found login element in Frame ({}) (hidden?): {}", frame.url(), s);
                        return frame;
                    }
                } catch (Exception e) {
                }
            }
        }

        return null;
    }

    private boolean checkLoginSuccessByElement(Page page) {
        try {
            ElementHandle element = page.querySelector(".rc-virtual-list-holder-inner");
            if (element != null && element.isVisible()) {
                log.info("【Login Task】Success Indicator Found: .rc-virtual-list-holder-inner");
                Object childrenCount = element.evaluate("el => el.children.length");
                if (childrenCount instanceof Number && ((Number) childrenCount).intValue() > 0) {
                    return true;
                }
                return true;
            }
            if (page.url().contains("goofish.com/im") && page.querySelector("#fm-login-id") == null) {
                log.info("【Login Task】Success Indicator: On IM page and login input is gone.");
                return false;
            }
        } catch (Exception e) {
            log.trace("【Login Task】Error checking login success element", e);
        }
        return false;
    }

    private void handleLoginSuccess(Page page, BrowserContext context, String accountId, String account, String password, boolean showBrowser, Long userId, Map<String, Object> session) {
        session.put("message", "登录成功，正在获取Cookie...");
        log.info("【Login Task】Login Success confirmed. Extracting cookies...");

        List<com.microsoft.playwright.options.Cookie> cookies = new ArrayList<>();
        int retries = 10;
        boolean unbFound = false;

        while (retries-- > 0) {
            cookies = context.cookies();
            unbFound = cookies.stream().anyMatch(c -> "unb".equals(c.name) && c.value != null && !c.value.isEmpty());
            if (unbFound) {
                log.info("【Login Task】Crucial 'unb' cookie found!");
                break;
            }
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
            }
        }

        if (!unbFound) {
            log.warn("【Login Task】Login seemed successful but 'unb' cookie missing for {}. Cookies found: {}", accountId, cookies.size());
        }

        StringBuilder sb = new StringBuilder();
        for (com.microsoft.playwright.options.Cookie c : cookies) {
            sb.append(c.name).append("=").append(c.value).append("; ");
        }
        String cookieStr = sb.toString();

        log.info("【Login Task】Total Cookies captured: {}", cookies.size());

        Cookie cookie = cookieRepository.findById(accountId).orElse(new Cookie());
        cookie.setId(accountId);
        cookie.setValue(cookieStr);
        cookie.setUsername(account);
        cookie.setPassword(password);
        cookie.setShowBrowser(showBrowser ? 1 : 0);
        cookie.setUserId(userId);
        cookie.setEnabled(true);
        cookieRepository.save(cookie);

        session.put("status", "success");
        session.put("message", "登录成功");
        session.put("username", account);
        session.put("cookies_count", cookies.size());
        log.info("【Login Task】Session completed successfully. Data saved to DB.");
    }

    private boolean solveSliderRecursively(Page page) {
        if (attemptSolveSlider(page.mainFrame())) return true;
        for (Frame frame : page.frames()) {
            if (attemptSolveSlider(frame)) return true;
        }
        return false;
    }

    private boolean attemptSolveSlider(Frame frame) {
        try {
            String[] sliderSelectors = {"#nc_1_n1z", ".nc-container", ".nc_scale", ".nc-wrapper"};
            ElementHandle sliderButton = null;

            boolean containerFound = false;
            for (String s : sliderSelectors) {
                if (frame.querySelector(s) != null && frame.isVisible(s)) {
                    containerFound = true;
                    break;
                }
            }
            if (!containerFound) return false;

            sliderButton = frame.querySelector("#nc_1_n1z");
            if (sliderButton == null) sliderButton = frame.querySelector(".nc_iconfont");

            if (sliderButton != null && sliderButton.isVisible()) {
                log.info("【Login Task】Detected slider in frame: {}", frame.url());
                BoundingBox box = sliderButton.boundingBox();
                if (box == null) return false;

                ElementHandle track = frame.querySelector("#nc_1_n1t");
                if (track == null) track = frame.querySelector(".nc_scale");
                if (track == null) return false;

                BoundingBox trackBox = track.boundingBox();
                double distance = trackBox.width - box.width;
                log.info("【Login Task】Solving Slider: distance={}", distance);

                List<BrowserTrajectoryUtils.TrajectoryPoint> trajectory =
                        BrowserTrajectoryUtils.generatePhysicsTrajectory(distance);

                double startX = box.x + box.width / 2;
                double startY = box.y + box.height / 2;

                frame.page().mouse().move(startX, startY);
                frame.page().mouse().down();

                for (BrowserTrajectoryUtils.TrajectoryPoint p : trajectory) {
                    frame.page().mouse().move(startX + p.x, startY + p.y);
                    if (p.delay > 0.001) {
                        try {
                            Thread.sleep((long) (p.delay * 1000));
                        } catch (Exception e) {
                        }
                    }
                }
                frame.page().mouse().up();

                Thread.sleep(1000);
                if (!sliderButton.isVisible()) {
                    log.info("【Login Task】Slider solved (button disappeared)!");
                    return true;
                }

                return true;
            }
        } catch (Exception e) {
            log.warn("【Login Task】Error solving slider: {}", e.getMessage());
        }
        return false;
    }

    private boolean attemptQuickLogin(Frame frame) {
        boolean containerFound = false;
        if (Objects.isNull(frame)) return containerFound;
        ElementHandle elementHandle = frame.querySelector("#alibaba-login-box");
        if (Objects.isNull(elementHandle)) return containerFound;
        Frame quickLoginFrame = elementHandle.contentFrame();
        if (Objects.isNull(quickLoginFrame)) return containerFound;
        ElementHandle loginButton = quickLoginFrame.querySelector(".fm-button.fm-submit");
        if (Objects.isNull(loginButton)) return containerFound;
        if (loginButton.isVisible()) {
            loginButton.click();
            return true;
        }
        return false;
    }

    private boolean attemptQuickLoginV2(Frame frame) {
        try {
            String[] loginButtonSelectors = {".has-login", ".cm-has-login", ".fm-btn", ".fm-button", ".fm-submit"};

            boolean containerFound = false;
            for (String s : loginButtonSelectors) {
                if (frame.querySelector(s) != null && frame.isVisible(s)) {
                    containerFound = true;
                    break;
                }
            }
            if (!containerFound) return false;

            ElementHandle loginButtonDialog = frame.querySelector(".has-login");
            if (loginButtonDialog == null) loginButtonDialog = frame.querySelector(".cm-has-login");

            if (loginButtonDialog != null && loginButtonDialog.isVisible()) {
                log.info("【Login Task】Detected quick login in frame: {}", frame.url());

                ElementHandle loginButton = frame.querySelector(".fm-button");
                if (loginButton == null) loginButton = frame.querySelector(".fm-submit");
                if (loginButton == null) return false;
                loginButton.click();
                log.info("【Login Task】quick login success!");
                return true;
            }
        } catch (Exception e) {
            log.warn("【Login Task】quick login fail : {}", e.getMessage());
        }
        return false;
    }

    /**
     * 刷新Cookie - 使用持久化浏览器上下文
     * Cookie会自动保存到UserData目录，类似真实浏览器行为
     */
    public Map<String, String> refreshCookies(String cookieId) {
        log.info("【{}-Cookie Refresh】开始刷新Cookie for id: {}", cookieId, cookieId);
        Cookie cookie = cookieRepository.findById(cookieId).orElse(null);
        if (cookie == null || cookie.getValue() == null) {
            log.error("【{}-Cookie Refresh】无法刷新，Cookie不存在: {}", cookieId, cookieId);
            return Collections.emptyMap();
        }

        Page page = null;
        try {
            // 1. 获取或创建持久化上下文（Cookie自动从UserData加载）
            BrowserContext context = getPersistentContext(cookieId);
            log.info("【{}-Cookie Refresh】已获取持久化上下文: {}", cookieId, cookieId);

            // 2. 创建新页面并访问闲鱼（增加容错处理）
            try {
                page = context.newPage();
            } catch (Exception e) {
                log.error("【{}-Cookie Refresh】创建Page失败，上下文可能已损坏，强制重建", cookieId, e);
                closeAndRemoveContext(cookieId);
                // 重新获取上下文
                context = getPersistentContext(cookieId);
                page = context.newPage();
            }

            addStealthScripts(page);

            String targetUrl = "https://www.goofish.com/im";
            log.info("【{}-Cookie Refresh】导航到: {}", cookieId, targetUrl);

            try {
                page.navigate(targetUrl, new Page.NavigateOptions()
                        .setTimeout(20000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            } catch (Exception e) {
                log.warn("【{}-Cookie Refresh】导航超时，尝试降级...", cookieId);
                try {
                    page.navigate(targetUrl, new Page.NavigateOptions()
                            .setTimeout(30000)
                            .setWaitUntil(WaitUntilState.LOAD));
                } catch (Exception ex) {
                    log.warn("【{}-Cookie Refresh】降级导航也超时，继续执行", cookieId);
                }
            }

            // 3. 等待页面加载
            try {
                Thread.sleep(5000);
            } catch (Exception e) {
            }


            // 判断是否有快捷登陆iframe
            for (Frame frame : page.frames()) {
                if (attemptQuickLogin(frame)) {
                    break;
                }
            }

            // 4. 重新加载页面以触发Cookie刷新
            log.info("【{}-Cookie Refresh】重新加载页面...", cookieId);
            try {
                page.reload(new Page.ReloadOptions()
                        .setTimeout(20000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            } catch (Exception e) {
                log.warn("【{}-Cookie Refresh】重新加载超时，继续执行", cookieId);
            }
            try {
                Thread.sleep(2000);
            } catch (Exception e) {
            }

            // 5. 获取刷新后的Cookie（从持久化上下文中获取）
            List<com.microsoft.playwright.options.Cookie> newCookies = context.cookies();
            log.info("【{}-Cookie Refresh】获取到 {} 个Cookie", cookieId, newCookies.size());

            // 6. 构建Cookie Map
            Map<String, String> newCookieMap = new HashMap<>();
            for (com.microsoft.playwright.options.Cookie c : newCookies) {
                newCookieMap.put(c.name, c.value);
            }

            // 7. 验证必要Cookie
            if (!newCookieMap.containsKey("unb")) {
                log.warn("【{}-Cookie Refresh】刷新后的Cookie缺少'unb'字段，可能已失效", cookieId);
                return Collections.emptyMap();
            }

            // 8. 构建Cookie字符串并保存到数据库
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : newCookieMap.entrySet()) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
            }
            String newCookieStr = sb.toString();

            // 9. 更新数据库
            if (!newCookieStr.equals(cookie.getValue())) {
                cookie.setValue(newCookieStr);
                log.debug("【{}】🤖刷新浏览器后获取到的 cookie 为: {}", cookieId, newCookieStr);
                cookieRepository.save(cookie);
                log.info("【{}-Cookie Refresh】✅ Cookie已更新并保存到数据库: {}", cookieId, cookieId);
            } else {
                log.info("【{}-Cookie Refresh】Cookie未变化，无需更新数据库", cookieId);
            }

            // 10. Cookie已自动保存到UserData目录（持久化）
            log.info("【{}-Cookie Refresh】✅ Cookie刷新完成（已持久化到磁盘）: {}", cookieId, cookieId);
            return newCookieMap;

        } catch (Exception e) {
            log.error("【{}-Cookie Refresh】❌ 刷新Cookie异常: {}", cookieId, cookieId, e);
            return Collections.emptyMap();
        } finally {
            // 关闭页面但保持上下文（保持持久化状态）
            if (page != null) {
                try {
                    page.close();
                    log.debug("【{}-Cookie Refresh】页面已关闭: {}", cookieId, cookieId);
                } catch (Exception e) {
                    log.error("【{}-Cookie Refresh】关闭页面失败", cookieId, e);
                }
            }
        }
    }

    /**
     * Verifies and refreshes cookies obtained from QR Login.
     * Replicates Python's refresh_cookies_from_qr_login logic.
     */
    public Map<String, String> verifyQrLoginCookies(Map<String, String> qrCookies, String accountId) {
        log.info("【QR Login】Verifying cookies for account: {}", accountId);

        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
//                .setViewportSize(1920, 1080)
        )) {

            // 1. Add Cookies
            List<com.microsoft.playwright.options.Cookie> playwrightCookies = new ArrayList<>();
            for (Map.Entry<String, String> entry : qrCookies.entrySet()) {
                playwrightCookies.add(new com.microsoft.playwright.options.Cookie(entry.getKey(), entry.getValue())
                        .setDomain(".goofish.com")
                        .setPath("/"));
            }
            context.addCookies(playwrightCookies);

            // 2. Navigate to verify
            Page page = context.newPage();
            try {
                log.info("【QR Login】Navigating to goofish.com to verify login...");
                page.navigate("https://www.goofish.com/");
                page.waitForLoadState();

                // Wait for some login indicator
                // Python uses: page.wait_for_selector(".mod-user-info", timeout=5000) or similar checks
                // Let's try to detect if we are logged in.

                // Using the selector from checkLoginSuccessByElement as a reference
                try {
                    page.waitForSelector(".rc-virtual-list-holder-inner", new Page.WaitForSelectorOptions().setTimeout(5000));
                    log.info("【QR Login】Login verification successful (found user element).");
                } catch (Exception e) {
                    log.warn("【QR Login】Could not find standard user element. Checking cookie presence again.");
                }

                // 3. Capture refreshed cookies
                List<com.microsoft.playwright.options.Cookie> freshCookies = context.cookies();
                boolean unbFound = freshCookies.stream().anyMatch(c -> "unb".equals(c.name));

                if (unbFound) {
                    log.info("【QR Login】Verification passed. UNB found. Total cookies: {}", freshCookies.size());
                    Map<String, String> resultMap = new HashMap<>();
                    for (com.microsoft.playwright.options.Cookie c : freshCookies) {
                        resultMap.put(c.name, c.value);
                    }
                    return resultMap;
                } else {
                    log.warn("【QR Login】Verification failed - UNB cookie missing after navigation.");
                }

            } catch (Exception e) {
                log.error("【QR Login】Error during browser verification navigation", e);
            }

        } catch (Exception e) {
            log.error("【QR Login】Error creating browser context for verification", e);
        }

        return null; // Failed
    }

    // ================== 持久化浏览器上下文管理 ==================

    /**
     * 获取或创建账号的持久化浏览器上下文
     * 使用持久化上下文可以将Cookie保存到磁盘，类似真实浏览器行为
     */
    private BrowserContext getPersistentContext(String cookieId) {
        // 获取或创建该账号的同步锁
        Object lock = contextLocks.computeIfAbsent(cookieId, k -> new Object());

        // 使用同步锁防止并发创建同一个上下文
        synchronized (lock) {
            return getPersistentContextInternal(cookieId);
        }
    }

    /**
     * 内部方法：实际执行获取或创建上下文的逻辑
     */
    private BrowserContext getPersistentContextInternal(String cookieId) {
        // 如果已存在，进行深度验证
        BrowserContext existingContext = persistentContexts.get(cookieId);
        if (existingContext != null) {
            try {
                // 深度验证：尝试创建临时Page测试上下文是否仍然有效
                Page testPage = existingContext.newPage();
                testPage.close();
                log.debug("【{}-Cookie Refresh】🤖持久化上下文仍然有效，复用: {}", cookieId, cookieId);
                return existingContext;
            } catch (Exception e) {
                // 上下文已失效，移除并重新创建
                log.warn("【{}-Cookie Refresh】持久化上下文已失效,强制重建: {}. 错误: {}", cookieId, cookieId, e.getMessage());
                closeAndRemoveContext(cookieId);
                // 继续创建新上下文
            }
        }

        // 创建新的持久化上下文
        try {
            String userDataDir = "browser_data/cookie_refresh/" + cookieId;
            java.nio.file.Path userDataPath = java.nio.file.Paths.get(userDataDir);

            // 确保目录存在
            java.nio.file.Files.createDirectories(userDataPath);
            log.info("【{}-Cookie Refresh】创建UserData目录: {}", cookieId, userDataDir);

            // 配置启动选项
            List<String> args = new ArrayList<>();
            args.add("--no-sandbox");
            args.add("--disable-setuid-sandbox");
            args.add("--disable-dev-shm-usage");
            args.add("--disable-gpu");
            args.add("--disable-blink-features=AutomationControlled");
            args.add("--lang=zh-CN");

            Cookie cookie = cookieRepository.findById(cookieId).orElse(null);
            BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                    .setHeadless(Objects.isNull(cookie) || !Objects.equals(cookie.getShowBrowser(), 1))
                    .setArgs(args)
//                    .setViewportSize(1920, 1080)
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                    .setLocale("zh-CN")
                    .setAcceptDownloads(false)
                    .setIgnoreHTTPSErrors(true);

            // macOS ARM架构特殊处理
            String osName = System.getProperty("os.name").toLowerCase();
            String osArch = System.getProperty("os.arch").toLowerCase();
            if (osName.contains("mac") && osArch.contains("aarch64")) {
                Path chromePath = Paths.get("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
                if (chromePath.toFile().exists()) {
                    options.setExecutablePath(chromePath);
                }
            }

            log.info("【{}-Cookie Refresh】创建持久化浏览器上下文: {}", cookieId, cookieId);
            BrowserContext context = playwright.chromium().launchPersistentContext(userDataPath, options);

            // 首次创建时，需要设置Cookie
            if (cookie != null && cookie.getValue() != null) {
                // 解析并添加Cookie
                List<com.microsoft.playwright.options.Cookie> playwrightCookies = new ArrayList<>();
                String[] parts = cookie.getValue().split(";");
                for (String part : parts) {
                    String[] kv = part.trim().split("=", 2);
                    if (kv.length == 2) {
                        playwrightCookies.add(new com.microsoft.playwright.options.Cookie(kv[0], kv[1])
                                .setDomain(".goofish.com")
                                .setPath("/"));
                    }
                }
                context.addCookies(playwrightCookies);
                log.info("【{}-Cookie Refresh】已设置初始Cookie: {} 个", cookieId, playwrightCookies.size());
            }

            // 缓存上下文
            persistentContexts.put(cookieId, context);

            return context;

        } catch (Exception e) {
            log.error("【{}-Cookie Refresh】创建持久化上下文失败: {}", cookieId, cookieId, e);
            throw new RuntimeException("创建持久化浏览器上下文失败", e);
        }
    }

    /**
     * 关闭并移除持久化上下文
     */
    private void closeAndRemoveContext(String cookieId) {
        BrowserContext ctx = persistentContexts.remove(cookieId);
        if (ctx != null) {
            try {
                ctx.close();
                log.info("【{}-Cookie Refresh】已关闭失效的持久化上下文: {}", cookieId, cookieId);
            } catch (Exception e) {
                log.warn("【{}-Cookie Refresh】关闭失效上下文时出错: {}", cookieId, cookieId, e);
            }
        }

        // 删除整个 UserData 目录，包括 SingletonLock 文件
        try {
            String userDataDir = "browser_data/cookie_refresh/" + cookieId;
            java.nio.file.Path userDataPath = java.nio.file.Paths.get(userDataDir);
            if (java.nio.file.Files.exists(userDataPath)) {
                deleteDirectory(userDataPath);
                log.info("【{}-Cookie Refresh】已删除UserData目录: {}", cookieId, userDataDir);
            }
        } catch (Exception e) {
            log.warn("【{}-Cookie Refresh】删除UserData目录失败: {}", cookieId, e.getMessage());
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(java.nio.file.Path path) throws java.io.IOException {
        if (java.nio.file.Files.isDirectory(path)) {
            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(path)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                java.nio.file.Files.delete(p);
                            } catch (java.io.IOException e) {
                                log.warn("删除文件失败: {}", p, e);
                            }
                        });
            }
        }
    }

    /**
     * 关闭指定账号的持久化上下文
     */
    public void closePersistentContext(String cookieId) {
        BrowserContext context = persistentContexts.remove(cookieId);
        if (context != null) {
            try {
                context.close();
                log.info("【{}-Cookie Refresh】已关闭持久化上下文: {}", cookieId, cookieId);
            } catch (Exception e) {
                log.error("【{}-Cookie Refresh】关闭持久化上下文失败: {}", cookieId, cookieId, e);
            }
        }
    }

    /**
     * 关闭所有持久化上下文
     */
    private void closeAllPersistentContexts() {
        log.info("【Cookie Refresh】关闭所有持久化上下文...");
        for (Map.Entry<String, BrowserContext> entry : persistentContexts.entrySet()) {
            try {
                entry.getValue().close();
                log.info("【Cookie Refresh】已关闭: {}", entry.getKey());
            } catch (Exception e) {
                log.error("【Cookie Refresh】关闭失败: {}", entry.getKey(), e);
            }
        }
        persistentContexts.clear();
    }


    private void addStealthScripts(Page page) {
        page.addInitScript(BrowserStealth.STEALTH_SCRIPT);
    }
}
