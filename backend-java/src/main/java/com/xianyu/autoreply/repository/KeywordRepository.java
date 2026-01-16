package com.xianyu.autoreply.repository;

import com.xianyu.autoreply.entity.Keyword;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface KeywordRepository extends JpaRepository<Keyword, Long> {
    List<Keyword> findByCookieId(String cookieId);
    
    @Transactional
    @Modifying
    void deleteByCookieId(String cookieId);

    @Transactional
    @Modifying
    @Query("DELETE FROM Keyword k WHERE k.cookieId = :cookieId AND (k.type IS NULL OR k.type = 'text')")
    void deleteTextKeywordsByCookieId(@Param("cookieId") String cookieId);

    // Conflict Check: Find image keywords that clash
    // Python: SELECT type FROM keywords WHERE cookie_id = ? AND keyword = ? AND item_id = ? AND type = 'image'
    @Query("SELECT k FROM Keyword k WHERE k.cookieId = :cookieId AND k.keyword = :keyword AND k.itemId = :itemId AND k.type = 'image'")
    List<Keyword> findConflictImageKeywords(@Param("cookieId") String cookieId, @Param("keyword") String keyword, @Param("itemId") String itemId);

    // Conflict Check for Generic (Null ItemId)
    // Python: SELECT type FROM keywords WHERE cookie_id = ? AND keyword = ? AND (item_id IS NULL OR item_id = '') AND type = 'image'
    @Query("SELECT k FROM Keyword k WHERE k.cookieId = :cookieId AND k.keyword = :keyword AND (k.itemId IS NULL OR k.itemId = '') AND k.type = 'image'")
    List<Keyword> findConflictGenericImageKeywords(@Param("cookieId") String cookieId, @Param("keyword") String keyword);
}
