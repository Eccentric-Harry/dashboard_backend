package com.personal_dashboard.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MealImagePreprocessorTest {

    private MealImagePreprocessor preprocessor;

    @BeforeEach
    void setUp() {
        preprocessor = new MealImagePreprocessor();
    }

    @Test
    void downscalesLargePhotoToMaxEdgeAndShrinksPayload() throws Exception {
        byte[] original = jpegOfSize(4032, 3024);

        byte[] result = preprocessor.downscale(original);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(1024, decoded.getWidth(), "longest edge should be capped at 1024px");
        assertEquals(768, decoded.getHeight(), "aspect ratio should be preserved");
        assertTrue(result.length < original.length, "downscaled payload should be smaller");
    }

    @Test
    void leavesAlreadySmallImageUntouched() throws Exception {
        byte[] original = jpegOfSize(800, 600);

        assertSame(original, preprocessor.downscale(original),
                "images within the ceiling should pass through without re-encoding");
    }

    @Test
    void passesThroughUndecodableBytesRatherThanFailing() {
        // Stands in for a format ImageIO cannot read (e.g. HEIC): a scan must still
        // proceed, since the provider may be able to decode what we cannot.
        byte[] garbage = "not-an-image".getBytes();

        assertSame(garbage, preprocessor.downscale(garbage));
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertNull(preprocessor.downscale(null));
        byte[] empty = new byte[0];
        assertSame(empty, preprocessor.downscale(empty));
    }

    @Test
    void preservesListOrderAndSize() throws Exception {
        List<byte[]> images = Arrays.asList(jpegOfSize(2000, 2000), jpegOfSize(400, 400));

        List<byte[]> prepared = preprocessor.prepare(images);

        assertEquals(2, prepared.size());
        assertEquals(1024, ImageIO.read(new ByteArrayInputStream(prepared.get(0))).getWidth());
        assertSame(images.get(1), prepared.get(1), "the small image should be the same instance");
    }

    @Test
    void portraitOrientationCapsTheHeight() throws Exception {
        byte[] result = preprocessor.downscale(jpegOfSize(1500, 3000));

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(1024, decoded.getHeight());
        assertEquals(512, decoded.getWidth());
    }

    /** A JPEG with enough visual variation that it doesn't compress to nothing. */
    private byte[] jpegOfSize(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        for (int i = 0; i < 40; i++) {
            g.setColor(new Color((i * 37) % 255, (i * 91) % 255, (i * 53) % 255));
            g.fillRect((i * width) / 40, 0, width / 40 + 1, height);
        }
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }
}
