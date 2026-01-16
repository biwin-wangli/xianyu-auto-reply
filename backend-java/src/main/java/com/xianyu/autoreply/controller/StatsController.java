package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.entity.UserStats;
import com.xianyu.autoreply.repository.UserStatsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
// Removing class level @RequestMapping to support root paths /statistics
public class StatsController {

    private final UserStatsRepository userStatsRepository;

    @Autowired
    public StatsController(UserStatsRepository userStatsRepository) {
        this.userStatsRepository = userStatsRepository;
    }

    @PostMapping("/statistics")
    public Map<String, Object> receiveUserStats(@RequestBody UserStatsDto data) {
        try {
            if (data.anonymous_id == null) {
                 return Map.of("status", "error", "message", "Missing anonymous_id");
            }
            
            String os = "unknown";
            String version = "2.2.0";
            
            if (data.info != null) {
                os = (String) data.info.getOrDefault("os", "unknown");
                version = (String) data.info.getOrDefault("version", "2.2.0");
            }
            
            UserStats stats = userStatsRepository.findByAnonymousId(data.anonymous_id)
                    .orElse(new UserStats());
            
            if (stats.getId() == null) {
                stats.setAnonymousId(data.anonymous_id);
                stats.setFirstSeen(LocalDateTime.now());
                stats.setTotalReports(1);
            } else {
                stats.setTotalReports(stats.getTotalReports() + 1);
            }
            stats.setLastSeen(LocalDateTime.now());
            stats.setOs(os);
            stats.setVersion(version);
            stats.setInfo(data.info);
            
            userStatsRepository.save(stats);
            
            log.info("Received user stats: {}", data.anonymous_id);
            return Map.of("status", "success", "message", "User stats received");
            
        } catch (Exception e) {
            log.error("Error saving stats", e);
            return Map.of("status", "error", "message", "Error saving stats");
        }
    }

    @GetMapping("/stats")
    public Map<String, Object> getSummary() {
        try {
            long totalUsers = userStatsRepository.count();
            long dailyActive = userStatsRepository.countActiveUsersSince(LocalDateTime.now().minusDays(1));
            long weeklyActive = userStatsRepository.countActiveUsersSince(LocalDateTime.now().minusDays(7));
            
            List<UserStats> all = userStatsRepository.findAll();
            
            Map<String, Long> osDistribution = all.stream()
                    .collect(Collectors.groupingBy(u -> u.getOs() == null ? "unknown" : u.getOs(), Collectors.counting()));
            
            Map<String, Long> versionDistribution = all.stream()
                    .collect(Collectors.groupingBy(u -> u.getVersion() == null ? "unknown" : u.getVersion(), Collectors.counting()));
            
            return Map.of(
                "total_users", totalUsers,
                "daily_active_users", dailyActive,
                "weekly_active_users", weeklyActive,
                "os_distribution", osDistribution,
                "version_distribution", versionDistribution,
                "last_updated", LocalDateTime.now().toString()
            );
        } catch (Exception e) {
             return Map.of("error", e.getMessage());
        }
    }

    @GetMapping("/stats/recent")
    public Map<String, Object> getRecentUsers() {
        List<UserStats> recent = userStatsRepository.findTop20ByOrderByLastSeenDesc();
        
        List<Map<String, Object>> mapped = recent.stream().map(u -> {
            String maskedId = u.getAnonymousId();
            if (maskedId.length() > 8) maskedId = maskedId.substring(0, 8) + "****";
            
            // Need to return specific keys
            Map<String, Object> m = new HashMap<>();
            m.put("anonymous_id", maskedId);
            m.put("first_seen", u.getFirstSeen());
            m.put("last_seen", u.getLastSeen());
            m.put("os", u.getOs());
            m.put("version", u.getVersion());
            m.put("total_reports", u.getTotalReports());
            return m;
        }).collect(Collectors.toList());
        
        return Map.of("recent_users", mapped);
    }
    
    // DTO class
    public static class UserStatsDto {
        public String anonymous_id;
        public String timestamp;
        public String project;
        public Map<String, Object> info;
    }
}
