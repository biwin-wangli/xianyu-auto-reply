package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.entity.UserStats;
import com.xianyu.autoreply.service.StatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/stats") // Python used /stats and /statistics, we unify under /api/stats or map matches
public class StatsController {

    @Autowired
    private StatsService statsService;

    @PostMapping("/report") // Mapped from Python's POST /statistics
    public Map<String, Object> reportStats(@RequestBody UserStatsDto data) {
        if (data.anonymous_id == null) {
             return Map.of("status", "error", "message", "Missing anonymous_id");
        }
        
        String os = "unknown";
        String version = "unknown";
        
        if (data.info != null) {
            os = (String) data.info.getOrDefault("os", "unknown");
            version = (String) data.info.getOrDefault("version", "unknown");
        }
        
        statsService.saveUserStats(data.anonymous_id, os, version);
        
        return Map.of("status", "success", "message", "User stats saved");
    }

    @GetMapping("/summary") // Mapped from Python's GET /stats
    public Map<String, Object> getSummary() {
        return statsService.getStatsSummary();
    }

    @GetMapping("/recent") // Mapped from Python's GET /stats/recent
    public List<UserStats> getRecentUsers() {
        return statsService.getRecentUsers();
    }
    
    // DTO class
    public static class UserStatsDto {
        public String anonymous_id;
        public String timestamp;
        public String project;
        public Map<String, Object> info;
    }
}
