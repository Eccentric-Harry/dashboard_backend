package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal_dashboard.backend.model.RdhCacheEntry;
import com.personal_dashboard.backend.repository.RdhCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheService {

    private final RdhCacheRepository rdhCacheRepository;
    private final ObjectMapper objectMapper;

    /**
     * Save data to the central Reference Data Hub (RDH)
     *
     * @param id The unique identifier for this cache (e.g., "workouts", "github")
     * @param data The JSON-serializable data to store
     * @param source The source of the data
     */
    public void saveCacheData(String id, Object data, String source) {
        try {
            RdhCacheEntry entry = RdhCacheEntry.builder()
                    .id(id)
                    .data(data)
                    .lastUpdated(Instant.now())
                    .status("SUCCESS")
                    .source(source)
                    .build();
            
            rdhCacheRepository.save(entry);
            log.info("Saved data to RDH cache for ID: {}", id);
        } catch (Exception e) {
            log.error("Error saving data to RDH cache for ID: {}", id, e);
            throw new RuntimeException("Failed to save to RDH cache", e);
        }
    }

    /**
     * Retrieve and deserialize data from the central Reference Data Hub (RDH)
     *
     * @param id The unique identifier for this cache
     * @param clazz The class type to map the data to
     * @return Optional containing the mapped data if found, or empty otherwise
     */
    public <T> Optional<T> getCacheData(String id, Class<T> clazz) {
        try {
            Optional<RdhCacheEntry> entryOpt = rdhCacheRepository.findById(id);
            if (entryOpt.isPresent() && entryOpt.get().getData() != null) {
                log.info("Found cached data for ID: {} (updated: {})", id, entryOpt.get().getLastUpdated());
                T mappedData = objectMapper.convertValue(entryOpt.get().getData(), clazz);
                return Optional.of(mappedData);
            }
            log.warn("No cached data found for ID: {}", id);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to retrieve or map cached data for ID: {} to class: {}", id, clazz.getSimpleName(), e);
            return Optional.empty();
        }
    }
}
