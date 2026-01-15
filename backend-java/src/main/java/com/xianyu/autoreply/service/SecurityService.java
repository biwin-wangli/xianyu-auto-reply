package com.xianyu.autoreply.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Service
public class SecurityService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.xianyu.autoreply.repository.CookieRepository cookieRepository;
    
    @org.springframework.beans.factory.annotation.Autowired
    private CookieService cookieService;

    public Map<String, Object> autoConfirmOrder(String cookieId, String orderId) {
        String cookieStr = getCookieValue(cookieId);
        if (cookieStr == null) {
             return Map.of("error", "Cookie not found");
        }
        
        // Extract Token
        String token = "";
        String[] parts = cookieStr.split(";");
        for (String part : parts) {
            String[] kv = part.trim().split("=");
            if (kv.length > 0 && "_m_h5_tk".equals(kv[0])) {
                if (kv.length > 1) {
                    token = kv[1].split("_")[0];
                }
                break;
            }
        }
        
        long t = System.currentTimeMillis();
        String appKey = "34839810";
        String data = "{\"orderId\":\"" + orderId + "\", \"tradeText\":\"\",\"picList\":[],\"newUnconsign\":true}";
        
        String sign = generateSign(String.valueOf(t), token, appKey, data);
        
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("jsv", "2.7.2");
        params.put("appKey", appKey);
        params.put("t", String.valueOf(t));
        params.put("sign", sign);
        params.put("v", "1.0");
        params.put("type", "originaljson");
        params.put("accountSite", "xianyu");
        params.put("dataType", "json");
        params.put("timeout", "20000");
        params.put("api", "mtop.taobao.idle.logistic.consign.dummy");
        params.put("sessionOption", "AutoLoginOnly");

        try {
            String url = "https://h5api.m.goofish.com/h5/mtop.taobao.idle.logistic.consign.dummy/1.0/";
            
            String requestUrl = url + "?" + cn.hutool.http.HttpUtil.toParams(params);
            try (cn.hutool.http.HttpResponse response = cn.hutool.http.HttpRequest.post(requestUrl)
                .header("Cookie", cookieStr)
                .body("data=" + java.net.URLEncoder.encode(data, StandardCharsets.UTF_8))
                .execute()) {

                String body = response.body();
                log.info("【{}】Auto confirm response: {}", cookieId, body);

                // Update Cookies from Response
                java.util.List<java.net.HttpCookie> cookies = response.getCookies();
                if (cookies != null && !cookies.isEmpty()) {
                    // Logic to merge new cookies into existing string
                    Map<String, String> currentCookiesMap = com.xianyu.autoreply.utils.XianyuUtils.transCookies(cookieStr);
                    boolean changed = false;
                    for (java.net.HttpCookie c : cookies) {
                         // Some cookies might be deleted if value is deleted? 
                         // Usually updates standard k=v
                         String oldVal = currentCookiesMap.get(c.getName());
                         if (oldVal == null || !oldVal.equals(c.getValue())) {
                             currentCookiesMap.put(c.getName(), c.getValue());
                             changed = true;
                         }
                    }
                    
                    if (changed) {
                        StringBuilder sb = new StringBuilder();
                        for (Map.Entry<String, String> entry : currentCookiesMap.entrySet()) {
                            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
                        }
                        String newCookieVal = sb.toString();
                        // Update Service
                        cookieService.updateCookie(cookieId, newCookieVal);
                        log.info("【{}】Cookies updated from response headers", cookieId);
                    }
                }

                com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(body);
                if (json.containsKey("ret")) {
                    com.alibaba.fastjson2.JSONArray ret = json.getJSONArray("ret");
                    if (ret != null && !ret.isEmpty() && ret.getString(0).startsWith("SUCCESS")) {
                        return Map.of("success", true, "order_id", orderId);
                    }
                }
                return Map.of("success", false, "message", body);
            }
            
        } catch (Exception e) {
            log.error("【{}】Auto confirm error", cookieId, e);
            return Map.of("error", e.getMessage());
        }
    }

    private String getCookieValue(String cookieId) {
        return cookieRepository.findById(cookieId).map(com.xianyu.autoreply.entity.Cookie::getValue).orElse(null);
    }

    private String generateSign(String t, String token, String appKey, String data) {
        String src = token + "&" + t + "&" + appKey + "&" + data;
        return cn.hutool.crypto.SecureUtil.md5(src);
    }
}
