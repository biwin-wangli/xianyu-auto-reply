package com.xianyu.autoreply.config;

import cn.hutool.core.util.StrUtil;

/**
 * 极验验证码配置
 * 对应 Python: utils/geetest/geetest_config.py
 */
public class GeetestConfig {
    
    // 极验分配的captcha_id（从环境变量读取，有默认值）
    public static final String CAPTCHA_ID = StrUtil.blankToDefault(System.getenv("GEETEST_CAPTCHA_ID"), "0ab567879ad202caee10fa9e30329806");
    
    // 极验分配的私钥（从环境变量读取，有默认值）
    public static final String PRIVATE_KEY = StrUtil.blankToDefault(System.getenv("GEETEST_PRIVATE_KEY"), "e0517af788cb831d72f8886f9ba41ca3");
    
    // 用户标识（可选）
    public static final String USER_ID = StrUtil.blankToDefault(System.getenv("GEETEST_USER_ID"), "xianyu_system");
    
    // 客户端类型：web, h5, native, unknown
    public static final String CLIENT_TYPE = "web";
    
    // API地址
    public static final String API_URL = "http://api.geetest.com";
    public static final String REGISTER_URL = "/register.php";
    public static final String VALIDATE_URL = "/validate.php";
    
    // 请求超时时间（毫秒）- Python是5秒
    public static final int TIMEOUT = 5000;
    
    // SDK版本
    public static final String VERSION = "java-springboot:1.0.0";
}
