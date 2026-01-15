package com.xianyu.autoreply.service;

import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.repository.CookieRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class CookieService {

    @Autowired
    private CookieRepository cookieRepository;

    @Autowired
    private XianyuClientService xianyuClientService;

    @Transactional
    public void addCookie(String cookieId, String cookieValue, Long userId) {
        Cookie cookie = new Cookie();
        cookie.setId(cookieId);
        cookie.setValue(cookieValue);
        cookie.setUserId(userId);
        cookie.setAutoConfirm(1);
        cookie.setEnabled(true);
        cookie.setShowBrowser(0);
        
        cookieRepository.save(cookie);
        log.info("Cookie saved for {}", cookieId);
        
        xianyuClientService.startClient(cookieId);
    }

    @Transactional
    public void removeCookie(String cookieId) {
        xianyuClientService.stopClient(cookieId);
        cookieRepository.deleteById(cookieId);
        log.info("Cookie removed: {}", cookieId);
    }

    @Transactional
    public void updateCookie(String cookieId, String newValue) {
        // Stop first
        xianyuClientService.stopClient(cookieId);
        
        // Update DB
        cookieRepository.findById(cookieId).ifPresent(cookie -> {
            cookie.setValue(newValue);
            cookieRepository.save(cookie);
            log.info("Cookie value updated for {}", cookieId);
        });
        
        // Restart
        xianyuClientService.startClient(cookieId);
    }
}
