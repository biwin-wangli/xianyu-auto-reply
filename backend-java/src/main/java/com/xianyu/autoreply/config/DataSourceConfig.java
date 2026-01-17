package com.xianyu.autoreply.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.File;

@Configuration
public class DataSourceConfig {

    /**
     * 自定义 DataSource Bean，以确保在连接池初始化之前创建数据库目录。
     * @param properties 由 Spring Boot 自动配置并注入的、包含 application.yml 中所有 spring.datasource.* 配置的属性对象。
     * @return 配置好的 DataSource 实例。
     */
    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        // 从URL中提取文件路径 (e.g., "jdbc:sqlite:./db/xianyu_data.db" -> "./db/xianyu_data.db")
        String url = properties.getUrl();
        String path = url.replace("jdbc:sqlite:", "");
        File dbFile = new File(path);

        // 获取父目录
        File parentDir = dbFile.getParentFile();

        // 如果父目录不为空且不存在，则创建它
        if (parentDir != null && !parentDir.exists()) {
            if (parentDir.mkdirs()) {
                System.out.println("Successfully created database directory: " + parentDir.getAbsolutePath());
            } else {
                System.err.println("Failed to create database directory: " + parentDir.getAbsolutePath());
            }
        }

        // 使用 Spring Boot 的标准构建器来创建 DataSource，这样可以重用所有 application.yml 中的配置
        return properties.initializeDataSourceBuilder().build();
    }
}
