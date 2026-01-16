package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.UserStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserStatsRepository extends JpaRepository<UserStats, Long> {
    Optional<UserStats> findByAnonymousId(String anonymousId);
    
    @Query("SELECT COUNT(u) FROM UserStats u WHERE u.lastSeen >= :since")
    long countActiveUsersSince(LocalDateTime since);
    
    List<UserStats> findTop20ByOrderByLastSeenDesc();
    
    // For manual grouping in service or custom query for os/version distribution
}
