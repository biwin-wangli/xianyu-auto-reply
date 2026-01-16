package com.xianyu.autoreply.controller;

import com.xianyu.autoreply.entity.User;
import com.xianyu.autoreply.entity.Cookie;
import com.xianyu.autoreply.repository.UserRepository;
import com.xianyu.autoreply.repository.CookieRepository;
import com.xianyu.autoreply.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping
public class AdminController {

    private final UserRepository userRepository;
    private final CookieRepository cookieRepository;
    
    // Log directory - adjust as needed for migration context
    private final String LOG_DIR = "logs"; 

    private final OrderRepository orderRepository;

    @Autowired
    public AdminController(UserRepository userRepository, 
                           CookieRepository cookieRepository,
                           OrderRepository orderRepository) {
        this.userRepository = userRepository;
        this.cookieRepository = cookieRepository;
        this.orderRepository = orderRepository;
    }

    // ------------------------- User Management -------------------------

    @GetMapping("/admin/users")
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @GetMapping("/admin/stats")
    public Map<String, Object> getStats() {
        return Map.of(
            "user_count", userRepository.count(),
            "cookie_count", cookieRepository.count(),
            "order_count", orderRepository.count(),
            "log_count", 0 // Mock log count or implement if needed
        );
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
}
