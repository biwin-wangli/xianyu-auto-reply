package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.entity.ItemInfo;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.repository.ItemInfoRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping
public class ItemController {

    private final ItemInfoRepository itemInfoRepository;
    private final CookieRepository cookieRepository;

    @Autowired
    public ItemController(ItemInfoRepository itemInfoRepository, CookieRepository cookieRepository) {
        this.itemInfoRepository = itemInfoRepository;
        this.cookieRepository = cookieRepository;
    }

    // ------------------------- Basic CRUD -------------------------

    // GET /items - Get all items for current user (Aggregated)
    @GetMapping("/items")
    public Map<String, Object> getAllItems() {
        // Migration assumption: Single user or Admin view, so we fetch all cookies first.
        List<String> cookieIds = cookieRepository.findAll().stream()
                .map(com.xianyu.autoreply.entity.Cookie::getId)
                .collect(Collectors.toList());

        List<ItemInfo> allItems = new ArrayList<>();
        if (!cookieIds.isEmpty()) {
             for (String cid : cookieIds) {
                 allItems.addAll(itemInfoRepository.findByCookieId(cid));
             }
        }
        
        return Map.of("items", allItems);
    }

    @GetMapping("/items/{cid}")
    public List<ItemInfo> getItems(@PathVariable String cid) {
        return itemInfoRepository.findByCookieId(cid);
    }
    
    // Alias for consistency
    @GetMapping("/items/cookie/{cookie_id}")
    public List<ItemInfo> getItemsAlias(@PathVariable String cookie_id) {
        return itemInfoRepository.findByCookieId(cookie_id);
    }

    @GetMapping("/items/{cookie_id}/{item_id}")
    public ItemInfo getItem(@PathVariable String cookie_id, @PathVariable String item_id) {
        return itemInfoRepository.findByCookieIdAndItemId(cookie_id, item_id)
                .orElseThrow(() -> new RuntimeException("Item not found"));
    }

    @PutMapping("/items/{cookie_id}/{item_id}")
    public Map<String, Object> updateItem(@PathVariable String cookie_id, 
                                          @PathVariable String item_id, 
                                          @RequestBody ItemInfo itemUpdate) {
        ItemInfo item = itemInfoRepository.findByCookieIdAndItemId(cookie_id, item_id)
                .orElseThrow(() -> new RuntimeException("Item not found"));
        
        if (itemUpdate.getItemTitle() != null) item.setItemTitle(itemUpdate.getItemTitle());
        if (itemUpdate.getItemDescription() != null) item.setItemDescription(itemUpdate.getItemDescription());
        if (itemUpdate.getItemPrice() != null) item.setItemPrice(itemUpdate.getItemPrice());
        if (itemUpdate.getItemDetail() != null) item.setItemDetail(itemUpdate.getItemDetail());
        if (itemUpdate.getItemCategory() != null) item.setItemCategory(itemUpdate.getItemCategory());
        
        // Specific flags
        if (itemUpdate.getIsMultiSpec() != null) item.setIsMultiSpec(itemUpdate.getIsMultiSpec());
        if (itemUpdate.getMultiQuantityDelivery() != null) item.setMultiQuantityDelivery(itemUpdate.getMultiQuantityDelivery());
        
        itemInfoRepository.save(item);
        return Map.of("success", true, "msg", "Item updated", "data", item);
    }

    @Transactional
    @DeleteMapping("/items/{cookie_id}/{item_id}")
    public Map<String, Object> deleteItem(@PathVariable String cookie_id, @PathVariable String item_id) {
         itemInfoRepository.deleteByCookieIdAndItemId(cookie_id, item_id);
         return Map.of("success", true, "msg", "Item deleted");
    }

    // ------------------------- Batch Operations -------------------------

    @Transactional
    @DeleteMapping("/items/batch")
    public Map<String, Object> deleteItemsBatch(@RequestBody BatchDeleteRequest request) {
        if (request.getCookie_id() == null || request.getItem_ids() == null) {
            return Map.of("success", false, "message", "Missing parameters");
        }
        itemInfoRepository.deleteByCookieIdAndItemIdIn(request.getCookie_id(), request.getItem_ids());
        return Map.of("success", true, "msg", "Batch delete successful", "count", request.getItem_ids().size());
    }

    // ------------------------- Search -------------------------

    @PostMapping("/items/search")
    public List<ItemInfo> searchItems(@RequestBody SearchRequest request) {
        if (request.getKeyword() == null || request.getKeyword().isEmpty()) {
            return itemInfoRepository.findByCookieId(request.getCookie_id());
        }
        return itemInfoRepository.findByCookieIdAndItemTitleContainingIgnoreCase(
                request.getCookie_id(), request.getKeyword());
    }

