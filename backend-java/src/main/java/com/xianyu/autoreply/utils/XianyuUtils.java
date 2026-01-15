package com.xianyu.autoreply.utils;

import cn.hutool.core.codec.Base64;
import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Slf4j
public class XianyuUtils {

    private static final String APP_KEY = "34839810";
    private static final ObjectMapper msgPackMapper = new ObjectMapper(new MessagePackFactory());
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    public static Map<String, String> transCookies(String cookiesStr) {
        if (!StringUtils.hasText(cookiesStr)) {
            throw new IllegalArgumentException("cookies cannot be empty");
        }
        Map<String, String> cookies = new HashMap<>();
        String[] parts = cookiesStr.split("; ");
        for (String part : parts) {
            if (part.contains("=")) {
                String[] split = part.split("=", 2);
                cookies.put(split[0], split[1]);
            }
        }
        return cookies;
    }

    public static String generateMid() {
        Random random = new Random();
        int randomPart = (int) (1000 * random.nextDouble());
        long timestamp = System.currentTimeMillis();
        return randomPart + String.valueOf(timestamp) + " 0";
    }

    public static String generateUuid() {
        long timestamp = System.currentTimeMillis();
        return "-" + timestamp + "1";
    }

    public static String generateDeviceId(String userId) {
        String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder result = new StringBuilder();
        Random random = new Random();

        for (int i = 0; i < 36; i++) {
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                result.append("-");
            } else if (i == 14) {
                result.append("4");
            } else {
                if (i == 19) {
                    int randVal = (int) (16 * random.nextDouble());
                    result.append(chars.charAt((randVal & 0x3) | 0x8));
                } else {
                    int randVal = (int) (16 * random.nextDouble());
                    result.append(chars.charAt(randVal));
                }
            }
        }
        return result.toString() + "-" + userId;
    }

    public static String generateSign(String t, String token, String data) {
        String msg = token + "&" + t + "&" + APP_KEY + "&" + data;
        return DigestUtil.md5Hex(msg);
    }

    public static String decrypt(String data) {
        try {
            // Clean up data
            String cleanData = data;
            // logic to match python's clean up if needed, but Java strings are UTF-8 usually.
            // Python: data.encode('utf-8', errors='ignore').decode('ascii', errors='ignore') 
            // This suggests removing non-ascii chars? 
            // In Java, we trust the input string mostly, but Base64 decode might need padding.
            
            // Base64 decode
            byte[] decodedBytes;
            try {
                decodedBytes = Base64.decode(cleanData);
            } catch (Exception e) {
                // Try padding
                int missingPadding = cleanData.length() % 4;
                if (missingPadding > 0) {
                    cleanData += "=".repeat(4 - missingPadding);
                    decodedBytes = Base64.decode(cleanData);
                } else {
                    throw e;
                }
            }

            // MessagePack decode to object (Map or others)
            Object decodedValue = msgPackMapper.readValue(decodedBytes, Object.class);

            // Convert to JSON string
            if (decodedValue instanceof Map || decodedValue instanceof java.util.List) {
               return jsonMapper.writeValueAsString(decodedValue);
            }
            return String.valueOf(decodedValue);

        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new RuntimeException("Decryption failed: " + e.getMessage());
        }
    }
}
