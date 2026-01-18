-- 系统默认账号
INSERT OR IGNORE INTO users (username, email, password_hash) VALUES ('admin', 'admin@localhost', '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92');
-- 系统默认设置
INSERT OR IGNORE INTO system_settings (key, value, description)
VALUES ('init_system', 'false', '是否初始化'),
       ('theme_color', 'blue', '主题颜色'),
       ('registration_enabled', 'true', '是否开启用户注册'),
       ('show_default_login_info', 'true', '是否显示默认登录信息'),
       ('login_captcha_enabled', 'true', '登录滑动验证码开关'),
       ('smtp_server', '', 'SMTP服务器地址'),
       ('smtp_port', '587', 'SMTP端口'),
       ('smtp_user', '', 'SMTP登录用户名（发件邮箱）'),
       ('smtp_password', '', 'SMTP登录密码/授权码'),
       ('smtp_from', '', '发件人显示名（留空则使用用户名）'),
       ('smtp_use_tls', 'true', '是否启用TLS'),
       ('smtp_use_ssl', 'false', '是否启用SSL'),
       ('qq_reply_secret_key', 'xianyu_qq_reply_2024', 'QQ回复消息API秘钥');