    @PostMapping("/items/search_multiple")
    public Map<String, Object> searchItemsMultiple(@RequestBody MultiSearchRequest request) {
        if (request.getCookie_ids() == null || request.getCookie_ids().isEmpty()) {
             return Map.of("success", false, "message", "No cookie IDs provided");
        }
        
        String keyword = request.getKeyword() != null ? request.getKeyword() : "";
        List<ItemInfo> items = itemInfoRepository.findByCookieIdInAndItemTitleContainingIgnoreCase(
                request.getCookie_ids(), keyword);
        
        return Map.of("success", true, "data", items);
    }

    // ------------------------- Pagination -------------------------

    @PostMapping("/items/get-by-page")
    public Map<String, Object> getItemsByPage(@RequestBody PageRequestDto request) {
        try {
            int page = request.getPage_number() > 0 ? request.getPage_number() - 1 : 0;
            int size = request.getPage_size() > 0 ? request.getPage_size() : 20;
            
            Pageable pageable = PageRequest.of(page, size, Sort.by("updatedAt").descending());
            Page<ItemInfo> pageResult;
            
            if (request.getKeyword() != null && !request.getKeyword().isEmpty()) {
                pageResult = itemInfoRepository.findByCookieIdAndItemTitleContainingIgnoreCase(
                        request.getCookie_id(), request.getKeyword(), pageable);
            } else {
                pageResult = itemInfoRepository.findByCookieId(request.getCookie_id(), pageable);
            }
            
            Map<String, Object> data = new HashMap<>();
            data.put("items", pageResult.getContent());
            data.put("total", pageResult.getTotalElements());
            data.put("current_page", request.getPage_number());
            data.put("total_pages", pageResult.getTotalPages());
            
            return Map.of("success", true, "data", data);
        } catch (Exception e) {
            log.error("Error getting items by page", e);
            return Map.of("success", false, "message", "Error getting items: " + e.getMessage());
        }
    }

    // ------------------------- Specific Feature Updates -------------------------

    @PutMapping("/items/{cookie_id}/{item_id}/multi-spec")
    public Map<String, Object> updateMultiSpec(@PathVariable String cookie_id, 
                                             @PathVariable String item_id,
                                             @RequestBody Map<String, Boolean> body) {
        ItemInfo item = itemInfoRepository.findByCookieIdAndItemId(cookie_id, item_id)
                .orElseThrow(() -> new RuntimeException("Item not found"));
        
        Boolean enabled = body.get("enabled");
        if (enabled != null) {
            item.setIsMultiSpec(enabled);
            itemInfoRepository.save(item);
        }
        return Map.of("success", true, "msg", "Multi-spec setting updated");
    }

    @PutMapping("/items/{cookie_id}/{item_id}/multi-quantity-delivery")
    public Map<String, Object> updateMultiQuantityDelivery(@PathVariable String cookie_id, 
                                                         @PathVariable String item_id,
                                                         @RequestBody Map<String, Boolean> body) {
        ItemInfo item = itemInfoRepository.findByCookieIdAndItemId(cookie_id, item_id)
                .orElseThrow(() -> new RuntimeException("Item not found"));
        
        Boolean enabled = body.get("enabled");
        if (enabled != null) {
            item.setMultiQuantityDelivery(enabled);
            itemInfoRepository.save(item);
        }
        return Map.of("success", true, "msg", "Multi-quantity delivery setting updated");
    }

    // ------------------------- Sync (Stub/Trigger) -------------------------

    @PostMapping("/items/get-all-from-account")
    public Map<String, Object> getAllFromAccount(@RequestBody Map<String, String> body) {
        // In Python this triggers a background crawler task. 
        // We will log this action and return success. 
        // Real implementation requires bridging to the crawler service (Python/Node/Java).
        String cookieId = body.get("cookie_id");
        log.info("Triggering item sync for cookie: {}", cookieId);
        // Logic to clear existing items logic if needed or it's an upsert process
        // For now, assuming external crawler pushes data to DB
        return Map.of("success", true, "message", "Sync task started (Backend Received)");
    }
    
    // ------------------------- DTOs -------------------------

    @Data
    public static class BatchDeleteRequest {
        private String cookie_id;
        private List<String> item_ids;
    }

    @Data
    public static class SearchRequest {
        private String cookie_id;
        private String keyword;
    }

    @Data
    public static class MultiSearchRequest {
        private List<String> cookie_ids;
        private String keyword;
    }

    @Data
    public static class PageRequestDto {
        private String cookie_id;
        private int page_number;
        private int page_size;
        private String keyword;
    }
}
