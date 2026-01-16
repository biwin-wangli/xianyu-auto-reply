package com.xianyu.autoreply.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xianyu.autoreply.converter.MapToJsonConverter;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Entity
@Table(name = "user_stats")
public class UserStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "anonymous_id", nullable = false, unique = true)
    @JsonProperty("anonymous_id")
    private String anonymousId;

    @CreationTimestamp
    @Column(name = "first_seen", updatable = false)
    @JsonProperty("first_seen")
    private LocalDateTime firstSeen;

    @UpdateTimestamp
    @Column(name = "last_seen")
    @JsonProperty("last_seen")
    private LocalDateTime lastSeen;

    @Column(name = "os")
    @JsonProperty("os")
    private String os;

    @Column(name = "version")
    @JsonProperty("version")
    private String version;
    
    @Column(name = "total_reports")
    @ColumnDefault("1")
    @JsonProperty("total_reports")
    private Integer totalReports = 1;
    
    // Additional field to store extra info if needed, though simple_stats only uses os/version extracted
    // But request body has 'info' dict.
    @Convert(converter = MapToJsonConverter.class) // Assuming this converter exists or we use String
    @Column(columnDefinition = "TEXT")
    private Map<String, Object> info; 
}
