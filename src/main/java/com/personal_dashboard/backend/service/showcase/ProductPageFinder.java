package com.personal_dashboard.backend.service.showcase;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.UserLocation;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * "Find photos" with no link: one short web-search call that names the official page for
 * what the user is saving for. The model only picks the page — the photos themselves are
 * read by {@link ProductPageScraper}, so nothing shown is invented. One click, one request,
 * at low effort; the result is stored on the goal and never re-run on its own.
 *
 * <p>Claude (web search) when its key is set, else Gemini with Google Search grounding —
 * the same primary/fallback pair as meal analysis.
 */
@Slf4j
@Component
public class ProductPageFinder {

    private static final String NO_CLAUDE_KEY = "ANTHROPIC_KEY_NOT_SET";
    private static final String NO_GEMINI_KEY = "GEMINI_KEY_NOT_SET";
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'`)\\]]+");

    private static final String SYSTEM = """
            You find the official web page for something a person in India is saving up for, so a \
            personal finance app can show its photos and highlights. Search the web, then choose one page:
            - Something to buy: the maker's own product page on its India site (for example \
            apple.com/in/iphone-…), else the maker's global product page, else an Indian retailer's \
            product page. Never a news article, review, video or comparison site.
            - A trip or place: the official tourism page for the destination, else its Wikipedia article.
            Pick the newest model that matches the name. Reply with only the URL on one line and nothing \
            else. If nothing fits, reply NONE.""";

    @Value("${ai.providers.claude.api-key}")
    private String apiKey;

    @Value("${ai.showcase.model:claude-opus-5-5}")
    private String model;

    @Value("${ai.showcase.timeout-ms:60000}")
    private long timeoutMs;

    @Value("${ai.providers.gemini.api-key:GEMINI_KEY_NOT_SET}")
    private String geminiKey;

    @Value("${ai.providers.gemini.endpoint:}")
    private String geminiEndpoint;

    private AnthropicClient client;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @PostConstruct
    void init() {
        if (hasClaude()) {
            client = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .build();
        }
    }

    public boolean available() {
        return hasClaude() || hasGemini();
    }

    private boolean hasClaude() {
        return apiKey != null && !apiKey.isBlank() && !NO_CLAUDE_KEY.equals(apiKey);
    }

    private boolean hasGemini() {
        return geminiKey != null && !geminiKey.isBlank() && !NO_GEMINI_KEY.equals(geminiKey)
                && geminiEndpoint != null && !geminiEndpoint.isBlank();
    }

    /** The official page for a goal, or empty when nothing fits. Throws when every provider failed. */
    public Optional<URI> find(String name, String kind) {
        String prompt = "Saving for: \"" + name + "\" (" + kindWords(kind) + ")";
        if (client != null) {
            try {
                return askClaude(name, prompt);
            } catch (RuntimeException e) {
                if (!hasGemini()) throw e;
                log.warn("Showcase page search: Claude failed ({}), trying Gemini", e.getMessage());
            }
        }
        if (hasGemini()) return askGemini(name, prompt);
        return Optional.empty();
    }

    private Optional<URI> askClaude(String name, String prompt) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addTool(WebSearchTool20260209.builder()
                        .maxUses(3L)
                        .userLocation(UserLocation.builder()
                                .country("IN")
                                .timezone("Asia/Kolkata")
                                .build())
                        .build())
                .addUserMessage(prompt)
                // On a policy decline the API re-runs the request on Anthropic's recommended
                // fallback model instead of returning a refusal.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();

        long started = System.currentTimeMillis();
        Message response = client.messages().create(params);
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"));
        log.info("Showcase page search (Claude) for '{}' took {}ms (stop={}, in={}, out={}): {}", name,
                System.currentTimeMillis() - started, response.stopReason().map(Object::toString).orElse("?"),
                response.usage().inputTokens(), response.usage().outputTokens(), text.replaceAll("\\s+", " "));
        if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            return Optional.empty();
        }
        return firstUrl(text);
    }

    /** Gemini with Google Search grounding, over REST like GeminiVisionProvider. */
    private Optional<URI> askGemini(String name, String prompt) {
        try {
            String body = json.writeValueAsString(Map.of(
                    "systemInstruction", Map.of("parts", List.of(Map.of("text", SYSTEM))),
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                    "tools", List.of(Map.of("google_search", Map.of())),
                    "generationConfig", Map.of(
                            "maxOutputTokens", 2048,
                            "thinkingConfig", Map.of("thinkingLevel", "low"))));
            long started = System.currentTimeMillis();
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(geminiEndpoint))
                            .timeout(Duration.ofMillis(timeoutMs))
                            .header("Content-Type", "application/json")
                            .header("x-goog-api-key", geminiKey)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Gemini returned HTTP " + response.statusCode());
            }
            JsonNode root = json.readTree(response.body());
            StringBuilder text = new StringBuilder();
            for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
                if (!part.path("thought").asBoolean(false)) text.append(part.path("text").asText("")).append('\n');
            }
            log.info("Showcase page search (Gemini) for '{}' took {}ms: {}", name,
                    System.currentTimeMillis() - started, text.toString().replaceAll("\\s+", " ").trim());
            return firstUrl(text.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while searching", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Gemini search failed: " + e.getMessage(), e);
        }
    }

    private static final Pattern APPLE_LINE = Pattern.compile(
            "^(?:apple\\s+)?(iphone|ipad|macbook|imac|mac\\s+(?:mini|studio|pro)|airpods|apple\\s+watch|watch|vision\\s+pro)\\b.*");
    private static final Pattern NOT_IN_SLUG = Pattern.compile(
            "\\b\\d+\\s?(?:gb|tb)\\b|\\b(?:black|white|silver|gold|blue|green|pink|purple|red|orange|burgundy|glacier"
                    + "|titanium|natural|desert|space|grey|gray|midnight|starlight|cosmic|deep|mist|sage|lavender)\\b|\\(.*?\\)");

    /**
     * Apple's product pages sit at predictable addresses (apple.com/in/iphone-18-pro/), so an
     * Apple goal is tried there first — free and instant, no model involved. Storage sizes and
     * colours in the goal's name ("iPhone 18 Pro 256GB Burgundy") are dropped from the slug.
     */
    public static Optional<URI> appleGuess(String name) {
        if (name == null) return Optional.empty();
        String lower = name.toLowerCase(java.util.Locale.ROOT).trim();
        if (!APPLE_LINE.matcher(lower).matches()) return Optional.empty();
        String slug = NOT_IN_SLUG.matcher(lower.replaceFirst("^apple\\s+(?!watch)", "")).replaceAll(" ")
                .trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) return Optional.empty();
        return Optional.of(URI.create("https://www.apple.com/in/" + slug + "/"));
    }

    /** The first http(s) URL in the model's reply, trailing punctuation trimmed. */
    static Optional<URI> firstUrl(String text) {
        if (text == null) return Optional.empty();
        Matcher m = URL.matcher(text);
        if (!m.find()) return Optional.empty();
        String url = m.group().replaceAll("[.,;:!?]+$", "");
        try {
            return Optional.of(SafeWebClient.parseLink(url));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String kindWords(String kind) {
        if (kind == null) return "something to buy";
        return switch (kind) {
            case "TRIP" -> "a trip or event";
            case "SAFETY_NET" -> "a safety net";
            case "OPEN" -> "open-ended saving";
            default -> "something to buy";
        };
    }
}
