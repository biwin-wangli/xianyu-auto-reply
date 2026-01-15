package com.xianyu.autoreply.service;

import com.xianyu.autoreply.entity.UserStats;
import com.xianyu.autoreply.repository.UserStatsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class StatsService {

    @Autowired
    private UserStatsRepository userStatsRepository;

    public void saveUserStats(String anonymousId, String os, String version) {
        UserStats stats = userStatsRepository.findByAnonymousId(anonymousId)
                .orElse(new UserStats());
        
        if (stats.getId() == null) {
            stats.setAnonymousId(anonymousId);
            stats.setTotalReports(1);
        } else {
            stats.setTotalReports(stats.getTotalReports() + 1);
        }
        
        stats.setOs(os);
        stats.setVersion(version);
        // lastSeen updated automatically by @UpdateTimestamp
        
        userStatsRepository.save(stats);
        log.info("User stats saved for: {}", anonymousId);
    }

    public Map<String, Object> getStatsSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        summary.put("total_users", userStatsRepository.count());
        // daily: last 24h
        summary.put("daily_active_users", userStatsRepository.countByLastSeenAfter(java.time.LocalDateTime.now().minusDays(1)));
        // weekly: last 7d
        summary.put("weekly_active_users", userStatsRepository.countByLastSeenAfter(java.time.LocalDateTime.now().minusDays(7)));
        
        // OS 
        List<Object[]> osStats = userStatsRepository.countByOs();
        Map<String, Long> osDist = new HashMap<>();
        for(Object[] row : osStats) {
            osDist.put((String)row[0], (Long)row[1]);
        }
        summary.put("os_distribution", osDist);
        
        // Version
        List<Object[]> verStats = userStatsRepository.countByVersion();
        Map<String, Long> verDist = new HashMap<>();
        for(Object[] row : verStats) {
            verDist.put((String)row[0], (Long)row[1]);
        }
        summary.put("version_distribution", verDist);
        
        return summary;
    }

    public List<UserStats> getRecentUsers() {
        return userStatsRepository.findTop20ByOrderByLastSeenDesc();
    }
}
