package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.ItemInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ItemInfoRepository extends JpaRepository<ItemInfo, Long> {
    Optional<ItemInfo> findByCookieIdAndItemId(String cookieId, String itemId);
}
