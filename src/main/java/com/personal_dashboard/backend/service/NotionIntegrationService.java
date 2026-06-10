package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class NotionIntegrationService {

    @Value("${notion.api.token}")
    private String notionApiToken;

    @Value("${notion.database.id}")
    private String notionDatabaseId;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public NotionIntegrationService(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    public String createNotionPage(String title) {
        try {
            log.info("Attempting to create Notion page for pursuit: {}", title);

            if (notionApiToken == null || notionApiToken.isBlank() || 
                notionApiToken.contains("NOTION_API_TOKEN") ||
                notionDatabaseId == null || notionDatabaseId.isBlank() ||
                notionDatabaseId.contains("NOTION_DATABASE_ID")) {
                log.warn("Notion token or database ID is not configured. Falling back to default URL.");
                return "https://notion.so";
            }

            // Detect and clean up the database/page ID (some Notion URLs contain page IDs with dashes)
            String cleanedId = notionDatabaseId.trim().replace("-", "");

            // Try creating the page as a child page of a Page parent first
            try {
                log.info("Attempting to create child page under parent Page ID: {}", cleanedId);
                return createPageWithPageParent(title, cleanedId);
            } catch (Exception pageError) {
                log.warn("Failed to create child page using page parent payload. Retrying with database parent payload... Error: {}", pageError.getMessage());
                try {
                    return createPageWithDatabaseParent(title, cleanedId);
                } catch (Exception dbError) {
                    log.error("Failed to create Notion page under both parent types. Database Error: {}", dbError.getMessage(), dbError);
                    throw dbError;
                }
            }
        } catch (Exception e) {
            log.error("Error occurred while creating page in Notion: ", e);
            return "https://notion.so";
        }
    }

    private String createPageWithPageParent(String title, String parentPageId) throws Exception {
        String url = "https://api.notion.com/v1/pages";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + notionApiToken);
        headers.set("Notion-Version", "2022-06-28");

        // Page Parent Schema:
        // {
        //   "parent": { "page_id": "PARENT_PAGE_ID" },
        //   "properties": {
        //     "title": [ { "text": { "content": "TITLE" } } ]
        //   }
        // }
        Map<String, Object> parent = new HashMap<>();
        parent.put("page_id", parentPageId);

        Map<String, Object> textContent = new HashMap<>();
        textContent.put("content", title);

        Map<String, Object> textObj = new HashMap<>();
        textObj.put("text", textContent);

        Map<String, Object> properties = new HashMap<>();
        properties.put("title", List.of(textObj));

        Map<String, Object> body = new HashMap<>();
        body.put("parent", parent);
        body.put("properties", properties);

        String requestBody = objectMapper.writeValueAsString(body);
        log.debug("Notion Request Payload (Page parent): {}", requestBody);

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        String responseStr = restTemplate.postForObject(url, entity, String.class);
        log.debug("Notion Response Payload: {}", responseStr);

        JsonNode root = objectMapper.readTree(responseStr);
        if (root.has("url")) {
            String pageUrl = root.get("url").asText();
            log.info("Successfully created child page under parent page. URL: {}", pageUrl);
            return pageUrl;
        } else {
            throw new RuntimeException("Notion response did not contain page url field");
        }
    }

    private String createPageWithDatabaseParent(String title, String parentDatabaseId) throws Exception {
        String url = "https://api.notion.com/v1/pages";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + notionApiToken);
        headers.set("Notion-Version", "2022-06-28");

        // Database Parent Schema:
        // {
        //   "parent": { "database_id": "DATABASE_ID" },
        //   "properties": {
        //     "Name": { "title": [ { "text": { "content": "TITLE" } } ] }
        //   }
        // }
        Map<String, Object> parent = new HashMap<>();
        parent.put("database_id", parentDatabaseId);

        Map<String, Object> textContent = new HashMap<>();
        textContent.put("content", title);

        Map<String, Object> textObj = new HashMap<>();
        textObj.put("text", textContent);

        Map<String, Object> titleProperty = new HashMap<>();
        titleProperty.put("title", List.of(textObj));

        Map<String, Object> properties = new HashMap<>();
        properties.put("Name", titleProperty);

        Map<String, Object> body = new HashMap<>();
        body.put("parent", parent);
        body.put("properties", properties);

        String requestBody = objectMapper.writeValueAsString(body);
        log.debug("Notion Request Payload (Database parent): {}", requestBody);

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        String responseStr = restTemplate.postForObject(url, entity, String.class);
        log.debug("Notion Response Payload: {}", responseStr);

        JsonNode root = objectMapper.readTree(responseStr);
        if (root.has("url")) {
            String pageUrl = root.get("url").asText();
            log.info("Successfully created database row. URL: {}", pageUrl);
            return pageUrl;
        } else {
            throw new RuntimeException("Notion response did not contain database row url field");
        }
    }
}
