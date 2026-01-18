package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.entity.User;
import com.xianyu.autoreply.repository.CardRepository;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.repository.KeywordRepository;
import com.xianyu.autoreply.repository.OrderRepository;
import com.xianyu.autoreply.repository.UserRepository;
import com.xianyu.autoreply.service.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping
public class AdminController extends BaseController {

    private final UserRepository userRepository;
    private final CookieRepository cookieRepository;
    private final OrderRepository orderRepository;
    private final CardRepository cardRepository;
    private final KeywordRepository keywordRepository;

    // Log directory - adjust as needed for migration context
    private final String LOG_DIR = "logs";

    @Autowired
    public AdminController(UserRepository userRepository, 
                           CookieRepository cookieRepository,
                           OrderRepository orderRepository,
                           CardRepository cardRepository,
                           KeywordRepository keywordRepository,
                           TokenService tokenService) {
        super(tokenService);
        this.userRepository = userRepository;
        this.cookieRepository = cookieRepository;
        this.orderRepository = orderRepository;
        this.cardRepository = cardRepository;
        this.keywordRepository = keywordRepository;
    }

    // ------------------------- User Management -------------------------

    @GetMapping("/admin/users")
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    /**
     * 获取系统统计信息（管理员专用）
     * 对应 Python: @app.get('/admin/stats')
     */
    @GetMapping("/admin/stats")
    public Map<String, Object> getStats(@RequestHeader(value = "Authorization", required = false) String token) {
        // 管理员权限校验
        validateAdminPermission(token);
        
        log.info("查询系统统计信息");
        
        // 1. 用户统计
        long totalUsers = userRepository.count();
        
        // 2. Cookie 统计
        long totalCookies = cookieRepository.count();
        
        // 3. 活跃账号统计（启用状态的账号）
        long activeCookies = cookieRepository.countByEnabled(true);
        
        // 4. 卡券统计
        long totalCards = cardRepository.count();
        
        // 5. 关键词统计
        long totalKeywords = keywordRepository.count();
        
        // 6. 订单统计
        long totalOrders = 0;
        try {
            totalOrders = orderRepository.count();
        } catch (Exception e) {
            // 兼容 Python 的异常处理
            log.warn("获取订单统计失败", e);
        }
        
        Map<String, Object> stats = Map.of(
            "total_users", totalUsers,
            "total_cookies", totalCookies,
            "active_cookies", activeCookies,
            "total_cards", totalCards,
            "total_keywords", totalKeywords,
            "total_orders", totalOrders
        );
        
        log.info("系统统计信息查询完成: {}", stats);
        return stats;
    }

    @DeleteMapping("/admin/users/{userId}")
    @Transactional
    public Map<String, Object> deleteUser(@PathVariable Long userId) {
        if (!userRepository.existsById(userId)) {
            return Map.of("success", false, "message", "User not found");
        }
        userRepository.deleteById(userId);
        return Map.of("success", true, "message", "User deleted");
    }

    @GetMapping("/admin/cookies")
    public List<Cookie> getAllCookies() {
        return cookieRepository.findAll();
    }

    // ------------------------- Log Management -------------------------

    @GetMapping("/admin/log-files")
    public List<String> getLogFiles() {
        try (Stream<Path> stream = Files.list(Paths.get(LOG_DIR))) {
            return stream
                    .filter(file -> !Files.isDirectory(file))
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".log"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Error listing log files", e);
            return List.of();
        }
    }

    @GetMapping("/admin/logs")
    public Map<String, Object> getLogs(@RequestParam(defaultValue = "100") int limit) {
         // Mock/Stub: Read global app.log or similar
         // Since Java logs depend on logback config, we return dummy or last N lines of a default file
         return Map.of("logs", readLastNLines(LOG_DIR + "/app.log", limit));
    }
    
    @GetMapping("/logs") // Alias for user-facing logs if any
    public Map<String, Object> getUserLogs() {
         return Map.of("logs", List.of()); // Implement specific user log logic if needed
    }

    // ------------------------- Backup Management -------------------------
    
    @GetMapping("/admin/backup/list")
    public List<String> getBackups() {
         // Mock backup list
         return List.of("backup_20250101.db", "backup_20250102.db");
    }
    
    // ------------------------- Utility -------------------------
    
    private List<String> readLastNLines(String filePath, int n) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) return List.of("Log file not found: " + filePath);
            List<String> allLines = Files.readAllLines(path);
            int start = Math.max(0, allLines.size() - n);
            return allLines.subList(start, allLines.size());
        } catch (IOException e) {
             return List.of("Error reading log file: " + e.getMessage());
        }
    }
    
    /**
     * 验证管理员权限
     * 对应 Python: require_admin
     */
    private void validateAdminPermission(String token) {
        if (token == null) {
            throw new RuntimeException("需要管理员权限");
        }
        
        String rawToken = token.replace("Bearer ", "");
        TokenService.TokenInfo tokenInfo = tokenService.verifyToken(rawToken);
        
        if (tokenInfo == null) {
            throw new RuntimeException("Token 无效");
        }
        
        // 检查是否为 admin 用户
        if (!"admin".equals(tokenInfo.username)) {
            throw new RuntimeException("需要管理员权限");
        }
    }
}
