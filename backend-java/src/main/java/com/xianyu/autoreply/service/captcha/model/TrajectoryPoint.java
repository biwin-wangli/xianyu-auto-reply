package com.xianyu.autoreply.service.captcha.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 轨迹点
 */
@Data
@AllArgsConstructor
public class TrajectoryPoint {
    private int x;  // X坐标
    private int y;  // Y坐标
    private double delay;  // 延迟（秒）
}
