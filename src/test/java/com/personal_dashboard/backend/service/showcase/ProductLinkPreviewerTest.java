package com.personal_dashboard.backend.service.showcase;

import com.personal_dashboard.backend.service.showcase.ProductLinkPreviewer.LinkPreview;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProductLinkPreviewerTest {

    private static Document doc(String html, String url) {
        return Jsoup.parse(html, url);
    }

    private static LinkPreview parse(String html, String url) {
        URI uri = URI.create(url);
        return ProductLinkPreviewer.parse(doc(html, url), uri, ProductLinkPreviewer.fromUrl(uri));
    }

    @Test
    void readsAnAmazonProductPage() {
        String html = """
                <html><head><title>Apple iPhone 15 (128 GB) - Black : Amazon.in: Electronics</title></head><body>
                <span id="productTitle"> Apple iPhone 15 (128 GB) - Black </span>
                <img id="landingImage" src="https://m.media-amazon.com/images/I/71x._SX300_.jpg"
                     data-old-hires="https://m.media-amazon.com/images/I/71657TiFeHL._SL1500_.jpg">
                <div id="corePriceDisplay_desktop_feature_div"><span class="a-price"><span class="a-offscreen">₹69,900.00</span></span></div>
                </body></html>""";

        LinkPreview p = parse(html, "https://www.amazon.in/Apple-iPhone-15-128-GB/dp/B0CHX1W1XY");

        assertEquals("Apple iPhone 15 (128 GB) - Black", p.title());
        assertEquals("https://m.media-amazon.com/images/I/71657TiFeHL._SL1500_.jpg", p.imageUrl());
        assertEquals("Amazon", p.store());
        assertEquals(0, new BigDecimal("69900.00").compareTo(p.price()));
        assertTrue(p.fetched());
    }

    @Test
    void readsOpenGraphAndJsonLd() {
        String html = """
                <html><head>
                <meta property="og:title" content="Men's Running Shoes Jogflow 100.1 - Black | Decathlon">
                <meta property="og:image" content="/p3106744/shoe.jpg?format=auto">
                <meta property="og:site_name" content="Decathlon India">
                <script type="application/ld+json">{"@type":"Product","offers":{"@type":"Offer","price":"1,999","priceCurrency":"INR"}}</script>
                </head></html>""";

        LinkPreview p = parse(html, "https://www.decathlon.in/p/8771124/jogflow");

        assertEquals("Men's Running Shoes Jogflow 100.1 - Black", p.title());
        assertEquals("https://www.decathlon.in/p3106744/shoe.jpg?format=auto", p.imageUrl());
        assertEquals("Decathlon", p.store());
        assertEquals(0, new BigDecimal("1999").compareTo(p.price()));
    }

    @Test
    void productMetaPriceIsReadWhenThereIsNoJsonLd() {
        String html = "<meta property='product:price:amount' content='2499.00'>";
        assertEquals(0, new BigDecimal("2499.00").compareTo(ProductLinkPreviewer.price(doc(html, "https://x.in/"))));
    }

    @Test
    void cleansStoreNoiseOffTitles() {
        assertEquals("Nike Pegasus 41", ProductLinkPreviewer.cleanTitle("Buy Nike Pegasus 41 Online at Best Prices in India"));
        assertEquals("Apple iPhone 15", ProductLinkPreviewer.cleanTitle("Apple iPhone 15 | Flipkart.com"));
        assertEquals("Apple iPhone 15 (128 GB)", ProductLinkPreviewer.cleanTitle("Apple iPhone 15 (128 GB) : Amazon.in: Electronics"));
        assertNull(ProductLinkPreviewer.cleanTitle("  "));
    }

    @Test
    void namesStoresFromDomains() {
        assertEquals("Amazon", ProductLinkPreviewer.storeName(URI.create("https://www.amazon.in/dp/X")));
        assertEquals("Amazon", ProductLinkPreviewer.storeName(URI.create("https://amzn.in/d/abc")));
        assertEquals("Amazon", ProductLinkPreviewer.storeName(URI.create("https://www.amazon.co.uk/dp/X")));
        assertEquals("Flipkart", ProductLinkPreviewer.storeName(URI.create("https://www.flipkart.com/x/p/itm1")));
        assertEquals("Bluetokai", ProductLinkPreviewer.storeName(URI.create("https://bluetokai.com/products/x")));
    }

    @Test
    void namesProductsFromTheirSlug() {
        assertEquals("Apple iPhone 15 Black 128 GB",
                ProductLinkPreviewer.titleFromPath("/apple-iphone-15-black-128-gb/p/itm6ac6485515ae4"));
        assertEquals("Apple iPhone 15 128 GB", ProductLinkPreviewer.titleFromPath("/Apple-iPhone-15-128-GB/dp/B0CHX1W1XY"));
        assertEquals("Nike Men Revolution 7 Running Shoes",
                ProductLinkPreviewer.titleFromPath("/sports-shoes/nike/nike-men-revolution-7-running-shoes/27460372/buy"));
        assertNull(ProductLinkPreviewer.titleFromPath("/dp/B0CHX1W1XY"));
        assertNull(ProductLinkPreviewer.titleFromPath(""));
    }

    @Test
    void aBlockedPageStillGivesACardFromTheLink() throws IOException {
        SafeWebClient web = mock(SafeWebClient.class);
        when(web.get(any(), anyInt(), any(Duration.class))).thenThrow(new IOException("HTTP 500 from www.flipkart.com"));

        LinkPreview p = new ProductLinkPreviewer(web).preview("flipkart.com/apple-iphone-15-black-128-gb/p/itm6ac6485515ae4");

        assertFalse(p.fetched());
        assertEquals("Flipkart", p.store());
        assertEquals("Apple iPhone 15 Black 128 GB", p.title());
        assertNull(p.imageUrl());
    }

    @Test
    void anImageLinkBecomesThePhoto() throws IOException {
        SafeWebClient web = mock(SafeWebClient.class);
        URI image = URI.create("https://cdn.example.com/shoe.jpg");
        when(web.get(any(), anyInt(), any(Duration.class))).thenReturn(new SafeWebClient.Fetched(image, "image/jpeg", new byte[0]));

        LinkPreview p = new ProductLinkPreviewer(web).preview(image.toString());

        assertEquals(image.toString(), p.imageUrl());
    }

    @Test
    void aNonWebLinkIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ProductLinkPreviewer(mock(SafeWebClient.class)).preview("ftp://x/y"));
    }
}
