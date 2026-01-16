package com.xianyu.autoreply.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.xianyu.autoreply.repository.CookieRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class QrLoginService {

    private final CookieRepository cookieRepository;
    private final BrowserService browserService;
    private final Map<String, QrLoginSession> sessions = new ConcurrentHashMap<>();
    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    @Autowired
    public QrLoginService(CookieRepository cookieRepository, BrowserService browserService) {
        this.cookieRepository = cookieRepository;
        this.browserService = browserService;
        this.client = new OkHttpClient.Builder()
                .cookieJar(new InMemoryCookieJar())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // --- Session Classes ---

    @Data
    public static class QrLoginSession {
        private String sessionId;
        private String status = "waiting"; // waiting, scanned, success, expired, cancelled, verification_required
        private String qrCodeUrl;
        private String qrContent;
        private String unb;
        private long createdTime = System.currentTimeMillis();
        private long expireTime = 300 * 1000; // 5 mins
        private String verificationUrl;
        private Map<String, String> params = new HashMap<>(); // Store login params (t, ck, etc.)
        private Map<String, String> cookies = new HashMap<>();

        public boolean isExpired() {
            return System.currentTimeMillis() - createdTime > expireTime;
        }
    }

    // --- Core Methods ---

    public Map<String, Object> generateQrCode() {
        String sessionId = UUID.randomUUID().toString();
        log.info("【QR Login】Generating new QR code session: {}", sessionId);

        QrLoginSession session = new QrLoginSession();
        session.setSessionId(sessionId);

        try {
            // 1. Get m_h5_tk
            getMh5tk(session);
            log.info("【QR Login】Got m_h5_tk for session: {}", sessionId);

            // 2. Get Login Params
            Map<String, String> loginParams = getLoginParams(session);
            log.info("【QR Login】Got login params for session: {}", sessionId);

            // 3. Generate QR Code Data
            // Construct URL: https://passport.goofish.com/newlogin/qrcode/generate.do
            HttpUrl.Builder urlBuilder = HttpUrl.parse("https://passport.goofish.com/newlogin/qrcode/generate.do").newBuilder();
            for (Map.Entry<String, String> entry : loginParams.entrySet()) {
                urlBuilder.addQueryParameter(entry.getKey(), entry.getValue());
            }

            Request request = new Request.Builder()
                    .url(urlBuilder.build())
                    .headers(generateHeaders())
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body().string();
                log.debug("【QR Login Debug】Generate QR raw response: {}", responseBody);
                
                Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
                Map<String, Object> content = (Map<String, Object>) result.get("content");
                Boolean success = (Boolean) content.get("success");

                if (success != null && success) {
                    Map<String, Object> data = (Map<String, Object>) content.get("data");
                    
                    // Update session with t and ck
                    session.getParams().put("t", String.valueOf(data.get("t")));
                    session.getParams().put("ck", (String) data.get("ck"));
                    
                    String qrContent = (String) data.get("codeContent");
                    session.setQrContent(qrContent);
                    
                    String qrBase64 = generateQrImageBase64(qrContent);
                    String qrDataUrl = "data:image/png;base64," + qrBase64;
                    
                    session.setQrCodeUrl(qrDataUrl);
                    sessions.put(sessionId, session);
                    
                    log.info("【QR Login】QR Code generated successfully: {}", sessionId);
                    return Map.of(
                        "success", true,
                        "session_id", sessionId,
                        "qr_code_url", qrDataUrl
                    );
                } else {
                    throw new RuntimeException("Failed to generate QR code from API");
                }
            }

        } catch (Exception e) {
            log.error("【QR Login】Error generating QR code", e);
            return Map.of("success", false, "message", "生成二维码失败: " + e.getMessage());
        }
    }
    
    public Map<String, Object> checkQrCodeStatus(String sessionId) {
        QrLoginSession session = sessions.get(sessionId);
        if (session == null) {
            return Map.of("status", "not_found", "message", "会话不存在或已过期");
        }
        
        if (session.isExpired() && !"success".equals(session.getStatus())) {
            session.setStatus("expired");
            return Map.of("status", "expired", "session_id", sessionId);
        }
        
        // If already successful, return stored result
        if ("success".equals(session.getStatus()) && session.getUnb() != null) {
             return buildSuccessResult(session);
        }

        // Poll status from API
        try {
            pollQrCodeStatus(session);
        } catch (Exception e) {
            log.error("【QR Login】Error polling status for {}", sessionId, e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", session.getStatus());
        result.put("session_id", sessionId);
        
        if ("verification_required".equals(session.getStatus())) {
            result.put("verification_url", session.getVerificationUrl());
            result.put("message", "账号被风控，需要手机验证");
        }
        
        if ("success".equals(session.getStatus())) {
             log.info("【QR Login】Status confirmed SUCCESS. Starting post-login processing for UNB: {}", session.getUnb());
             
             try {
                processLoginSuccess(session);
                return buildSuccessResult(session);
             } catch (Exception e) {
                 log.error("【QR Login】Error during post-login processing", e);
                 result.put("status", "error");
                 result.put("message", "登录后处理失败: " + e.getMessage());
             }
        }
        
        return result;
    }
    
    private void processLoginSuccess(QrLoginSession session) {
        String unb = session.getUnb();
        if (unb == null) {
            throw new RuntimeException("Logged in but UNB is missing!");
        }
        
        // 1. Determine AccountId
        String accountId = unb; // Default to UNB
        boolean isNewAccount = true;
        
        if (cookieRepository.existsById(unb)) {
            isNewAccount = false;
            log.info("【QR Login】Found existing account by ID: {}", unb);
        } else {
             log.info("【QR Login】New account detected for UNB: {}", unb);
        }
        
        // 2. Verify and Refresh Cookies via BrowserService
        Map<String, String> verifiedCookies = browserService.verifyQrLoginCookies(session.getCookies(), accountId);
        
        if (verifiedCookies != null && !verifiedCookies.isEmpty()) {
            log.info("【QR Login】Browser verification SUCCESS. Cookies verified: {}", verifiedCookies.size());
            
            // Build cookie string
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : verifiedCookies.entrySet()) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
            }
            String finalCookieStr = sb.toString();
            
            // 3. Save to DB
            com.xianyu.autoreply.entity.Cookie cookieEntity = cookieRepository.findById(accountId)
                    .orElse(new com.xianyu.autoreply.entity.Cookie());
            
            cookieEntity.setId(accountId);
            cookieEntity.setValue(finalCookieStr);
            if (isNewAccount) {
                 cookieEntity.setUsername("TB_" + unb); // Placeholder
                 cookieEntity.setPassword("QR_LOGIN");  // Placeholder
                 cookieEntity.setUserId(0L); 
            }
            cookieEntity.setEnabled(true);
            cookieRepository.save(cookieEntity);
            
            log.info("【QR Login】Account saved to DB: {}", accountId);
            
            // Update session
            session.setCookies(verifiedCookies);
            
        } else {
             log.warn("【QR Login】Browser verification FAILED. Falling back to simple API cookies.");
             // Fallback: Save original API cookies
             
             StringBuilder sb = new StringBuilder();
             for (Map.Entry<String, String> entry : session.getCookies().entrySet()) {
                 sb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
             }
             String finalCookieStr = sb.toString();
             
             com.xianyu.autoreply.entity.Cookie cookieEntity = cookieRepository.findById(accountId)
                    .orElse(new com.xianyu.autoreply.entity.Cookie());
             cookieEntity.setId(accountId);
             cookieEntity.setValue(finalCookieStr);
             cookieEntity.setEnabled(true);
             cookieRepository.save(cookieEntity);
             log.info("【QR Login】Fallback: Original API cookies saved for {}", accountId);
        }
    }
    
    private Map<String, Object> buildSuccessResult(QrLoginSession session) {
         Map<String, Object> result = new HashMap<>();
         result.put("status", "success");
         result.put("session_id", session.getSessionId());
         result.put("unb", session.getUnb());
         
         StringBuilder sb = new StringBuilder();
         for (Map.Entry<String, String> entry : session.getCookies().entrySet()) {
             sb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
         }
         result.put("cookies", sb.toString());
         return result;
    }

    // --- Helper Methods ---

    /**
     * Refresh existing cookies using browser (Keep-alive)
     */
    public Map<String, String> refreshCookie(String accountId) {
        log.info("【QR Login】Triggering cookie refresh for account: {}", accountId);
        return browserService.refreshCookies(accountId);
    }

    private void getMh5tk(QrLoginSession session) throws IOException {
        String apiH5Tk = "https://h5api.m.goofish.com/h5/mtop.gaia.nodejs.gaia.idle.data.gw.v2.index.get/1.0/";
        String appKey = "34839810";
        String dataStr = "{\"bizScene\":\"home\"}";
        String t = String.valueOf(System.currentTimeMillis());

        // 1. Initial Get to get m_h5_tk cookie
        Request initialRequest = new Request.Builder()
                .url(apiH5Tk)
                .headers(generateHeaders())
                .get()
                .build();
        
        client.newCall(initialRequest).execute().close(); // Cookies handled by cookieJar

        // Extract m_h5_tk from cookie jar
        String mh5tk = getCookieValue("m_h5_tk");
        String token = mh5tk.split("_")[0];
        
        // 2. Sign
        String signInput = token + "&" + t + "&" + appKey + "&" + dataStr;
        String sign = md5(signInput);
        
        // 3. Post with sign
        HttpUrl url = HttpUrl.parse(apiH5Tk).newBuilder()
                .addQueryParameter("jsv", "2.7.2")
                .addQueryParameter("appKey", appKey)
                .addQueryParameter("t", t)
                .addQueryParameter("sign", sign)
                .addQueryParameter("v", "1.0")
                .addQueryParameter("type", "originaljson")
                .addQueryParameter("dataType", "json")
                .addQueryParameter("api", "mtop.gaia.nodejs.gaia.idle.data.gw.v2.index.get")
                .addQueryParameter("data", dataStr)
                .build();
                
        Request postRequest = new Request.Builder()
                .url(url)
                .headers(generateHeaders())
                .get() // Note: Python code used POST but query params seem to be in URL? Let's check Python code. Line 138: client.post(..., params=params). params in httpx POST usually go to query string? No, httpx params are query, data/json is body. But here Python used `post` with `params`. 
                // Ah, Python code: client.post(self.api_h5_tk, params=params, headers=self.headers, cookies=session.cookies)
                // Wait, if it is a POST without body?? Usually mtop requires GET or POST. Let's stick to what Python did.
                // Re-reading Python: `params` argument in `client.post` adds to URL query string.
                .post(RequestBody.create(new byte[0], null)) // Empty body POST
                .build();
                
        client.newCall(postRequest).execute().close();
    }
    
    private Map<String, String> getLoginParams(QrLoginSession session) throws IOException {
        HttpUrl url = HttpUrl.parse("https://passport.goofish.com/mini_login.htm").newBuilder()
                .addQueryParameter("lang", "zh_cn")
                .addQueryParameter("appName", "xianyu")
                .addQueryParameter("appEntrance", "web")
                .addQueryParameter("styleType", "vertical")
                .addQueryParameter("bizParams", "")
                .addQueryParameter("notLoadSsoView", "false")
                .addQueryParameter("notKeepLogin", "false")
                .addQueryParameter("isMobile", "false")
                .addQueryParameter("qrCodeFirst", "false")
                .addQueryParameter("stie", "77")
                .addQueryParameter("rnd", String.valueOf(Math.random()))
                .build();
                
        Request request = new Request.Builder()
                .url(url)
                .headers(generateHeaders())
                .get()
                .build();
                
        try (Response response = client.newCall(request).execute()) {
             String html = response.body().string();
             Pattern pattern = Pattern.compile("window\\.viewData\\s*=\s*(\\{.*?\\});");
             Matcher matcher = pattern.matcher(html);
             if (matcher.find()) {
                 String jsonStr = matcher.group(1);
                 Map<String, Object> viewData = objectMapper.readValue(jsonStr, Map.class);
                 Map<String, Object> loginFormData = (Map<String, Object>) viewData.get("loginFormData");
                 
                 Map<String, String> params = new HashMap<>();
                 if (loginFormData != null) {
                     for (Map.Entry<String, Object> entry : loginFormData.entrySet()) {
                         params.put(entry.getKey(), String.valueOf(entry.getValue()));
                     }
                     params.put("umidTag", "SERVER");
                     session.getParams().putAll(params);
                     return params;
                 }
             }
        }
        throw new RuntimeException("Could not find login params in mini_login.htm");
    }
    
    private void pollQrCodeStatus(QrLoginSession session) throws IOException {
        String apiScanStatus = "https://passport.goofish.com/newlogin/qrcode/query.do";
        
        FormBody.Builder formBuilder = new FormBody.Builder();
        for (Map.Entry<String, String> entry : session.getParams().entrySet()) {
            formBuilder.add(entry.getKey(), entry.getValue());
        }
        
        Request request = new Request.Builder()
                .url(apiScanStatus)
                .headers(generateHeaders())
                // In Python: client.post(api, data=session.params). `data` means FORM body.
                .post(formBuilder.build())
                .build();
                
        try (Response response = client.newCall(request).execute()) {
            String body = response.body().string();
            Map<String, Object> result = objectMapper.readValue(body, Map.class);
            
            // Capture cookies from response
            List<Cookie> cookies = Cookie.parseAll(request.url(), response.headers());
            for (Cookie c : cookies) {
                session.getCookies().put(c.name(), c.value());
                if ("unb".equals(c.name())) {
                    session.setUnb(c.value());
                }
            }
            
            Map<String, Object> content = (Map<String, Object>) result.get("content");
            Map<String, Object> data = (Map<String, Object>) content.get("data");
            
            String qrCodeStatus = (String) data.get("qrCodeStatus");
            
            if ("CONFIRMED".equals(qrCodeStatus)) {
                Boolean iframeRedirect = (Boolean) data.get("iframeRedirect");
                if (iframeRedirect != null && iframeRedirect) {
                    session.setStatus("verification_required");
                    session.setVerificationUrl((String) data.get("iframeRedirectUrl"));
                    log.warn("【QR Login】Risk control triggered: {}", session.getSessionId());
                } else {
                    session.setStatus("success");
                    log.info("【QR Login】Success! UNB: {}", session.getUnb());
                }
            } else if ("NEW".equals(qrCodeStatus)) {
                // waiting
            } else if ("EXPIRED".equals(qrCodeStatus)) {
                session.setStatus("expired");
            } else if ("SCANED".equals(qrCodeStatus)) {
                session.setStatus("scanned");
            } else {
                session.setStatus("cancelled");
            }
        }
    }

    private String generateQrImageBase64(String content) throws Exception {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, 200, 200);
        ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngOutputStream);
        byte[] pngData = pngOutputStream.toByteArray();
        return Base64.getEncoder().encodeToString(pngData);
    }
    
    private Headers generateHeaders() {
        return new Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .add("Accept", "application/json, text/plain, */*")
                .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .add("Referer", "https://passport.goofish.com/")
                .add("Origin", "https://passport.goofish.com")
                .build();
    }
    
    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : messageDigest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String getCookieValue(String name) {
        // InMemoryCookieJar implementation detail - retrieving for specific host
        // Since we know the host
        List<Cookie> cookies = client.cookieJar().loadForRequest(HttpUrl.parse("https://h5api.m.goofish.com"));
        for (Cookie c : cookies) {
            if (c.name().equals(name)) return c.value();
        }
        return "";
    }
    
    // Simple custom InMemoryCookieJar
    private static class InMemoryCookieJar implements CookieJar {
        private final List<Cookie> cookies = new ArrayList<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            this.cookies.addAll(cookies);
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> validCookies = new ArrayList<>();
            for (Cookie cookie : cookies) {
                validCookies.add(cookie);
            }
            return validCookies;
        }
    }
}
