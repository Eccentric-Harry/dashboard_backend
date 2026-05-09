package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "rdh_cache")
public class RdhCacheEntry {
    @Id
    private String id;
    
    private Object data;
    
    private Instant lastUpdated;
    
    private String status;
    
    private String source;
}
