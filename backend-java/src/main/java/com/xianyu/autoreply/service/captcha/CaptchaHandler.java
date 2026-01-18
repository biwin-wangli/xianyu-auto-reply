package com.xianyu.autoreply.service.captcha;

import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.Cookie;
import com.xianyu.autoreply.service.BrowserService;
import com.xianyu.autoreply.service.captcha.model.CaptchaResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.microsoft.playwright.*;

import java.util.*;

/**
 * 滑块验证处理器 - 基于Playwright
 * 通过依赖注入复用BrowserService的Playwright实例，避免多实例冲突
 */
@Slf4j
@Component
public class CaptchaHandler {
    
    private final BrowserService browserService;
    private BrowserContext context;
    private Page page;
    
    @Autowired
    public CaptchaHandler(BrowserService browserService) {
        this.browserService = browserService;
    }
    
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
            Thread.sleep(5000);
            boolean success = checkSuccess(verificationUrl, cookieId);
            
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
     * 初始化浏览器（复用共享Browser实例，但创建临时的Context和Page）
     */
    private void initBrowser(String cookieId) {
        log.info("【{}】初始化浏览器上下文（复用共享Browser实例）...", cookieId);
        
        try {
            // 从 BrowserService 获取共享的 Browser 实例
            Browser sharedBrowser = browserService.getSharedBrowser();
            
            if (sharedBrowser == null) {
                String errorMsg = "BrowserService.getSharedBrowser() 返回 null，Playwright 可能未正确初始化";
                log.error("【{}】{}", cookieId, errorMsg);
                throw new IllegalStateException(errorMsg);
            }
            
            log.debug("【{}】成功获取共享Browser实例", cookieId);
            
            // 创建临时的非持久化 BrowserContext（不使用 UserData，避免 SingletonLock 冲突）
            context = sharedBrowser.newContext(new Browser.NewContextOptions()
                .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                // 注意：不设置 UserData，这样就不会创建持久化上下文
            );
            
            if (context == null) {
                String errorMsg = "创建 BrowserContext 失败，返回 null";
                log.error("【{}】{}", cookieId, errorMsg);
                throw new IllegalStateException(errorMsg);
            }
            
            log.debug("【{}】成功创建浏览器上下文", cookieId);
            
            // 创建页面
            page = context.newPage();
            
            if (page == null) {
                String errorMsg = "创建 Page 失败，返回 null";
                log.error("【{}】{}", cookieId, errorMsg);
                throw new IllegalStateException(errorMsg);
            }
            
            log.debug("【{}】成功创建页面对象", cookieId);
            
            // 添加反检测脚本
            injectStealthScript(cookieId);
            
            log.info("【{}】✅ 浏览器上下文初始化完成（Context: {}, Page: {}）", 
                     cookieId, context != null, page != null);
        } catch (Exception e) {
            log.error("【{}】❌ 初始化浏览器失败", cookieId, e);
            // 确保资源清理
            cleanup(cookieId);
            throw new RuntimeException("初始化浏览器失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 注入反检测脚本
     */
    private void injectStealthScript(String cookieId) {
        if (this.page == null) {
            log.warn("【{}】⚠️ Page为null，无法注入反检测脚本", cookieId);
            return;
        }
        
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
        
        // 防御性检查：确保 page 已经被正确初始化
        if (this.page == null) {
            String errorMsg = "Page对象为null，浏览器可能未正确初始化";
            log.error("【{}】{}", cookieId, errorMsg);
            throw new IllegalStateException(errorMsg);
        }
        
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
     * 拖动滑块（使用贝塞尔曲线模拟人类行为）
     */
    private void dragSlider(ElementHandle slider, int distance, String cookieId) throws InterruptedException {
        log.info("【{}】滑块移动距离: {}px", cookieId, distance);
        log.info("【{}】开始拖动滑块（使用贝塞尔曲线模拟人类行为）...", cookieId);
        
        BoundingBox box = slider.boundingBox();
        double startX = box.x + box.width / 2;
        double startY = box.y + box.height / 2;
        
        // ========== 鼠标预热移动（模拟真实人类鼠标轨迹） ==========
        log.info("【{}】执行鼠标预热移动...", cookieId);
        
        // 1. 先移动到页面随机位置（模拟用户浏览页面）
        double randomX1 = 200 + Math.random() * 400; // 200-600px范围
        double randomY1 = 100 + Math.random() * 200; // 100-300px范围
        page.mouse().move(randomX1, randomY1);
        page.mouse().click(randomX1, randomY1, new Mouse.ClickOptions().setClickCount(1));
        Thread.sleep(100 + (long)(Math.random() * 200));
        
        // 2. 再移动到接近滑块的位置（但不是精确位置）
        double approachX = startX - 50 - Math.random() * 100; // 滑块左侧50-150px
        double approachY = startY + (Math.random() - 0.5) * 100; // 上下浮动50px
        List<Point> approachTrack = generateBezierTrack(randomX1, randomY1, approachX - randomX1);
        
        // 快速移动到接近位置（模拟找到滑块的过程）
        for (int i = 0; i < Math.min(approachTrack.size(), 20); i += 2) { // 只取部分点，移动更快
            Point p = approachTrack.get(i);
            // Y轴使用插值
            double approachProgress = i / 20.0;
            double currentY = randomY1 + (approachY - randomY1) * approachProgress;
            page.mouse().move(p.x, currentY);
            Thread.sleep(8 + (long)(Math.random() * 5));
        }
        
        log.info("【{}】预热移动完成，准备拖动滑块", cookieId);
        
        // ========== 随机等待（模拟人类反应时间） ==========
        Thread.sleep(200 + (long)(Math.random() * 300));
        
        // 移动到滑块起始位置（带一点随机偏移）
        double offsetY = (Math.random() - 0.5) * 5; // ±2.5px的Y轴偏移
        page.mouse().move(startX, startY + offsetY);
        Thread.sleep(80 + (long)(Math.random() * 120));
        
        // 按下鼠标
        page.mouse().down();
        Thread.sleep(50 + (long)(Math.random() * 50));
        
        // 使用贝塞尔曲线生成轨迹点
        List<Point> track = generateBezierTrack(startX, startY + offsetY, distance);
        
        // 按轨迹移动鼠标
        for (int i = 0; i < track.size(); i++) {
            Point p = track.get(i);
            page.mouse().move(p.x, p.y);
            
            // 动态延迟：开始快，中间慢，结束稍快
            double progress = i / (double) track.size();
            long delay;
            if (progress < 0.3) {
                // 前30%：快速移动
                delay = 5 + (long)(Math.random() * 8);
            } else if (progress < 0.8) {
                // 中间50%：减速
                delay = 15 + (long)(Math.random() * 12);
            } else {
                // 最后20%：稍加速完成
                delay = 8 + (long)(Math.random() * 10);
            }
            Thread.sleep(delay);
        }
        
        // ========== 过冲和回退（模拟人类精细调整） ==========
        Point lastPoint = track.get(track.size() - 1);
        
        // 70%概率过冲（人类经常拖多一点再调整）
        if (Math.random() < 0.7) {
            // 过冲 3-8px
            double overshoot = 3 + Math.random() * 5;
            page.mouse().move(lastPoint.x + overshoot, lastPoint.y + (Math.random() - 0.5) * 3);
            Thread.sleep(50 + (long)(Math.random() * 80));
            
            // 回退到准确位置
            page.mouse().move(lastPoint.x, lastPoint.y);
            Thread.sleep(30 + (long)(Math.random() * 50));
        }
        
        // 到达终点后短暂停顿（模拟人类确认）
        Thread.sleep(150 + (long)(Math.random() * 200));
        
        // 松开鼠标
        page.mouse().up();
        
        log.info("【{}】滑块拖动完成", cookieId);
        
        // 等待验证结果
        Thread.sleep(1500);
    }
    
    /**
     * 使用贝塞尔曲线生成滑块轨迹
     * 模拟人类拖动：带随机抖动、非线性速度变化
     */
    private List<Point> generateBezierTrack(double startX, double startY, double distance) {
        List<Point> points = new ArrayList<>();
        
        // 终点坐标（带一点随机偏移避免过于精确）
        double endX = startX + distance + (Math.random() - 0.5) * 3;
        double endY = startY + (Math.random() - 0.5) * 8; // Y轴有更大的偏移
        
        // 控制点：使轨迹呈弧形（模拟手臂自然弧度）
        double controlX1 = startX + distance * 0.3 + (Math.random() - 0.5) * 15;
        double controlY1 = startY - 10 - Math.random() * 15; // 向上弧
        
        double controlX2 = startX + distance * 0.7 + (Math.random() - 0.5) * 15;
        double controlY2 = startY + 5 + (Math.random() - 0.5) * 10; // 稍微向下
        
        // 生成轨迹点（50-80个点之间）
        int numPoints = 50 + (int)(Math.random() * 30);
        
        for (int i = 0; i <= numPoints; i++) {
            double t = i / (double) numPoints;
            
            // 三次贝塞尔曲线公式
            double x = Math.pow(1 - t, 3) * startX +
                      3 * Math.pow(1 - t, 2) * t * controlX1 +
                      3 * (1 - t) * Math.pow(t, 2) * controlX2 +
                      Math.pow(t, 3) * endX;
                      
            double y = Math.pow(1 - t, 3) * startY +
                      3 * Math.pow(1 - t, 2) * t * controlY1 +
                      3 * (1 - t) * Math.pow(t, 2) * controlY2 +
                      Math.pow(t, 3) * endY;
            
            // 添加随机抖动（模拟手部微颤）
            if (i > 0 && i < numPoints) { // 起点和终点不抖动
                x += (Math.random() - 0.5) * 2.5; // X轴抖动 ±1.25px
                y += (Math.random() - 0.5) * 3;   // Y轴抖动 ±1.5px（更大）
            }
            
            points.add(new Point(x, y));
        }
        
        return points;
    }
    
    /**
     * 检查验证是否成功
     * 通过判断当前页面地址是否已经不是验证页面地址来确定验证是否成功
     * 
     * @param verificationUrl 验证页面URL
     * @param cookieId 账号ID
     * @return 验证是否成功
     */
    private boolean checkSuccess(String verificationUrl, String cookieId) {
        try {
            // 获取当前页面URL
            String currentUrl = page.url();
            log.info("【{}】当前页面URL: {}", cookieId, currentUrl);
            log.info("【{}】验证页面URL: {}", cookieId, verificationUrl);
            
            // 判断当前页面地址是否已经不是验证页面地址
            boolean urlChanged = !currentUrl.equals(verificationUrl);
            
            if (urlChanged) {
                log.info("【{}】✅ 页面已跳转，验证成功！", cookieId);
                return true;
            } else {
                log.warn("【{}】⚠️ 页面未跳转，仍在验证页面", cookieId);
                return false;
            }
        } catch (Exception e) {
            log.error("【{}】❌ 检查验证状态时发生异常", cookieId, e);
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
     * 注意：只清理自己创建的Context和Page，不关闭共享的Browser
     */
    private void cleanup(String cookieId) {
        try {
            if (page != null) {
                page.close();
                page = null;
            }
            if (context != null) {
                context.close();
                context = null;
            }
            // 不关闭共享的Browser和Playwright，它们由BrowserService管理
            log.info("【{}】浏览器资源已清理", cookieId);
        } catch (Exception e) {
            log.warn("【{}】清理资源时出错", cookieId, e);
        }
    }
    
    /**
     * 辅助类：表示一个坐标点
     */
    private static class Point {
        double x;
        double y;
        
        Point(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }
}
