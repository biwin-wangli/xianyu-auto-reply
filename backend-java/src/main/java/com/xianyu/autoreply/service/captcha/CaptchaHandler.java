package com.xianyu.autoreply.service.captcha;

import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.Cookie;
import com.xianyu.autoreply.service.captcha.model.CaptchaResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.microsoft.playwright.*;

import java.util.*;

/**
 * 滑块验证处理器 - 基于Playwright
 */
@Slf4j
@Component
public class CaptchaHandler {
    
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    
    /**
     * 处理滑块验证
     * 
     * @param verificationUrl 验证URL
     * @param cookieId 账号ID
     * @return 验证结果
     */
    public CaptchaResult handleCaptcha(String verificationUrl, String cookieId) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("【{}】开始处理滑块验证...", cookieId);
            log.info("【{}】验证URL: {}", cookieId, verificationUrl);
            
            // 初始化浏览器
            initBrowser(cookieId);
            
            // 导航到验证页面
            navigateToCaptchaPage(verificationUrl, cookieId);
            
            // 等待页面加载
            Thread.sleep(2000);
            
            // 查找滑块元素
            log.info("【{}】查找滑块元素...", cookieId);
            ElementHandle sliderElement = findSliderElement(cookieId);
            
            if (sliderElement == null) {
                return CaptchaResult.failure("未找到滑块元素");
            }
            
            // 计算移动距离
            int distance = calculateDistance(sliderElement, cookieId);
            log.info("【{}】滑块移动距离: {}px", cookieId, distance);
            
            // 执行拖动
            dragSlider(sliderElement, distance, cookieId);
            
            // 检查是否成功
            Thread.sleep(2000);
            boolean success = checkSuccess(cookieId);
            
            if (success) {
                // 提取cookies
                Map<String, String> cookies = extractCookies(cookieId);
                long duration = System.currentTimeMillis() - startTime;
                log.info("【{}】✅ 滑块验证成功！耗时: {}ms", cookieId, duration);
                return CaptchaResult.success(cookies, duration);
            } else {
                return CaptchaResult.failure("滑块验证失败");
            }
            
        } catch (Exception e) {
            log.error("【{}】滑块验证异常", cookieId, e);
            return CaptchaResult.failure("异常: " + e.getMessage());
        } finally {
            // 清理资源
            cleanup(cookieId);
        }
    }
    
    /**
     * 初始化浏览器
     */
    private void initBrowser(String cookieId) {
        log.info("【{}】初始化Playwright浏览器...", cookieId);
        
        playwright = Playwright.create();
        
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
            .setHeadless(false)
            .setArgs(Arrays.asList(
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--disable-dev-shm-usage",
                "--disable-blink-features=AutomationControlled",
                "--disable-gpu",
                "--disable-web-security"
            ))
        );
        
        // 创建上下文
        context = browser.newContext(new Browser.NewContextOptions()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setViewportSize(1920, 1080)
        );
        
        // 创建页面
        page = context.newPage();
        
        // 注入反检测脚本
        injectStealthScript(cookieId);
        
        log.info("【{}】浏览器初始化完成", cookieId);
    }
    
    /**
     * 注入反检测脚本
     */
    private void injectStealthScript(String cookieId) {
        String script = """
            Object.defineProperty(navigator, 'webdriver', {
                get: () => undefined
            });
            
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array;
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise;
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol;
            
            window.chrome = {
                runtime: {}
            };
        """;
        
        page.addInitScript(script);
        log.debug("【{}】反检测脚本已注入", cookieId);
    }
    
    /**
     * 导航到验证页面
     */
    private void navigateToCaptchaPage(String url, String cookieId) {
        log.info("【{}】导航到验证页面...", cookieId);
        page.navigate(url, new Page.NavigateOptions().setTimeout(30000));
        log.info("【{}】页面加载完成", cookieId);
    }
    
    /**
     * 查找滑块元素
     */
    private ElementHandle findSliderElement(String cookieId) {
        // 多种选择器策略
        List<String> selectors = Arrays.asList(
            "#nc_1_n1z",
            ".nc-lang-cnt",
            "[id^='nc_'][id$='_n1z']",
            ".btn_slide"
        );
        
        for (String selector : selectors) {
            try {
                ElementHandle element = page.querySelector(selector);
                if (element != null) {
                    log.info("【{}】找到滑块元素: {}", cookieId, selector);
                    return element;
                }
            } catch (Exception e) {
                log.debug("【{}】选择器{}未找到", cookieId, selector);
            }
        }
        
        log.warn("【{}】未找到滑块元素", cookieId);
        return null;
    }
    
    /**
     * 计算移动距离
     */
    private int calculateDistance(ElementHandle slider, String cookieId) {
        // 获取滑块位置信息
        BoundingBox box = slider.boundingBox();
        
        // 简化计算：移动到右侧（实际应该根据页面计算）
        int distance = 300;  // 默认300px
        
        log.info("【{}】计算移动距离: {}px", cookieId, distance);
        return distance;
    }
    
    /**
     * 拖动滑块
     */
    private void dragSlider(ElementHandle slider, int distance, String cookieId) throws InterruptedException {
        log.info("【{}】开始拖动滑块...", cookieId);
        
        BoundingBox box = slider.boundingBox();
        double startX = box.x + box.width / 2;
        double startY = box.y + box.height / 2;
        
        // 移动到滑块
        page.mouse().move(startX, startY);
        page.mouse().down();
        
        // 简单拖动（后续优化为贝塞尔曲线）
        int steps = 3;
        for (int i = 1; i <= steps; i++) {
            double x = startX + (distance * i / (double) steps);
            page.mouse().move(x, startY);
        }
        
        page.mouse().up();
        log.info("【{}】滑块拖动完成", cookieId);
    }
    
    /**
     * 检查验证是否成功
     */
    private boolean checkSuccess(String cookieId) {
        try {
            // 等待成功提示
            page.waitForSelector(".nc-lang-cnt:has-text('验证通过')", 
                new Page.WaitForSelectorOptions().setTimeout(5000));
            log.info("【{}】检测到验证成功提示", cookieId);
            return true;
        } catch (Exception e) {
            log.warn("【{}】未检测到验证成功", cookieId);
            return false;
        }
    }
    
    /**
     * 提取cookies
     */
    private Map<String, String> extractCookies(String cookieId) {
        log.info("【{}】提取验证后的cookies...", cookieId);
        
        List<Cookie> cookies = context.cookies();
        Map<String, String> result = new HashMap<>();
        
        // 只提取x5相关cookies
        for (Cookie cookie : cookies) {
            String name = cookie.name.toLowerCase();
            if (name.startsWith("x5") || name.contains("x5sec")) {
                result.put(cookie.name, cookie.value);
                log.info("【{}】提取x5 cookie: {}", cookieId, cookie.name);
            }
        }
        
        log.info("【{}】成功提取{}个x5 cookies", cookieId, result.size());
        return result;
    }
    
    /**
     * 清理资源
     */
    private void cleanup(String cookieId) {
        try {
            if (page != null) {
                page.close();
            }
            if (context != null) {
                context.close();
            }
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
            log.info("【{}】浏览器资源已清理", cookieId);
        } catch (Exception e) {
            log.warn("【{}】清理资源时出错", cookieId, e);
        }
    }
}
