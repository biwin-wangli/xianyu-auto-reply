package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.ItemInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ItemInfoRepository extends JpaRepository<ItemInfo, Long> {
    List<ItemInfo> findByCookieId(String cookieId);
    Page<ItemInfo> findByCookieId(String cookieId, Pageable pageable);
    
    Optional<ItemInfo> findByCookieIdAndItemId(String cookieId, String itemId);
    
    void deleteByCookieIdAndItemId(String cookieId, String itemId);
    
    // Batch delete
    void deleteByCookieIdAndItemIdIn(String cookieId, List<String> itemIds);
    
    // Search
    List<ItemInfo> findByCookieIdAndItemTitleContainingIgnoreCase(String cookieId, String keyword);
    
    // Search Multiple (CookieId IN list)
    List<ItemInfo> findByCookieIdInAndItemTitleContainingIgnoreCase(List<String> cookieIds, String keyword);
    
    // Page search
    Page<ItemInfo> findByCookieIdAndItemTitleContainingIgnoreCase(String cookieId, String keyword, Pageable pageable);
}
