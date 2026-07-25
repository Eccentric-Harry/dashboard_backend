package com.personal_dashboard.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Shrinks uploaded meal photos before they are sent to a vision model.
 *
 * <p>Vision models bill images by tile count, so a 12-megapixel phone photo costs
 * several times what a 1-megapixel one does — with no accuracy gain for ingredient
 * identification, which is what Stage 1 actually does. Capping the longest edge at
 * {@value #MAX_EDGE_PX} keeps plates, bowls and garnishes clearly legible while
 * cutting the image-token bill (and the upload payload) substantially.
 *
 * <p>Every failure path is non-fatal: unreadable or unsupported formats (notably
 * HEIC, which ImageIO cannot decode) fall through to the original bytes, so a scan
 * never fails because preprocessing did.
 */
@Component
@Slf4j
public class MealImagePreprocessor {

    /** Longest-edge ceiling in pixels. Below this, images are passed through untouched. */
    private static final int MAX_EDGE_PX = 1024;

    /** JPEG quality for the re-encoded image. 0.8 is visually clean at this size. */
    private static final float JPEG_QUALITY = 0.8f;

    /**
     * Downscales each image that exceeds {@link #MAX_EDGE_PX}, preserving list order
     * and size. Images that are already small, unreadable, or that would grow under
     * re-encoding are returned as-is.
     */
    public List<byte[]> prepare(List<byte[]> images) {
        if (images == null || images.isEmpty()) {
            return images;
        }
        List<byte[]> prepared = new ArrayList<>(images.size());
        for (byte[] original : images) {
            prepared.add(downscale(original));
        }
        return prepared;
    }

    /**
     * Returns a downscaled JPEG rendering of {@code original}, or {@code original}
     * itself when downscaling is unnecessary or impossible.
     */
    public byte[] downscale(byte[] original) {
        if (original == null || original.length == 0) {
            return original;
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(original));
            if (source == null) {
                // Unsupported format (e.g. HEIC) — hand the raw bytes to the provider,
                // which may still be able to decode it.
                log.debug("[MealImagePreprocessor] Unsupported image format; passing through {} bytes", original.length);
                return original;
            }

            int longestEdge = Math.max(source.getWidth(), source.getHeight());
            if (longestEdge <= MAX_EDGE_PX) {
                return original;
            }

            double scale = (double) MAX_EDGE_PX / longestEdge;
            int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));

            // TYPE_INT_RGB + white fill: JPEG has no alpha channel, so a transparent
            // PNG would otherwise encode its transparent regions as black.
            BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = target.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, targetWidth, targetHeight);
                g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            } finally {
                g.dispose();
            }

            byte[] encoded = encodeJpeg(target);
            if (encoded == null || encoded.length >= original.length) {
                return original;
            }

            log.info("[MealImagePreprocessor] {}x{} -> {}x{} ({} KB -> {} KB)",
                    source.getWidth(), source.getHeight(), targetWidth, targetHeight,
                    original.length / 1024, encoded.length / 1024);
            return encoded;
        } catch (Exception e) {
            log.warn("[MealImagePreprocessor] Downscale failed ({}); using original bytes", e.getMessage());
            return original;
        }
    }

    private byte[] encodeJpeg(BufferedImage image) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
