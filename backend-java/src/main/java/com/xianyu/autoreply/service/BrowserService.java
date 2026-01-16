package com.xianyu.autoreply.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.LoadState;
import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.utils.BrowserStealth;
import com.xianyu.autoreply.utils.BrowserTrajectoryUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class BrowserService {

    private final CookieRepository cookieRepository;
    private Playwright playwright;
    private Browser browser; 

    @Autowired
    public BrowserService(CookieRepository cookieRepository) {
        this.cookieRepository = cookieRepository;
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
                .setHeadless(true)
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
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        log.info("Playwright resources released.");
    }

    // ---------------- Password Login Logic ----------------

    private final Map<String, Map<String, Object>> passwordLoginSessions = new ConcurrentHashMap<>();

    public String startPasswordLogin(String accountId, String account, String password, boolean showBrowser, Long userId) {
        String sessionId = UUID.randomUUID().toString();
        Map<String, Object> sessionData = new ConcurrentHashMap<>();
        sessionData.put("status", "running");
        sessionData.put("message", "正在初始化浏览器...");
        passwordLoginSessions.put(sessionId, sessionData);

        java.util.concurrent.CompletableFuture.runAsync(() -> processPasswordLogin(sessionId, accountId, account, password, showBrowser, userId));

        return sessionId;
    }

    public Map<String, Object> checkPasswordLoginStatus(String sessionId) {
        return passwordLoginSessions.getOrDefault(sessionId, Map.of("status", "unknown", "message", "任务不存在"));
    }

    private void processPasswordLogin(String sessionId, String accountId, String account, String password, boolean showBrowser, Long userId) {
        Map<String, Object> session = passwordLoginSessions.get(sessionId);
        BrowserContext context = null;
        try {
            log.info("Starting password login for session: {}, showBrowser: {}", sessionId, showBrowser);
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
                    .setViewportSize(1920, 1080)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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
            
            context = playwright.chromium().launchPersistentContext(java.nio.file.Paths.get(userDataDir), options);
            
            Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            
            page.addInitScript(BrowserStealth.STEALTH_SCRIPT);
            
            session.put("message", "正在导航至登录页...");
            page.navigate("https://www.goofish.com/im");
            
            // Wait for network idle to ensure frames loaded
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10000));
            } catch (Exception e) {
                 log.warn("Network idle timeout, proceeding...");
            }
            Thread.sleep(2000);
            
            // 1. Check if already logged in
            if (checkLoginSuccessByElement(page)) {
                 handleLoginSuccess(page, context, accountId, account, password, showBrowser, userId, session);
                 return;
            }

            session.put("message", "正在查找登录表单...");
            
            // 2. Robust Frame Search (Main Page OR Frames)
            Frame loginFrame = findLoginFrame(page);
            
            // Retry logic for finding frame
            if (loginFrame == null) {
                log.info("Login frame not found, waiting and retrying...");
                Thread.sleep(3000); // Wait more
                loginFrame = findLoginFrame(page);
            }
            
            if (loginFrame != null) {
                log.info("Found login form in frame: {}", loginFrame.url());
                // Switch to password login
                try {
                    ElementHandle switchLink = loginFrame.querySelector("i.iconfont.icon-mimadenglu");
                    // Sometimes selector is a.password-login-tab-item
                    if (switchLink == null || !switchLink.isVisible()) {
                        switchLink = loginFrame.querySelector("a.password-login-tab-item");
                    }
                    
                    if (switchLink != null && switchLink.isVisible()) {
                         log.info("Clicking password switch link...");
                         switchLink.click();
                         Thread.sleep(1000);
                    }
                } catch (Exception e) {
                    log.warn("Error switching to password tab: {}", e.getMessage());
                }

                session.put("message", "正在输入账号密码...");
                
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
                        agreement.click();
                    }
                } catch (Exception e) {}

                session.put("message", "正在点击登录...");
                loginFrame.click("button.fm-button.fm-submit.password-login");
                Thread.sleep(3000); 
            } else {
                 if (checkLoginSuccessByElement(page)) {
                     handleLoginSuccess(page, context, accountId, account, password, showBrowser, userId, session);
                     return;
                 }
                 log.error("Login form NOT found after retries. Page title: {}, URL: {}", page.title(), page.url());
                 session.put("status", "failed");
                 session.put("message", "无法找到登录框 (URL: " + page.url() + ")");
                 // Capture screenshot for debugging if possible? (not easy to send back via session map safely)
                 return;
            }

            // Post-login / Slider Loop
            session.put("message", "正在检测登录状态与滑块...");
            
            long startTime = System.currentTimeMillis();
            long maxWaitTime = 450 * 1000L; 
            if (!showBrowser) maxWaitTime = 60 * 1000L;
            
            boolean success = false;
            
            while (System.currentTimeMillis() - startTime < maxWaitTime) {
                if (checkLoginSuccessByElement(page)) {
                    success = true;
                    break;
                }
                
                boolean sliderFound = solveSliderRecursively(page);
                if (sliderFound) {
                     session.put("message", "正在处理滑块验证...");
                     Thread.sleep(3000);
                     page.reload();
                     Thread.sleep(2000);
                     continue;
                 }

                 String content = page.content();
                 if (content.contains("验证") || content.contains("安全检测") || content.contains("security-check")) {
                     session.put("status", "verification_required");
                     session.put("message", "需要二次验证(短信/人脸)，请手动在浏览器中完成");
                     if (!showBrowser) {
                         session.put("status", "failed");
                         session.put("message", "需要验证但处于无头模式，无法手动处理");
                         return;
                     }
                 }
                 
                 if (content.contains("账号名或登录密码不正确") || content.contains("账密错误")) {
                      session.put("status", "failed");
                      session.put("message", "账号名或登录密码不正确");
                      return;
                 }

                 Thread.sleep(2000);
            }

            if (success) {
                handleLoginSuccess(page, context, accountId, account, password, showBrowser, userId, session);
            } else {
                session.put("status", "failed");
                session.put("message", "登录超时或失败");
            }

        } catch (Exception e) {
            log.error("Password login failed", e);
            session.put("status", "failed");
            session.put("message", "异常: " + e.getMessage());
        } finally {
            if (context != null) {
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
                    log.info("Found login element in Main Frame: {}", s);
                    return page.mainFrame();
                }
                if (page.querySelector(s) != null) { // Fallback check availability even if not visible yet
                     log.info("Found login element in Main Frame (hidden?): {}", s);
                     return page.mainFrame();
                }
            } catch (Exception e) {}
        }

        // 2. Check All Frames
        for (Frame frame : page.frames()) {
            for (String s : selectors) {
                try {
                     if (frame.isVisible(s)) {
                         log.info("Found login element in Frame ({}): {}", frame.url(), s);
                         return frame;
                     }
                     if (frame.querySelector(s) != null) {
                         log.info("Found login element in Frame ({}) (hidden?): {}", frame.url(), s);
                         return frame;
                     }
                } catch (Exception e) {}
            }
        }
        
        return null;
    }

    private boolean checkLoginSuccessByElement(Page page) {
        try {
            ElementHandle element = page.querySelector(".rc-virtual-list-holder-inner");
            if (element != null && element.isVisible()) {
                Object childrenCount = element.evaluate("el => el.children.length");
                if (childrenCount instanceof Number && ((Number)childrenCount).intValue() > 0) {
                    return true;
                }
                return true; 
            }
            if (page.url().contains("goofish.com/im") && page.querySelector("#fm-login-id") == null) {
                return false;
            }
        } catch (Exception e) {}
        return false;
    }
    
    private void handleLoginSuccess(Page page, BrowserContext context, String accountId, String account, String password, boolean showBrowser, Long userId, Map<String, Object> session) {
        session.put("message", "登录成功，正在获取Cookie...");
        
        List<com.microsoft.playwright.options.Cookie> cookies = new ArrayList<>();
        int retries = 10;
        boolean unbFound = false;
        
        while (retries-- > 0) {
            cookies = context.cookies();
            unbFound = cookies.stream().anyMatch(c -> "unb".equals(c.name) && c.value != null && !c.value.isEmpty());
            if (unbFound) break;
            try { Thread.sleep(1000); } catch (Exception e) {}
        }
        
        if (!unbFound) {
             log.warn("Login seemed successful but 'unb' cookie missing for {}", accountId);
        }

        StringBuilder sb = new StringBuilder();
        for (com.microsoft.playwright.options.Cookie c : cookies) {
            sb.append(c.name).append("=").append(c.value).append("; ");
        }
        String cookieStr = sb.toString();
        
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
                log.info("Detected slider in frame: {}", frame.url());
                BoundingBox box = sliderButton.boundingBox();
                if (box == null) return false;
                
                ElementHandle track = frame.querySelector("#nc_1_n1t");
                 if (track == null) track = frame.querySelector(".nc_scale");
                 if (track == null) return false;
                 
                 BoundingBox trackBox = track.boundingBox();
                 double distance = trackBox.width - box.width;
                 
                 List<BrowserTrajectoryUtils.TrajectoryPoint> trajectory = 
                     BrowserTrajectoryUtils.generatePhysicsTrajectory(distance);
                     
                 double startX = box.x + box.width / 2;
                 double startY = box.y + box.height / 2;
                 
                 frame.page().mouse().move(startX, startY);
                 frame.page().mouse().down();
                 
                 for (BrowserTrajectoryUtils.TrajectoryPoint p : trajectory) {
                     frame.page().mouse().move(startX + p.x, startY + p.y);
                     if (p.delay > 0.001) {
                        try { Thread.sleep((long)(p.delay * 1000)); } catch (Exception e) {}
                     }
                 }
                 frame.page().mouse().up();
                 
                 Thread.sleep(1000);
                 if (!sliderButton.isVisible()) return true;
                 
                 return true; 
            }
        } catch (Exception e) {
            log.warn("Error solving slider: {}", e.getMessage());
        }
        return false;
    }

    public Map<String, String> refreshCookies(String cookieId) {
        log.info("Attempting to refresh cookies for id: {}", cookieId);
        Cookie cookie = cookieRepository.findById(cookieId).orElse(null);
        if (cookie == null || cookie.getUsername() == null || cookie.getPassword() == null) {
            log.error("Cannot refresh cookies. No valid credentials found for id: {}", cookieId);
            return Collections.emptyMap();
        }

        BrowserContext context = null;
        try {
            Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setViewportSize(1920, 1080)
                .setLocale("zh-CN")
                .setTimezoneId("Asia/Shanghai");
            
            context = browser.newContext(options);
            Page page = context.newPage();
            addStealthScripts(page);
            
            page.navigate("https://login.taobao.com/member/login.jhtml");

            try {
                if (page.isVisible("i.iconfont.icon-mimadenglu")) {
                    page.click("i.iconfont.icon-mimadenglu");
                }
            } catch (Exception e) {}

            page.fill("#fm-login-id", cookie.getUsername());
            page.fill("#fm-login-password", cookie.getPassword());
            page.click("button.fm-button.fm-submit.password-login");

            try {
                page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(10000));
            } catch (Exception e) {}

            if (page.url().contains("login.taobao.com")) {
                log.error("Cookie refresh failed for {}. Still on login page.", cookieId);
                return Collections.emptyMap();
            }

            List<com.microsoft.playwright.options.Cookie> newCookies = context.cookies();
            StringBuilder sb = new StringBuilder();
            for (com.microsoft.playwright.options.Cookie c : newCookies) {
                sb.append(c.name).append("=").append(c.value).append("; ");
            }
            String cookieStr = sb.toString();
            
            cookie.setValue(cookieStr);
            cookieRepository.save(cookie);
            
            log.info("Successfully refreshed cookies for {}", cookieId);
            return Collections.emptyMap(); 

        } catch (Exception e) {
            log.error("Exception during cookie refresh for {}", cookieId, e);
            return Collections.emptyMap();
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private void addStealthScripts(Page page) {
        page.addInitScript(BrowserStealth.STEALTH_SCRIPT);
    }
}
