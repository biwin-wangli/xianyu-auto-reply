package com.xianyu.autoreply;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class XianyuAutoReplyApplication {

    public static void main(String[] args) {
        SpringApplication.run(XianyuAutoReplyApplication.class, args);
    }

}
