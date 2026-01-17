package com.xianyu.autoreply.service;

import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.service.captcha.CaptchaHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class XianyuClientService {

    private final CookieRepository cookieRepository;
    private final ReplyService replyService;
    private final CaptchaHandler captchaHandler;
    private final BrowserService browserService;
    private final PauseManager pauseManager;
    private final OrderStatusHandler orderStatusHandler;
    private final Map<String, XianyuClient> clients = new ConcurrentHashMap<>();

    @Autowired
    public XianyuClientService(CookieRepository cookieRepository, ReplyService replyService, 
                               CaptchaHandler captchaHandler, BrowserService browserService,
                               PauseManager pauseManager, OrderStatusHandler orderStatusHandler) {
        this.cookieRepository = cookieRepository;
        this.replyService = replyService;
        this.captchaHandler = captchaHandler;
        this.browserService = browserService;
        this.pauseManager = pauseManager;
        this.orderStatusHandler = orderStatusHandler;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing XianyuClientService...");
        List<Cookie> cookies = cookieRepository.findAll();
        for (Cookie cookie : cookies) {
            if (Boolean.TRUE.equals(cookie.getEnabled())) {
                startClient(cookie.getId());
            }
        }
    }

    public void startClient(String cookieId) {
        if (clients.containsKey(cookieId)) {
            log.warn("Client {} already running", cookieId);
            return;
        }
        XianyuClient client = new XianyuClient(cookieId, cookieRepository, replyService, 
                                              captchaHandler, browserService, pauseManager, orderStatusHandler);
        clients.put(cookieId, client);
        client.start();
    }

    public void stopClient(String cookieId) {
        XianyuClient client = clients.remove(cookieId);
        if (client != null) {
            client.stop();
        }
    }
    
    public XianyuClient getClient(String cookieId) {
        return clients.get(cookieId);
    }
}
