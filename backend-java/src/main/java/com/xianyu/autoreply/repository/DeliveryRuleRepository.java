package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.DeliveryRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DeliveryRuleRepository extends JpaRepository<DeliveryRule, Long> {
    List<DeliveryRule> findByKeyword(String keyword);
}
