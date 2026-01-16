package com.xianyu.autoreply.utils;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xianyu.autoreply.config.GeetestConfig;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 极验验证码SDK核心库
 * 对应 Python: utils/geetest/geetest_lib.py
 */
@Slf4j
@Component
public class GeetestLib {

    private final String captchaId;
    private final String privateKey;

    public GeetestLib() {
        this.captchaId = GeetestConfig.CAPTCHA_ID;
        this.privateKey = GeetestConfig.PRIVATE_KEY;
    }

    public enum DigestMod {
        MD5("md5"),
        SHA256("sha256"),
        HMAC_SHA256("hmac-sha256");

        private final String value;

        DigestMod(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeetestResult {
        private int status; // 1成功，0失败
        private String data; // 返回数据（JSON字符串）
        private String msg;  // 备注信息

        public JSONObject toJsonObject() {
            try {
                if (data != null && !data.isEmpty()) {
                    return JSONUtil.parseObj(data);
                }
            } catch (Exception e) {
                // ignore
            }
            return new JSONObject();
        }
    }

    private String md5Encode(String value) {
        return DigestUtil.md5Hex(value);
    }

    private String sha256Encode(String value) {
        return DigestUtil.sha256Hex(value);
    }

    private String hmacSha256Encode(String value, String key) {
        HMac hMac = new HMac(HmacAlgorithm.HmacSHA256, key.getBytes());
        return hMac.digestHex(value);
    }

    private String encryptChallenge(String originChallenge, DigestMod digestMod) {
        if (digestMod == DigestMod.MD5) {
            return md5Encode(originChallenge + this.privateKey);
        } else if (digestMod == DigestMod.SHA256) {
            return sha256Encode(originChallenge + this.privateKey);
        } else if (digestMod == DigestMod.HMAC_SHA256) {
            return hmacSha256Encode(originChallenge, this.privateKey);
        } else {
            return md5Encode(originChallenge + this.privateKey);
        }
    }

    private String requestRegister(Map<String, Object> params) {
        params.put("gt", this.captchaId);
        params.put("json_format", "1");
        params.put("sdk", GeetestConfig.VERSION);

        String url = GeetestConfig.API_URL + GeetestConfig.REGISTER_URL;
        log.debug("极验register URL: {}", url);

        try {
            String result = HttpUtil.get(url, params, GeetestConfig.TIMEOUT);
            log.debug("极验register响应: {}", result);
            JSONObject json = JSONUtil.parseObj(result);
            return json.getStr("challenge", "");
        } catch (Exception e) {
            log.error("极验register请求失败: {}", e.getMessage());
            return "";
        }
    }

    private GeetestResult buildRegisterResult(String originChallenge, DigestMod digestMod) {
        // challenge为空或为0表示失败，走宕机模式
        if (originChallenge == null || originChallenge.isEmpty() || "0".equals(originChallenge)) {
            // 本地生成随机challenge
            String challenge = UUID.randomUUID().toString().replace("-", "");
            JSONObject data = new JSONObject();
            data.set("success", 0);
            data.set("gt", this.captchaId);
            data.set("challenge", challenge);
            data.set("new_captcha", true);

            return new GeetestResult(0, data.toString(), "初始化接口失败，后续流程走宕机模式");
        } else {
            // 正常模式，加密challenge
            String challenge = encryptChallenge(originChallenge, digestMod != null ? digestMod : DigestMod.MD5);
            JSONObject data = new JSONObject();
            data.set("success", 1);
            data.set("gt", this.captchaId);
            data.set("challenge", challenge);
            data.set("new_captcha", true);

            return new GeetestResult(1, data.toString(), "");
        }
    }

    /**
     * 验证码初始化
     */
    public GeetestResult register(DigestMod digestMod, String userId, String clientType) {
        if (digestMod == null) digestMod = DigestMod.MD5;
        log.info("极验register开始: digest_mod={}", digestMod.getValue());

        Map<String, Object> params = new HashMap<>();
        params.put("digestmod", digestMod.getValue());
        params.put("user_id", StrUtil.blankToDefault(userId, GeetestConfig.USER_ID));
        params.put("client_type", StrUtil.blankToDefault(clientType, GeetestConfig.CLIENT_TYPE));

        String originChallenge = requestRegister(params);
        GeetestResult result = buildRegisterResult(originChallenge, digestMod);

        log.info("极验register完成: status={}", result.getStatus());
        return result;
    }

    /**
     * 本地初始化（宕机降级模式）
     */
    public GeetestResult localInit() {
        log.info("极验本地初始化（宕机模式）");
        return buildRegisterResult(null, null);
    }

    private String requestValidate(String challenge, String validate, String seccode, Map<String, Object> params) {
        params.put("seccode", seccode);
        params.put("json_format", "1");
        params.put("challenge", challenge);
        params.put("sdk", GeetestConfig.VERSION);
        params.put("captchaid", this.captchaId);

        String url = GeetestConfig.API_URL + GeetestConfig.VALIDATE_URL;

        try {
            String result = HttpUtil.post(url, params, GeetestConfig.TIMEOUT);
            log.debug("极验validate响应: {}", result);
            JSONObject json = JSONUtil.parseObj(result);
            return json.getStr("seccode", "");
        } catch (Exception e) {
            log.error("极验validate请求失败: {}", e.getMessage());
            return "";
        }
    }

    private boolean checkParams(String challenge, String validate, String seccode) {
        return StrUtil.isNotBlank(challenge) && StrUtil.isNotBlank(validate) && StrUtil.isNotBlank(seccode);
    }

    /**
     * 正常模式下的二次验证
     */
    public GeetestResult successValidate(String challenge, String validate, String seccode, String userId, String clientType) {
        log.info("极验二次验证（正常模式）: challenge={}...", challenge != null && challenge.length() > 16 ? challenge.substring(0, 16) : challenge);

        if (!checkParams(challenge, validate, seccode)) {
            return new GeetestResult(0, "", "正常模式，本地校验，参数challenge、validate、seccode不可为空");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("user_id", StrUtil.blankToDefault(userId, GeetestConfig.USER_ID));
        params.put("client_type", StrUtil.blankToDefault(clientType, GeetestConfig.CLIENT_TYPE));

        String responseSeccode = requestValidate(challenge, validate, seccode, params);

        if (StrUtil.isBlank(responseSeccode)) {
            return new GeetestResult(0, "", "请求极验validate接口失败");
        } else if ("false".equals(responseSeccode)) {
            return new GeetestResult(0, "", "极验二次验证不通过");
        } else {
            return new GeetestResult(1, "", "");
        }
    }

    /**
     * 宕机模式下的二次验证
     */
    public GeetestResult failValidate(String challenge, String validate, String seccode) {
        log.info("极验二次验证（宕机模式）: challenge={}...", challenge != null && challenge.length() > 16 ? challenge.substring(0, 16) : challenge);

        if (!checkParams(challenge, validate, seccode)) {
            return new GeetestResult(0, "", "宕机模式，本地校验，参数challenge、validate、seccode不可为空");
        } else {
            return new GeetestResult(1, "", "");
        }
    }
}
