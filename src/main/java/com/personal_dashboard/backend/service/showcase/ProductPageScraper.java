package com.personal_dashboard.backend.service.showcase;

import com.personal_dashboard.backend.model.GoalPhoto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a product page into a showcase: its big photos and its own one-line highlights.
 *
 * <p>Product pages put their best shots in different places — {@code og:image}, {@code <img>}
 * and {@code srcset}, and (Apple) CSS backgrounds and JSON blobs — so candidates are gathered
 * from all of them, de-duplicated across size variants, then measured. Only real photos
 * survive: big enough to fill a hero (≥ 640×360), not a tall strip or a thin banner, and not
 * named like chrome (icons, logos, nav thumbnails, an animation's blank first frame).
 * {@code og:image} leads — it's the shot the maker picked to stand for the page — then page
 * order, which follows the page's own story.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductPageScraper {

    static final int MAX_PHOTOS = 16;
    private static final int MAX_CANDIDATES = 80;
    private static final int PAGE_BYTES = 6 * 1024 * 1024;
    private static final int HEADER_BYTES = 48 * 1024;
    private static final int PHOTO_BYTES = 4 * 1024 * 1024;
    private static final int MIN_WIDTH = 640;
    private static final int MIN_HEIGHT = 360;
    private static final Duration PAGE_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(8);

    /** Image URLs anywhere in the markup — CSS backgrounds and JSON included. */
    private static final Pattern IMAGE_URL = Pattern.compile(
            "(?:https?:)?/{1,2}[^\\s\"'()<>,\\\\]+?\\.(?:jpe?g|png|webp)(?:\\?[^\\s\"'()<>,\\\\]*)?",
            Pattern.CASE_INSENSITIVE);
    /** File names and folders that are page chrome, not product photos. */
    private static final Pattern CHROME = Pattern.compile(
            "(?:^|[^a-z])(?:icons?|logos?|sprites?|badges?|qr|qr_code|avatars?|favicons?|placeholders?|loader|spinner"
                    + "|pixel|spacer|arrows?|chevrons?|buttons?|startframe|blank|transparent|flags?|localnav|globalnav"
                    + "|footer)(?:[^a-z]|$)",
            Pattern.CASE_INSENSITIVE);
    /** Size suffixes that mark variants of one image: Apple's _large_2x, @2x, Amazon's ._SL1500_. */
    private static final Pattern VARIANT = Pattern.compile(
            "(?:_(?:xsmall|small|medium|large|xlarge))?(?:_2x|@2x|@3x)?(?:\\._[A-Z0-9_,]+_)?(\\.[a-z0-9]+)$",
            Pattern.CASE_INSENSITIVE);
    /** Description sentences that are a shop talking, not the product. */
    private static final Pattern SHOP_TALK = Pattern.compile(
            "\\b(?:buy|price|prices|online|shop|shopping|order|deliver|delivery|amazon|flipkart|offers?|deals?|emi|sale"
                    + "|check out|cash on|lowest)\\b",
            Pattern.CASE_INSENSITIVE);

    private final SafeWebClient web;

    /** What a link gave: a page's title, highlights and photos — or, for an image link, just that photo. */
    public record Scraped(URI finalUrl, boolean image, String title, List<String> highlights, List<GoalPhoto> photos) {
    }

    public Scraped scrape(URI uri) throws IOException {
        SafeWebClient.Fetched page = web.get(uri, PAGE_BYTES, PAGE_TIMEOUT);
        if (page.isImage()) {
            GoalPhoto photo = measure(page.uri().toString(), page.body());
            return new Scraped(page.uri(), true, null, List.of(), photo == null ? List.of() : List.of(photo));
        }
        if (!page.isHtml()) {
            throw new IOException("That link isn't a web page or an image");
        }
        String html = new String(page.body(), StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(html, page.uri().toString());
        String title = title(doc);
        List<String> highlights = highlights(description(doc), title);
        List<String> candidates = candidates(doc, html, page.uri());
        List<GoalPhoto> photos = measureAll(candidates);
        log.info("Showcase scrape {}: {} candidates → {} photos, {} highlights", page.uri().getHost(),
                candidates.size(), photos.size(), highlights.size());
        return new Scraped(page.uri(), false, title, highlights, photos);
    }

    // ─── Candidates (pure; tested) ───────────────────────────────────────

    static List<String> candidates(Document doc, String html, URI base) {
        // JSON blobs escape slashes ("https:\/\/…"); unescape so the URL pattern sees them.
        String text = html.replace("\\/", "/");
        Map<String, Integer> positions = new LinkedHashMap<>();
        List<String> pinned = new ArrayList<>();
        for (String selector : List.of("meta[property=og:image]", "meta[property=og:image:secure_url]",
                "meta[name=twitter:image]", "meta[property=twitter:image]")) {
            for (Element meta : doc.select(selector)) {
                String url = absolute(meta.attr("content"), base);
                if (url != null && !pinned.contains(url)) pinned.add(url);
            }
        }
        for (Element el : doc.select("link[rel=image_src]")) {
            String url = absolute(el.attr("href"), base);
            if (url != null && !pinned.contains(url)) pinned.add(url);
        }
        for (Element img : doc.select("img, source")) {
            for (String attr : List.of("src", "data-src", "data-lazy-src", "data-original")) {
                String raw = img.attr(attr);
                if (!raw.isBlank() && !raw.startsWith("data:")) note(positions, raw, absolute(raw, base), text);
            }
            for (String attr : List.of("srcset", "data-srcset")) {
                String raw = largestInSrcset(img.attr(attr));
                if (raw != null) note(positions, raw, absolute(raw, base), text);
            }
        }
        Matcher m = IMAGE_URL.matcher(text);
        while (m.find()) {
            String url = absolute(m.group(), base);
            if (url != null) positions.putIfAbsent(url, m.start());
        }

        List<String> ordered = new ArrayList<>(pinned);
        positions.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .forEach(ordered::add);

        // One URL per image: group size variants under a stem and keep the best variant.
        Map<String, List<String>> byStem = new LinkedHashMap<>();
        for (String url : ordered) {
            if (isChrome(url)) continue;
            byStem.computeIfAbsent(stemOf(url), k -> new ArrayList<>()).add(url);
        }
        return byStem.values().stream()
                .map(ProductPageScraper::bestVariant)
                .limit(MAX_CANDIDATES)
                .toList();
    }

    private static void note(Map<String, Integer> positions, String raw, String url, String text) {
        if (url == null) return;
        int at = text.indexOf(raw);
        positions.merge(url, at < 0 ? Integer.MAX_VALUE : at, Math::min);
    }

    static boolean isChrome(String url) {
        String path = pathOf(url).toLowerCase(Locale.ROOT);
        if (path.endsWith(".svg") || path.endsWith(".gif") || path.endsWith(".ico")) return true;
        return CHROME.matcher(path).find();
    }

    static String stemOf(String url) {
        return VARIANT.matcher(pathOf(url)).replaceFirst("");
    }

    /**
     * Apple's plain {@code _large} is ~1440px — sharp in a hero without the 2x file's weight;
     * a retailer's biggest {@code _SL<n>_} is its full-size shot; otherwise the first seen.
     */
    static String bestVariant(List<String> variants) {
        for (String v : variants) {
            if (pathOf(v).matches("(?i).*_large\\.[a-z0-9]+$")) return v;
        }
        Pattern sl = Pattern.compile("\\._SL(\\d+)_\\.");
        return variants.stream()
                .max(Comparator.comparingInt(v -> {
                    Matcher m = sl.matcher(v);
                    return m.find() ? Integer.parseInt(m.group(1)) : 0;
                }))
                .filter(v -> sl.matcher(v).find())
                .orElse(variants.get(0));
    }

    static String largestInSrcset(String srcset) {
        if (srcset == null || srcset.isBlank()) return null;
        String best = null;
        double bestScore = -1;
        for (String part : srcset.split(",\\s+")) {
            String[] bits = part.trim().split("\\s+");
            if (bits.length == 0 || bits[0].isBlank() || bits[0].startsWith("data:")) continue;
            double score = 1;
            if (bits.length > 1) {
                String d = bits[1].toLowerCase(Locale.ROOT);
                try {
                    score = Double.parseDouble(d.substring(0, d.length() - 1)) * (d.endsWith("x") ? 1000 : 1);
                } catch (NumberFormatException ignored) {
                    // Unknown descriptor: treat as 1x.
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = bits[0];
            }
        }
        return best;
    }

    private static String absolute(String raw, URI base) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI resolved = base.resolve(raw.trim().replace(" ", "%20").replace("&amp;", "&"));
            String scheme = resolved.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
            return resolved.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String pathOf(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    // ─── Text (pure; tested) ─────────────────────────────────────────────

    static String title(Document doc) {
        String t = firstContent(doc, "meta[property=og:title]", "meta[name=twitter:title]");
        if (t == null) t = doc.title();
        return clip(t, 90);
    }

    static String description(Document doc) {
        return firstContent(doc, "meta[property=og:description]", "meta[name=description]", "meta[name=twitter:description]");
    }

    /**
     * The description's short sentences as highlights — Apple writes these as a feature list
     * ("48MP Main camera with variable aperture. Big leap in battery life."). Shop talk and
     * the product's own name are dropped; at most six.
     */
    static List<String> highlights(String description, String title) {
        if (description == null || description.isBlank()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        String titleLower = title == null ? "" : title.toLowerCase(Locale.ROOT);
        for (String part : description.split("(?<=[.!?])\\s+|\\s+[|•·]\\s+")) {
            String line = part.trim().replaceAll("\\s+", " ").replaceAll("[.!?]+$", "").trim();
            if (line.length() < 4 || line.length() > 90) continue;
            if (SHOP_TALK.matcher(line).find()) continue;
            if (!titleLower.isEmpty() && titleLower.contains(line.toLowerCase(Locale.ROOT))) continue;
            out.add(line);
            if (out.size() == 6) break;
        }
        return List.copyOf(out);
    }

    private static String firstContent(Document doc, String... selectors) {
        for (String selector : selectors) {
            Element el = doc.selectFirst(selector);
            if (el != null && !el.attr("content").isBlank()) return el.attr("content").trim();
        }
        return null;
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1).trim() + "…";
    }

    // ─── Measuring ───────────────────────────────────────────────────────

    /** Probes each candidate's header for its size, keeps hero-sized photos, then reads the kept ones' tone. */
    private List<GoalPhoto> measureAll(List<String> candidates) {
        Semaphore gate = new Semaphore(12);
        List<ImageProbe.Size> sizes = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ImageProbe.Size>> heads = new ArrayList<>();
            for (String url : candidates) {
                heads.add(pool.submit(() -> {
                    gate.acquire();
                    try {
                        return ImageProbe.size(web.get(URI.create(url), HEADER_BYTES, IMAGE_TIMEOUT).body());
                    } catch (Exception e) {
                        return null;
                    } finally {
                        gate.release();
                    }
                }));
            }
            for (Future<ImageProbe.Size> head : heads) sizes.add(await(head));

            List<Integer> kept = new ArrayList<>();
            for (int i = 0; i < candidates.size() && kept.size() < MAX_PHOTOS; i++) {
                if (isHeroSized(sizes.get(i))) kept.add(i);
            }
            List<Future<String>> tones = new ArrayList<>();
            for (int i : kept) {
                String url = candidates.get(i);
                ImageProbe.Size size = sizes.get(i);
                tones.add(pool.submit(() -> {
                    gate.acquire();
                    try {
                        return ImageProbe.tone(web.get(URI.create(url), PHOTO_BYTES, IMAGE_TIMEOUT).body(), size);
                    } catch (Exception e) {
                        return null;
                    } finally {
                        gate.release();
                    }
                }));
            }
            List<GoalPhoto> photos = new ArrayList<>();
            for (int k = 0; k < kept.size(); k++) {
                int i = kept.get(k);
                photos.add(GoalPhoto.builder()
                        .url(candidates.get(i))
                        .width(sizes.get(i).width())
                        .height(sizes.get(i).height())
                        .tone(await(tones.get(k)))
                        .build());
            }
            return photos;
        }
    }

    /** One photo the user linked to directly; null when it's too small to show. */
    private GoalPhoto measure(String url, byte[] body) {
        ImageProbe.Size size = ImageProbe.size(body);
        if (size == null || size.width() < 320 || size.height() < 200) return null;
        return GoalPhoto.builder().url(url).width(size.width()).height(size.height())
                .tone(ImageProbe.tone(body, size)).build();
    }

    static boolean isHeroSized(ImageProbe.Size size) {
        if (size == null || size.width() < MIN_WIDTH || size.height() < MIN_HEIGHT) return false;
        double aspect = (double) size.width() / size.height();
        return aspect >= 0.75 && aspect <= 2.8;
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return null;
        }
    }

    /** For tests and the service: hosts read nicer without "www.". */
    public static String sourceName(URI uri) {
        String host = uri.getHost();
        if (host == null) return null;
        return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
    }
}
