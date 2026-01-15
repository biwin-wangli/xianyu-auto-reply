package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.UserStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface UserStatsRepository extends JpaRepository<UserStats, Long> {
    
    Optional<UserStats> findByAnonymousId(String anonymousId);
    
    // Count active users since a given date
    long countByLastSeenAfter(LocalDateTime date);
    
    // Recent users
    List<UserStats> findTop20ByOrderByLastSeenDesc();
    
    @Query("SELECT u.os as os, COUNT(u) as count FROM UserStats u GROUP BY u.os ORDER BY count DESC")
    List<Object[]> countByOs();
    
    @Query("SELECT u.version as version, COUNT(u) as count FROM UserStats u GROUP BY u.version ORDER BY count DESC")
    List<Object[]> countByVersion();
}
