package com.personal_dashboard.backend.service.showcase;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Fetches pages and images the user points a savings goal at. Because the server fetches
 * URLs a user typed, every hop is checked before it is opened: http(s) on the default ports
 * only, and a host that resolves to public addresses only — never loopback, the LAN, link-
 * local (cloud metadata lives at 169.254.169.254) or carrier-grade NAT. Redirects are
 * followed by hand so each one is checked too, and reads stop at a byte cap.
 */
@Component
public class SafeWebClient {

    /** A desktop Safari agent: product pages serve their full markup (and its images) to browsers. */
    static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.0 Safari/605.1.15";
    private static final int MAX_REDIRECTS = 4;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** What came back: the final URL after redirects, its content type, and up to the byte cap of its body. */
    public record Fetched(URI uri, String contentType, byte[] body) {
        public boolean isImage() {
            return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
        }

        public boolean isHtml() {
            return contentType == null || contentType.toLowerCase(Locale.ROOT).contains("html");
        }
    }

    /** GETs a public http(s) URL, reading at most {@code maxBytes} of the body. */
    public Fetched get(URI uri, int maxBytes, Duration timeout) throws IOException {
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requirePublic(current);
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,image/avif,image/webp,image/*;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-IN,en;q=0.9")
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while fetching " + current.getHost(), e);
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                response.body().close();
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("Redirect without a location from " + uri.getHost()));
                current = current.resolve(location.trim().replace(" ", "%20"));
                continue;
            }
            try (InputStream body = response.body()) {
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP " + status + " from " + current.getHost());
                }
                String type = response.headers().firstValue("Content-Type").orElse(null);
                return new Fetched(current, type, body.readNBytes(maxBytes));
            }
        }
        throw new IOException("Too many redirects from " + uri.getHost());
    }

    /** Parses a user's link: http(s) only, scheme added when they pasted a bare host. */
    public static URI parseLink(String raw) {
        String link = raw == null ? "" : raw.trim();
        if (link.isEmpty()) throw new IllegalArgumentException("Paste a link first");
        if (!link.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) link = "https://" + link;
        try {
            URI uri = new URI(link.replace(" ", "%20"));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
                throw new IllegalArgumentException("That doesn't look like a web link");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("That doesn't look like a web link");
        }
    }

    static void requirePublic(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("Only web links can be opened");
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) {
            throw new IOException("Only standard web ports can be opened");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IOException("That link has no host");
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("Couldn't find " + host);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) throw new IOException(host + " isn't a public address");
        }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if (first == 0) return false;                                // "this network"
            if (first == 100 && second >= 64 && second <= 127) return false; // carrier-grade NAT
            if (first == 192 && second == 0 && (b[2] & 0xff) == 0) return false; // IETF protocol assignments
            if (first >= 240) return false;                              // reserved + broadcast
            return true;
        }
        if (address instanceof Inet6Address) {
            return (b[0] & 0xfe) != 0xfc;                                 // unique local fc00::/7
        }
        return false;
    }
}
