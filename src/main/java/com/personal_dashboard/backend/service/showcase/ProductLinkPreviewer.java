package com.personal_dashboard.backend.service.showcase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a product link into what a wishlist card needs — a name, one photo, the store and,
 * when the page says so, a price — without keeping any of it but text: the photo stays on
 * the store's own CDN and is only ever referenced by URL.
 *
 * <p>Always answers. Stores that turn servers away (Flipkart, Croma) or time out still give a
 * usable card from the link itself: the store from the domain and a name from the URL slug
 * ({@code /apple-iphone-15-black-128-gb/p/…} → "Apple iPhone 15 Black 128 GB"). The user can
 * then fix the name, type the price and paste an image address.
 *
 * <p>Fetching goes through {@link SafeWebClient}, so a pasted link can never reach the LAN.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductLinkPreviewer {

    private static final int PAGE_BYTES = 3 * 1024 * 1024;
    private static final Duration PAGE_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_TITLE = 120;

    /** What a link gave. {@code fetched} is false when only the URL could be read. */
    public record LinkPreview(String url, String title, String imageUrl, String store, BigDecimal price, boolean fetched) {
    }

    private static final Map<String, String> STORES = Map.ofEntries(
            Map.entry("amazon", "Amazon"), Map.entry("amzn", "Amazon"), Map.entry("flipkart", "Flipkart"),
            Map.entry("myntra", "Myntra"), Map.entry("ajio", "AJIO"), Map.entry("decathlon", "Decathlon"),
            Map.entry("croma", "Croma"), Map.entry("reliancedigital", "Reliance Digital"), Map.entry("nykaa", "Nykaa"),
            Map.entry("tatacliq", "Tata CLiQ"), Map.entry("meesho", "Meesho"), Map.entry("apple", "Apple"),
            Map.entry("ikea", "IKEA"), Map.entry("bigbasket", "BigBasket"), Map.entry("blinkit", "Blinkit"),
            Map.entry("zeptonow", "Zepto"), Map.entry("swiggy", "Swiggy"), Map.entry("jiomart", "JioMart"),
            Map.entry("vijaysales", "Vijay Sales"), Map.entry("snitch", "Snitch"), Map.entry("uniqlo", "Uniqlo"),
            Map.entry("hm", "H&M"), Map.entry("zara", "Zara"), Map.entry("nike", "Nike"), Map.entry("adidas", "adidas"),
            Map.entry("puma", "PUMA"), Map.entry("boat-lifestyle", "boAt"), Map.entry("samsung", "Samsung"),
            Map.entry("oneplus", "OnePlus"), Map.entry("mi", "Xiaomi"), Map.entry("lenskart", "Lenskart"),
            Map.entry("pepperfry", "Pepperfry"), Map.entry("urbanladder", "Urban Ladder"), Map.entry("firstcry", "FirstCry"));

    /** Second-level labels that sit in front of the real name: co.in, com.au. */
    private static final Set<String> GENERIC_SLD = Set.of("co", "com", "net", "org", "gov", "ac", "edu");
    /** URL segments that are routing, not a product name. */
    private static final Set<String> NOT_A_NAME = Set.of("dp", "p", "gp", "product", "products", "buy", "item", "itm",
            "shop", "store", "en", "in", "en-in", "collections", "catalog");
    private static final Set<String> UPPER_WORDS = Set.of("gb", "tb", "mb", "ml", "kg", "led", "usb", "hd", "fhd",
            "uhd", "oled", "amoled", "ssd", "ram", "tv", "ac", "uv", "spf", "xl", "xxl", "xs", "4k", "5g", "4g", "pc");
    /** Brand spellings a slug flattens: "iphone" → "iPhone". */
    private static final Map<String, String> BRAND_CASE = Map.ofEntries(
            Map.entry("iphone", "iPhone"), Map.entry("ipad", "iPad"), Map.entry("imac", "iMac"),
            Map.entry("macbook", "MacBook"), Map.entry("airpods", "AirPods"), Map.entry("oneplus", "OnePlus"),
            Map.entry("playstation", "PlayStation"), Map.entry("ps5", "PS5"), Map.entry("xbox", "Xbox"),
            Map.entry("boat", "boAt"), Map.entry("jbl", "JBL"), Map.entry("hp", "HP"), Map.entry("lg", "LG"),
            Map.entry("asus", "ASUS"), Map.entry("iqoo", "iQOO"), Map.entry("realme", "realme"));
    private static final Pattern PRICE_NUMBER = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)");
    private static final Pattern LD_PRICE = Pattern.compile("\"(?:lowPrice|price)\"\\s*:\\s*\"?(\\d[\\d,]*(?:\\.\\d+)?)");
    /** Store names and sales talk that pages append to their titles. */
    private static final Pattern TITLE_NOISE = Pattern.compile(
            "(?i)^buy\\s+|\\s+online\\s+at\\s+.*$|\\s+-\\s+buy\\s+.*$|\\s*[|:]\\s*(?:amazon|flipkart|myntra|ajio|croma|decathlon"
                    + "|nykaa|online shopping|buy online|shop online).*$");

    private final SafeWebClient web;

    public LinkPreview preview(String rawLink) {
        URI uri = SafeWebClient.parseLink(rawLink);
        LinkPreview fallback = fromUrl(uri);
        try {
            SafeWebClient.Fetched page = web.get(uri, PAGE_BYTES, PAGE_TIMEOUT);
            if (page.isImage()) {
                return new LinkPreview(fallback.url(), null, page.uri().toString(), null, null, true);
            }
            if (!page.isHtml()) {
                return fallback;
            }
            Document doc = Jsoup.parse(new String(page.body(), StandardCharsets.UTF_8), page.uri().toString());
            LinkPreview read = parse(doc, page.uri(), fromUrl(page.uri()));
            log.info("Link preview {}: title={}, image={}, price={}", page.uri().getHost(),
                    read.title() != null, read.imageUrl() != null, read.price());
            return read;
        } catch (IOException | RuntimeException e) {
            // Blocked, timed out or unparsable: the link alone still makes a card.
            log.info("Link preview fell back to the URL for {}: {}", uri.getHost(), e.getMessage());
            return fallback;
        }
    }

    // ─── Pure parts (tested) ─────────────────────────────────────────────

    /** Everything the page says, falling back field by field to what the URL said. */
    static LinkPreview parse(Document doc, URI finalUri, LinkPreview fallback) {
        String title = firstText(
                text(doc.selectFirst("#productTitle")),
                meta(doc, "meta[property=og:title]"),
                meta(doc, "meta[name=twitter:title]"),
                doc.title());
        String cleanTitle = cleanTitle(title);
        String image = firstText(
                attr(doc.selectFirst("#landingImage"), "data-old-hires"),
                meta(doc, "meta[property=og:image]"),
                meta(doc, "meta[property=og:image:secure_url]"),
                meta(doc, "meta[name=twitter:image]"),
                attr(doc.selectFirst("#landingImage"), "src"),
                attr(doc.selectFirst("link[rel=image_src]"), "href"));
        String store = firstText(storeName(finalUri), meta(doc, "meta[property=og:site_name]"));
        return new LinkPreview(
                finalUri.toString(),
                cleanTitle != null ? cleanTitle : fallback.title(),
                absolute(image, finalUri),
                store != null ? store : fallback.store(),
                price(doc),
                true);
    }

    /** What the link alone says: the store from the domain, a name from the slug. */
    static LinkPreview fromUrl(URI uri) {
        return new LinkPreview(uri.toString(), titleFromPath(uri.getPath()), null, storeName(uri), null, false);
    }

    /** "www.amazon.in" → "Amazon"; an unknown shop → its capitalised name ("bluetokai.com" → "Bluetokai"). */
    static String storeName(URI uri) {
        String host = uri.getHost();
        if (host == null) return null;
        String[] labels = host.toLowerCase(Locale.ROOT).replaceFirst("^(?:www|m|shop|store)\\.", "").split("\\.");
        if (labels.length == 0) return null;
        int index = labels.length >= 2 ? labels.length - 2 : 0;
        if (index > 0 && GENERIC_SLD.contains(labels[index])) index--;
        String name = labels[index];
        String known = STORES.get(name);
        if (known != null) return known;
        return name.isEmpty() ? null : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** The product-looking slug in a path, as words: the longest hyphenated segment that isn't routing or an id. */
    static String titleFromPath(String path) {
        if (path == null || path.isBlank()) return null;
        String best = null;
        for (String segment : path.split("/")) {
            String s = segment.trim();
            if (s.isEmpty() || NOT_A_NAME.contains(s.toLowerCase(Locale.ROOT))) continue;
            if (!s.contains("-") && !s.contains("_")) continue;
            long letters = s.chars().filter(Character::isLetter).count();
            if (letters < 4) continue;
            if (best == null || s.length() > best.length()) best = s;
        }
        if (best == null) return null;
        String decoded = java.net.URLDecoder.decode(best.replace("+", "%2B"), StandardCharsets.UTF_8)
                .replaceAll("\\.(?:html?|aspx?|php)$", "");
        List<String> words = new ArrayList<>();
        for (String word : decoded.split("[-_\\s]+")) {
            if (word.isBlank()) continue;
            String lower = word.toLowerCase(Locale.ROOT);
            if (BRAND_CASE.containsKey(lower)) words.add(BRAND_CASE.get(lower));
            else if (UPPER_WORDS.contains(lower)) words.add(lower.toUpperCase(Locale.ROOT));
            else if (word.chars().anyMatch(Character::isUpperCase)) words.add(word);
            else words.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        String title = String.join(" ", words);
        return title.isBlank() ? null : truncate(title);
    }

    /** Store suffixes and sales talk off; a title that is only noise reads as none. */
    static String cleanTitle(String raw) {
        if (raw == null) return null;
        String title = raw.replaceAll("\\s+", " ").trim();
        String previous;
        do {
            previous = title;
            title = TITLE_NOISE.matcher(title).replaceAll("").trim();
        } while (!title.equals(previous) && !title.isEmpty());
        if (title.length() < 3) return null;
        return truncate(title);
    }

    /** The page's price: structured data first (JSON-LD, product meta), then Amazon's price block. */
    static BigDecimal price(Document doc) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            Matcher m = LD_PRICE.matcher(script.data());
            if (m.find()) {
                BigDecimal value = number(m.group(1));
                if (value != null) return value;
            }
        }
        for (String selector : List.of("meta[property=product:price:amount]", "meta[property=og:price:amount]",
                "meta[itemprop=price]", "[itemprop=price][content]")) {
            BigDecimal value = number(meta(doc, selector));
            if (value != null) return value;
        }
        for (String selector : List.of("#corePriceDisplay_desktop_feature_div .a-price .a-offscreen",
                "#corePrice_feature_div .a-price .a-offscreen", ".priceToPay .a-offscreen", "#priceblock_ourprice",
                "#priceblock_dealprice")) {
            BigDecimal value = number(text(doc.selectFirst(selector)));
            if (value != null) return value;
        }
        return null;
    }

    private static BigDecimal number(String raw) {
        if (raw == null) return null;
        Matcher m = PRICE_NUMBER.matcher(raw);
        if (!m.find()) return null;
        try {
            BigDecimal value = new BigDecimal(m.group(1).replace(",", ""));
            return value.signum() > 0 && value.compareTo(new BigDecimal("100000000")) < 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String meta(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        return el == null ? null : blankToNull(el.attr("content"));
    }

    private static String attr(Element el, String name) {
        return el == null ? null : blankToNull(el.attr(name));
    }

    private static String text(Element el) {
        return el == null ? null : blankToNull(el.text());
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String absolute(String raw, URI base) {
        if (raw == null || raw.isBlank() || raw.startsWith("data:")) return null;
        try {
            URI resolved = base.resolve(raw.trim().replace(" ", "%20"));
            String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
            return scheme.equals("https") || scheme.equals("http") ? resolved.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String truncate(String value) {
        return value.length() <= MAX_TITLE ? value : value.substring(0, MAX_TITLE - 1).trim() + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
