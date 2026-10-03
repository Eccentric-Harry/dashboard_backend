package com.personal_dashboard.backend.service.showcase;

import com.personal_dashboard.backend.model.GoalPhoto;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Iterator;

/**
 * Reads what a showcase needs to know about a photo without a full image library: its size
 * from the first few kilobytes of the file (PNG, JPEG, GIF, WebP headers), and its tone —
 * whether its edges are studio-black or cut-out-white — from a coarse decode.
 */
final class ImageProbe {

    private ImageProbe() {
    }

    record Size(int width, int height) {
    }

    /** Pixel size from the file's header bytes; null when the format isn't recognised or the header is cut off. */
    static Size size(byte[] b) {
        if (b == null || b.length < 26) return null;
        // PNG: signature, then the IHDR chunk.
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return new Size(be32(b, 16), be32(b, 20));
        }
        // GIF: logical screen size, little-endian.
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return new Size(le16(b, 6), le16(b, 8));
        }
        // WebP: a RIFF container holding a VP8, VP8L or VP8X chunk.
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B'
                && b[11] == 'P' && b.length >= 30) {
            String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
            switch (chunk) {
                case "VP8 ":
                    return new Size(le16(b, 26) & 0x3fff, le16(b, 28) & 0x3fff);
                case "VP8L": {
                    int b1 = b[22] & 0xff, b2 = b[23] & 0xff, b3 = b[24] & 0xff;
                    int w = 1 + (((b1 & 0x3f) << 8) | (b[21] & 0xff));
                    int h = 1 + (((b3 & 0x0f) << 10) | (b2 << 2) | ((b1 & 0xc0) >> 6));
                    return new Size(w, h);
                }
                case "VP8X":
                    return new Size(1 + le24(b, 24), 1 + le24(b, 27));
                default:
                    return null;
            }
        }
        // JPEG: walk the segments to the first start-of-frame marker.
        if ((b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8) {
            int i = 2;
            while (i + 9 < b.length) {
                if ((b[i] & 0xff) != 0xff) {
                    i++;
                    continue;
                }
                int marker = b[i + 1] & 0xff;
                if (marker == 0xff) {
                    i++;
                    continue;
                }
                if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                    i += 2;
                    continue;
                }
                int length = be16(b, i + 2);
                boolean startOfFrame = marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
                if (startOfFrame) {
                    return new Size(be16(b, i + 7), be16(b, i + 5));
                }
                if (length < 2) return null;
                i += 2 + length;
            }
        }
        return null;
    }

    /**
     * DARK or LIGHT when the photo's border is near-black or near-white, else null. Decoded at
     * roughly 1/8 scale — only the edges matter, and a full decode of every photo is wasted work.
     */
    static String tone(byte[] bytes, Size size) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null; // WebP and friends: no reader in the JDK
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                int step = size == null ? 4 : Math.max(1, Math.min(size.width(), size.height()) / 120);
                param.setSourceSubsampling(step, step, 0, 0);
                return borderTone(reader.read(0, param));
            } finally {
                reader.dispose();
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    static String borderTone(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w < 4 || h < 4) return null;
        // The image is already decoded small (~120px on its short side), so a full pass is cheap.
        int band = Math.max(1, Math.round(Math.min(w, h) * 0.06f));
        double sum = 0;
        long count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean edge = y < band || y >= h - band || x < band || x >= w - band;
                if (!edge) continue;
                int rgb = image.getRGB(x, y);
                sum += 0.2126 * ((rgb >> 16) & 0xff) + 0.7152 * ((rgb >> 8) & 0xff) + 0.0722 * (rgb & 0xff);
                count++;
            }
        }
        double mean = sum / count;
        if (mean < 48) return GoalPhoto.DARK;
        if (mean > 218) return GoalPhoto.LIGHT;
        return null;
    }

    private static int be16(byte[] b, int i) {
        return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
    }

    private static int be32(byte[] b, int i) {
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    private static int le16(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8);
    }

    private static int le24(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8) | ((b[i + 2] & 0xff) << 16);
    }
}
