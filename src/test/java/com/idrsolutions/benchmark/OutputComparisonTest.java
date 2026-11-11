package com.idrsolutions.benchmark;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that {@link OutputComparison} passes renderings that differ only in the ways two PDF
 * libraries normally differ, and fails renderings where content is missing.
 *
 * <p>The pages are drawn from rectangles rather than fonts, so the tests give the same result on
 * every machine. The page size matches a US Letter page rendered at 150 DPI.
 */
class OutputComparisonTest {

    /** Width of a US Letter page at 150 DPI, in pixels. */
    private static final int WIDTH = 1275;

    /** Height of a US Letter page at 150 DPI, in pixels. */
    private static final int HEIGHT = 1650;

    /**
     * Draws a page of simulated text: rows of short black bars, like words in lines of text,
     * between 1-inch margins.
     *
     * @return the page
     */
    private static BufferedImage textPage() {
        BufferedImage img = blank();
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        Random rnd = new Random(1);
        for (int y = 150; y < HEIGHT - 150; y += 24) {
            int x = 150;
            while (x < WIDTH - 200) {
                int word = 20 + rnd.nextInt(60);
                g.fillRect(x, y, word, 12);
                x += word + 10;
            }
        }
        g.dispose();
        return img;
    }

    /**
     * Creates a white page.
     *
     * @return the page
     */
    private static BufferedImage blank() {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.dispose();
        return img;
    }

    /**
     * Copies an image, then fills a rectangle of the copy with a color.
     *
     * @param src   the image to copy
     * @param color the fill color
     * @param x     left edge of the rectangle
     * @param y     top edge of the rectangle
     * @param w     width of the rectangle
     * @param h     height of the rectangle
     * @return the modified copy
     */
    private static BufferedImage withRect(BufferedImage src, Color color, int x, int y, int w, int h) {
        BufferedImage img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.setColor(color);
        g.fillRect(x, y, w, h);
        g.dispose();
        return img;
    }

    /**
     * Copies an image and lightens every dark pixel to gray level 32, as when one library draws
     * text slightly lighter or thinner than the other. On a densely printed page this gives a mean
     * difference of about 8, close to the largest seen between JPedal and PDFBox on real pages
     * that show the same content.
     *
     * @param src the image to copy
     * @return the modified copy
     */
    private static BufferedImage lighterInk(BufferedImage src) {
        BufferedImage img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int rgb = src.getRGB(x, y);
                img.setRGB(x, y, (rgb & 0xff) < 128 ? 0x202020 : rgb);
            }
        }
        return img;
    }

    /** Identical pages pass. */
    @Test
    void identicalPagesPass() {
        BufferedImage page = textPage();
        OutputComparison.Result r = OutputComparison.compare(page, page);
        assertTrue(r.comparable(), r.toString());
        assertEquals(0.0, r.meanDiff(), 1e-9);
    }

    /** Text drawn slightly lighter, as with different antialiasing, still passes. */
    @Test
    void slightlyLighterTextPasses() {
        BufferedImage page = textPage();
        OutputComparison.Result r = OutputComparison.compare(page, lighterInk(page));
        assertTrue(r.comparable(), r.toString());
    }

    /**
     * Light gray content near the ink threshold, drawn a few gray levels lighter by one library,
     * still passes. Without the minimum tile difference for the ink test, this was a false alarm.
     */
    @Test
    void lightGrayShadeChangePasses() {
        BufferedImage a = withRect(blank(), new Color(226, 226, 226), 100, 100, 1000, 1400);
        BufferedImage b = withRect(blank(), new Color(234, 234, 234), 100, 100, 1000, 1400);
        OutputComparison.Result r = OutputComparison.compare(a, b);
        assertTrue(r.comparable(), r.toString());
    }

    /** A one-pixel size difference from rounding passes. */
    @Test
    void onePixelSizeDifferencePasses() {
        BufferedImage page = textPage();
        OutputComparison.Result r = OutputComparison.compare(page, page.getSubimage(0, 0, WIDTH - 1, HEIGHT - 1));
        assertTrue(r.comparable(), r.toString());
    }

    /** Pages that differ in size by more than one pixel fail. */
    @Test
    void differentSizeFails() {
        BufferedImage page = textPage();
        OutputComparison.Result r = OutputComparison.compare(page, page.getSubimage(0, 0, WIDTH - 10, HEIGHT));
        assertFalse(r.comparable());
        assertEquals("size", r.reason());
    }

    /** A blank rendering of a page with content fails. */
    @Test
    void blankPageFails() {
        OutputComparison.Result r = OutputComparison.compare(textPage(), blank());
        assertFalse(r.comparable());
        assertEquals("ink", r.reason());
    }

    /** A blank rendering of a page with only a few lines of text fails, though the page-wide difference is small. */
    @Test
    void blankSparsePageFails() {
        BufferedImage sparse = blank();
        Graphics2D g = sparse.createGraphics();
        g.setColor(Color.BLACK);
        for (int y = 150; y < 300; y += 24) {
            g.fillRect(150, y, 900, 12);
        }
        g.dispose();
        OutputComparison.Result r = OutputComparison.compare(sparse, blank());
        assertFalse(r.comparable(), r.toString());
    }

    /** A missing image covering about 6% of the page fails, though the page-wide figures barely move. */
    @Test
    void missingImageFails() {
        BufferedImage withImage = withRect(textPage(), new Color(90, 60, 30), 200, 200, 400, 300);
        BufferedImage without = withRect(textPage(), Color.WHITE, 200, 200, 400, 300);
        OutputComparison.Result r = OutputComparison.compare(withImage, without);
        assertFalse(r.comparable(), r.toString());
    }

    /** A missing block of text covering 5% of the page height fails. */
    @Test
    void missingTextBlockFails() {
        BufferedImage page = textPage();
        int h = HEIGHT / 20;
        OutputComparison.Result r = OutputComparison.compare(page, withRect(page, Color.WHITE, 0, (HEIGHT - h) / 2, WIDTH, h));
        assertFalse(r.comparable(), r.toString());
        assertEquals("tiles", r.reason());
    }

    /** Content in a different place, as with a substituted font of different width, fails. */
    @Test
    void shiftedContentFails() {
        BufferedImage page = textPage();
        BufferedImage shifted = blank();
        Graphics2D g = shifted.createGraphics();
        g.drawImage(page, 0, 12, null);
        g.dispose();
        OutputComparison.Result r = OutputComparison.compare(page, shifted);
        assertFalse(r.comparable(), r.toString());
    }
}
