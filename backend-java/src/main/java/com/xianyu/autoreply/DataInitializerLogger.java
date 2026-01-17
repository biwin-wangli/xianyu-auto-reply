package com.xianyu.autoreply;

import com.xianyu.autoreply.entity.SystemSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

@Component
public class DataInitializerLogger implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializerLogger.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ResourceLoader resourceLoader;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 检查初始化标志
        List<SystemSetting> systemSettings = jdbcTemplate.query("SELECT `key`, `value` FROM system_settings WHERE `key` = 'init_system'", new BeanPropertyRowMapper<>(SystemSetting.class));
        SystemSetting systemSetting = systemSettings.isEmpty() ? null : systemSettings.get(0);

        if (Objects.isNull(systemSetting)) {
            // 如果没有初始化过，则执行data.sql中的脚本
            Resource resource = resourceLoader.getResource("classpath:data.sql");
            try (InputStream inputStream = resource.getInputStream()) {
                String sqlScript = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
                // Split the SQL script into individual statements and execute them
                String[] statements = sqlScript.split(";");
                for (String statement : statements) {
                    String trimmedStatement = statement.trim();
                    // Remove single-line comments starting with --
                    trimmedStatement = trimmedStatement.replaceAll("--.*", "").trim();
                    if (!trimmedStatement.isEmpty()) {
                        jdbcTemplate.execute(trimmedStatement);
                    }
                }
            }

            // 在这里定义您的自定义日志内容
            logger.info("数据库首次初始化完成，默认数据已通过 data.sql 文件成功插入。");

            // 插入初始化标志，防止下次启动时再次执行
            jdbcTemplate.update("UPDATE system_settings SET value = ? WHERE `key` = ?", "true", "init_system");
        } else {
            logger.info("数据库已初始化，跳过默认数据插入。");
        }
    }
}
