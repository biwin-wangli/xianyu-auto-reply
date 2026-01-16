package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.service.QrLoginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class QrLoginController {

    private final QrLoginService qrLoginService;

    @Autowired
    public QrLoginController(QrLoginService qrLoginService) {
        this.qrLoginService = qrLoginService;
    }

    @PostMapping("/qr-login/generate")
    public Map<String, Object> generateQrCode() {
        return qrLoginService.generateQrCode();
    }

    @GetMapping("/qr-login/check/{sessionId}")
    public Map<String, Object> checkQrCodeStatus(@PathVariable String sessionId) {
        return qrLoginService.checkQrCodeStatus(sessionId);
    }

    @PostMapping("/qr-login/refresh-cookie/{accountId}")
    public Map<String, String> refreshCookie(@PathVariable String accountId) {
        return qrLoginService.refreshCookie(accountId);
    }
}
