package com.xianyu.autoreply.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Jackson 全局配置
 * 解决前端 snake_case 与后端 camelCase 字段命名不匹配的问题
 */
@Configuration
public class JacksonConfig {

    /**
     * 配置 Jackson ObjectMapper
     * 自动处理 snake_case 和 camelCase 之间的转换
     */
    @Bean
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper objectMapper = builder.createXmlMapper(false).build();
        
        // 设置属性命名策略：将 JSON 的 snake_case 映射到 Java 的 camelCase
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        
        return objectMapper;
    }
}
