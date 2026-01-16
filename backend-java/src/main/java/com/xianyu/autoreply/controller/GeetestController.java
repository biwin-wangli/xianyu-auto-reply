package com.xianyu.autoreply.controller;

import cn.hutool.json.JSONObject;
import com.xianyu.autoreply.utils.GeetestLib;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/geetest")
public class GeetestController {

    private final GeetestLib geetestLib;

    @Autowired
    public GeetestController(GeetestLib geetestLib) {
        this.geetestLib = geetestLib;
    }

    /**
     * 极验初始化接口
     */
    @GetMapping("/register")
    public Map<String, Object> register() {
        // 必传参数
        // digestmod: 加密算法，"md5", "sha256", "hmac-sha256"
        GeetestLib.GeetestResult result = geetestLib.register(GeetestLib.DigestMod.MD5, null, null);
        
        Map<String, Object> response = new HashMap<>();
        if (result.getStatus() == 1 || (result.getData() != null && result.getData().contains("\"success\": 0"))) {
             // status 1 means full success
             // or if it fallback mode (status might be 0 in Lib but we treat as success HTTP response with offline data)
             // Check GeetestLib: logic. It sets status=0 if logic fails? 
             // GeetestLib: "初始化接口失败，后续流程走宕机模式" sets status=0. 
             // But for the frontend, getting the offline parameters IS a successful API call.
             
             response.put("success", true);
             response.put("code", 200);
             response.put("message", "获取成功");
             response.put("data", result.toJsonObject());
        } else {
             response.put("success", false);
             response.put("code", 500);
             response.put("message", "获取验证参数失败: " + result.getMsg());
        }
        
        return response;
    }

    /**
     * 极验二次验证接口
     */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody ValidateRequest request) {
        GeetestLib.GeetestResult result;
        
        // 这里的逻辑需要根据 register 返回的 new_captcha (gt_server_status) 来判断走 normal 还是 fail 模式
        // 但是在 Python SDK 的使用中，这个状态通常维护在 Session 中
        // 简单实现：如果不判断状态，默认尝试走 successValidate (正常模式)
        // 也可以让前端传回来，或者像 Python demo 那样存 session
        
        // 在 Python 的 reply_server.py 中，其实并没有展示完整的 validate 逻辑，
        // 这里我们按照 Standard Flow 实现
        
        result = geetestLib.successValidate(
                request.getChallenge(), 
                request.getValidate(), 
                request.getSeccode(), 
                null, 
                null
        );

        Map<String, Object> response = new HashMap<>();
        if (result.getStatus() == 1) {
            response.put("success", true);
            response.put("code", 200);
            response.put("message", "验证通过");
        } else {
            response.put("success", false);
            response.put("code", 400);
            response.put("message", "验证失败: " + result.getMsg());
        }
        
        return response;
    }

    @Data
    public static class ValidateRequest {
        private String challenge;
        private String validate;
        private String seccode;
    }
}
