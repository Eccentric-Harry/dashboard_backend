package com.personal_dashboard.backend.service.showcase;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductPageScraperTest {

    private static final URI BASE = URI.create("https://www.apple.com/in/iphone-18-pro/");

    @Test
    void candidatesLeadWithOgImageThenFollowPageOrderAcrossCssImgAndJson() {
        String html = """
                <html><head>
                <meta property="og:image" content="https://www.apple.com/v/p/images/meta/og.png?2026">
                <style>
                  .hero { background-image: url(/v/p/images/overview/hero_endframe__abc_large.jpg); }
                  .hero-2x { background-image: url(/v/p/images/overview/hero_endframe__abc_large_2x.jpg); }
                  .nav { background-image: url(/v/p/images/site/localnav/iphone__x_large.png); }
                  .ico { background-image: url(/v/p/images/overview/icon_a20__q_large.png); }
                  .first { background-image: url(/v/p/images/overview/hero_startframe__z_large.jpg); }
                </style></head><body>
                <img src="/v/p/images/overview/colors__c_medium.jpg"
                     srcset="/v/p/images/overview/colors__c_medium.jpg 1x, /v/p/images/overview/colors__c_large.jpg 2x">
                <img src="data:image/gif;base64,R0lGOD">
                <script>{"image":"https:\\/\\/www.apple.com\\/v\\/p\\/images\\/overview\\/chip__d_large.jpg"}</script>
                </body></html>""";
        Document doc = Jsoup.parse(html, BASE.toString());

        List<String> urls = ProductPageScraper.candidates(doc, html, BASE);

        assertThat(urls).containsExactly(
                "https://www.apple.com/v/p/images/meta/og.png?2026",
                "https://www.apple.com/v/p/images/overview/hero_endframe__abc_large.jpg",
                "https://www.apple.com/v/p/images/overview/colors__c_large.jpg",
                "https://www.apple.com/v/p/images/overview/chip__d_large.jpg");
    }

    @Test
    void sizeVariantsShareAStem() {
        assertThat(ProductPageScraper.stemOf("https://x.com/a/hero__abc_large_2x.jpg"))
                .isEqualTo(ProductPageScraper.stemOf("https://x.com/a/hero__abc_small.jpg"));
        assertThat(ProductPageScraper.stemOf("https://m.media-amazon.com/images/I/61PBLEFPoKL._SL1500_.jpg"))
                .isEqualTo(ProductPageScraper.stemOf("https://m.media-amazon.com/images/I/61PBLEFPoKL._SX679_.jpg"));
        assertThat(ProductPageScraper.bestVariant(List.of(
                "https://m.media-amazon.com/images/I/61P._SX679_.jpg",
                "https://m.media-amazon.com/images/I/61P._SL1500_.jpg")))
                .endsWith("._SL1500_.jpg");
    }

    @Test
    void largestSrcsetEntryWins() {
        assertThat(ProductPageScraper.largestInSrcset("a.jpg 480w, b.jpg 1440w, c.jpg 960w")).isEqualTo("b.jpg");
        assertThat(ProductPageScraper.largestInSrcset("a.jpg, b.jpg 2x")).isEqualTo("b.jpg");
        assertThat(ProductPageScraper.largestInSrcset("")).isNull();
    }

    @Test
    void chromeIsSkipped() {
        assertThat(ProductPageScraper.isChrome("https://x.com/images/icon_battery.png")).isTrue();
        assertThat(ProductPageScraper.isChrome("https://x.com/site/globalnav/bag.jpg")).isTrue();
        assertThat(ProductPageScraper.isChrome("https://x.com/hero_startframe__a_large.jpg")).isTrue();
        assertThat(ProductPageScraper.isChrome("https://x.com/logo.svg")).isTrue();
        assertThat(ProductPageScraper.isChrome("https://x.com/overview/color_burgundy__f_large.jpg")).isFalse();
        assertThat(ProductPageScraper.isChrome("https://x.com/overview/main_camera_endframe__f_large.jpg")).isFalse();
    }

    @Test
    void highlightsComeFromTheDescriptionWithoutShopTalkOrTheName() {
        String description = "iPhone 18 Pro. Total AI powerhouse. 48MP Main camera with variable aperture. "
                + "Big leap in battery life. Buy online with free delivery. A20 Pro chip.";

        assertThat(ProductPageScraper.highlights(description, "iPhone 18 Pro and iPhone 18 Pro Max"))
                .containsExactly("Total AI powerhouse", "48MP Main camera with variable aperture",
                        "Big leap in battery life", "A20 Pro chip");
        assertThat(ProductPageScraper.highlights(null, "x")).isEmpty();
    }

    @Test
    void heroSizeRules() {
        assertThat(ProductPageScraper.isHeroSized(new ImageProbe.Size(1440, 760))).isTrue();
        assertThat(ProductPageScraper.isHeroSized(new ImageProbe.Size(1500, 1500))).isTrue();
        assertThat(ProductPageScraper.isHeroSized(new ImageProbe.Size(696, 452))).isTrue();
        assertThat(ProductPageScraper.isHeroSized(new ImageProbe.Size(372, 452))).isFalse();
        assertThat(ProductPageScraper.isHeroSized(new ImageProbe.Size(1260, 300))).isFalse();
        assertThat(ProductPageScraper.isHeroSized(null)).isFalse();
    }

    @Test
    void imageHeadersGiveTheirSize() {
        byte[] png = new byte[32];
        png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
        png[18] = 0x05; png[19] = (byte) 0xa0; // width 1440
        png[22] = 0x02; png[23] = (byte) 0xf8; // height 760
        assertThat(ImageProbe.size(png)).isEqualTo(new ImageProbe.Size(1440, 760));

        // JPEG: SOI, an APP0 segment, then SOF0 with height 612 and width 1260.
        byte[] jpeg = {
                (byte) 0xff, (byte) 0xd8,
                (byte) 0xff, (byte) 0xe0, 0x00, 0x04, 0x00, 0x00,
                (byte) 0xff, (byte) 0xc0, 0x00, 0x11, 0x08, 0x02, 0x64, 0x04, (byte) 0xec, 0x03,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        };
        assertThat(ImageProbe.size(jpeg)).isEqualTo(new ImageProbe.Size(1260, 612));
        assertThat(ImageProbe.size(new byte[40])).isNull();
    }

    @Test
    void onlyPublicAddressesAreFetched() throws Exception {
        assertThat(SafeWebClient.isPublic(InetAddress.getByName("17.253.144.10"))).isTrue();
        assertThat(SafeWebClient.isPublic(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(SafeWebClient.isPublic(InetAddress.getByName("10.1.2.3"))).isFalse();
        assertThat(SafeWebClient.isPublic(InetAddress.getByName("192.168.1.10"))).isFalse();
        assertThat(SafeWebClient.isPublic(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(SafeWebClient.isPublic(InetAddress.getByName("100.100.1.1"))).isFalse();
        assertThat(SafeWebClient.isPublic(InetAddress.getByName("::1"))).isFalse();
        assertThat(SafeWebClient.isPublic(InetAddress.getByName("fd00::1"))).isFalse();
    }

    @Test
    void linksAreParsedLeniently() {
        assertThat(SafeWebClient.parseLink("apple.com/in/iphone-18-pro").toString())
                .isEqualTo("https://apple.com/in/iphone-18-pro");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SafeWebClient.parseLink("ftp://x.com/a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appleGoalsAreTriedAtApplesOwnAddress() {
        assertThat(ProductPageFinder.appleGuess("iPhone 18 Pro"))
                .contains(URI.create("https://www.apple.com/in/iphone-18-pro/"));
        assertThat(ProductPageFinder.appleGuess("iPhone 18 Pro 256GB Burgundy"))
                .contains(URI.create("https://www.apple.com/in/iphone-18-pro/"));
        assertThat(ProductPageFinder.appleGuess("Apple Watch Ultra 4"))
                .contains(URI.create("https://www.apple.com/in/apple-watch-ultra-4/"));
        assertThat(ProductPageFinder.appleGuess("Apple MacBook Air"))
                .contains(URI.create("https://www.apple.com/in/macbook-air/"));
        assertThat(ProductPageFinder.appleGuess("Goa in December")).isEmpty();
        assertThat(ProductPageFinder.appleGuess("Pixel 11 Pro")).isEmpty();
    }

    @Test
    void claudesReplyYieldsItsUrl() {
        assertThat(ProductPageFinder.firstUrl("https://www.apple.com/in/iphone-18-pro/."))
                .contains(URI.create("https://www.apple.com/in/iphone-18-pro/"));
        assertThat(ProductPageFinder.firstUrl("NONE")).isEmpty();
    }
}
