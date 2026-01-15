package com.xianyu.autoreply.service;

import com.microsoft.playwright.*;
import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.utils.XianyuUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class BrowserService {

    private final CookieRepository cookieRepository;
    private Playwright playwright;
    private Browser browser;
    // Map to hold contexts per user or task if needed
    private final Map<String, BrowserContext> contextMap = new ConcurrentHashMap<>();

    @Autowired
    public BrowserService(CookieRepository cookieRepository) {
        this.cookieRepository = cookieRepository;
        initPlaywright();
    }

    private void initPlaywright() {
        try {
            playwright = Playwright.create();
            List<String> args = Arrays.asList(
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--disable-dev-shm-usage",
                "--disable-accelerated-2d-canvas",
                "--no-first-run",
                "--no-zygote",
                "--disable-gpu",
                "--disable-background-timer-throttling",
                "--disable-backgrounding-occluded-windows",
                "--disable-renderer-backgrounding",
                "--disable-features=TranslateUI",
                "--disable-ipc-flooding-protection",
                "--disable-extensions",
                "--disable-default-apps",
                "--disable-sync",
                "--disable-translate",
                "--hide-scrollbars",
                "--mute-audio",
                "--no-default-browser-check",
                "--no-pings",
                "--disable-background-networking",
                "--disable-client-side-phishing-detection",
                "--disable-hang-monitor",
                "--disable-popup-blocking",
                "--disable-prompt-on-repost",
                "--metrics-recording-only",
                "--safebrowsing-disable-auto-update",
                "--enable-automation",
                "--password-store=basic",
                "--use-mock-keychain",
                "--disable-web-security",
                "--disable-features=VizDisplayCompositor",
                "--disable-blink-features=AutomationControlled"
            );
            
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true) // Default to headless
                .setArgs(args)
            );
            log.info("Playwright initialized.");
        } catch (Exception e) {
            log.error("Failed to initialize Playwright", e);
        }
    }

    public void close() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    public Map<String, String> performLogin(String userId) {
        // Simple login logic placeholder - in real scenario would involve manual interaction or complex flow
        // For now, this mimics the "stealth" browser setup
        BrowserContext context = createStealthContext();
        Page page = context.newPage();
        
        try {
            log.info("Navigating to login page for user: {}", userId);
            page.navigate("https://login.taobao.com/member/login.jhtml");
            // Wait for user manual login or implement automated login if credentials are available
            // Since this is a migration, we assume we might leverage existing cookies or need manual intervention initially if no credentials.
            // If automated login is required, we populate username/password fields.
            
            // For this task, we focus on REFRESHING cookies or getting them if we simulate the flow.
            // Let's assume we just return empty for now as we don't have credentials in plain text passed here easily yet.
            // But we will implement the Stealth Scripts injection.
            
            return new HashMap<>();
        } finally {
            context.close();
        }
    }

    public Map<String, String> refreshCookies(String cookieId) {
        Cookie cookieEntity = cookieRepository.findById(cookieId).orElse(null);
        if (cookieEntity == null) return null;

        log.info("Refreshing cookies for: {}", cookieId);
        BrowserContext context = createStealthContext();
        
        try {
            // Add existing cookies
            Map<String, String> existingCookies = XianyuUtils.transCookies(cookieEntity.getValue());
            List<com.microsoft.playwright.options.Cookie> playwrightCookies = new ArrayList<>();
            existingCookies.forEach((k, v) -> {
                playwrightCookies.add(new com.microsoft.playwright.options.Cookie(k, v)
                    .setDomain(".taobao.com")
                    .setPath("/"));
            });
            context.addCookies(playwrightCookies);

            Page page = context.newPage();
            // Add stealth scripts
            addStealthScripts(page);

            // Navigate to a page that validates cookies
            page.navigate("https://h5api.m.goofish.com/h5/mtop.idle.web.xyh.item.list/1.0/");
            
            // Wait for network idle or specific element
            page.waitForLoadState();
            
            // Capture new cookies
            List<com.microsoft.playwright.options.Cookie> newCookiesList = context.cookies();
            Map<String, String> newCookiesMap = new HashMap<>();
            for (com.microsoft.playwright.options.Cookie c : newCookiesList) {
                newCookiesMap.put(c.name, c.value);
            }
            
            // Specific logic for x5sec or sliding capture (simplified for Java)
            // If slider appears, we would need logic to handle it.
            
            return newCookiesMap;
        } catch (Exception e) {
            log.error("Error refreshing cookies", e);
            return null;
        } finally {
            context.close();
        }
    }

    private BrowserContext createStealthContext() {
        Browser.NewContextOptions options = new Browser.NewContextOptions();
        options.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        options.setViewportSize(1920, 1080);
        options.setLocale("zh-CN");
        options.setTimezoneId("Asia/Shanghai");
        
        return browser.newContext(options);
    }

    private void addStealthScripts(Page page) {
        page.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined});");
        page.addInitScript("delete navigator.__proto__.webdriver;");
        page.addInitScript("window.chrome = { runtime: {} };");
        // Add more stealth scripts as needed from the Python reference
    }
}
