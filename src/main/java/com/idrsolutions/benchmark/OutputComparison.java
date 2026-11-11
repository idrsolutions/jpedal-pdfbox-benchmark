package com.idrsolutions.benchmark;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Decides whether two renderings of the same page show the same content, so that a timing
 * comparison only counts pages where both libraries did equivalent work.
 *
 * <p>Two libraries never produce identical pixels: antialiasing, font hinting and color
 * management differ. The check therefore compares the pages at a coarser grid, where those
 * differences average out but a missing image, missing text or a blank page does not. It
 * applies four checks with fixed thresholds:
 * <ul>
 *   <li>size: the image dimensions must agree to within one pixel;</li>
 *   <li>ink: the share of non-background cells must agree, which catches content that one
 *       library skipped. It only applies when the pages differ by at least
 *       {@link #MIN_DIFF_FOR_INK} gray levels on average;</li>
 *   <li>mean difference: the average gray-level difference per cell must be small;</li>
 *   <li>tiles: no tile of {@link #TILE} x {@link #TILE} cells may show content in one image
 *       that is missing or different in the other. This catches a skipped image or a missing
 *       block of text that is too small to move the whole-page figures.</li>
 * </ul>
 * The raw measurements go into the report, so a reader can apply stricter thresholds.
 */
final class OutputComparison {

    /** Side length in pixels of the square cell each page is averaged over before comparing. */
    static final int CELL = 4;

    /** Largest allowed width or height difference in pixels. */
    static final int MAX_SIZE_DIFF = 1;

    /** A cell darker than this gray level (0-255) counts as ink rather than background. */
    static final int INK_LEVEL = 230;

    /** Below this share of ink cells, a page counts as blank and the ink ratio is not checked. */
    static final double BLANK_INK = 0.002;

    /** Smallest allowed ratio between the lower and higher ink share of the two pages. */
    static final double MIN_INK_RATIO = 0.8;

    /** Largest allowed mean gray-level difference per cell (0-255). */
    static final double MAX_MEAN_DIFF = 12.0;

    /** Side length, in cells, of the square tiles checked for local differences. */
    static final int TILE = 16;

    /** A tile whose mean gray-level difference per cell exceeds this (0-255) is a mismatch. */
    static final double MAX_TILE_DIFF = 60.0;

    /** A tile with at least this ink share in either image is checked for missing ink. */
    static final double TILE_MIN_INK = 0.1;

    /** A checked tile whose lower-to-higher ink share ratio falls below this is a mismatch. */
    static final double TILE_MIN_INK_RATIO = 0.5;

    /**
     * The ink-ratio tests, for the page and for each tile, only apply where the mean gray-level
     * difference is at least this (0-255). Without it, light gray content near
     * {@link #INK_LEVEL} flips between ink and background on a difference of a few gray levels.
     */
    static final double MIN_DIFF_FOR_INK = 10.0;

    /**
     * The measurements for one page and the verdict drawn from them.
     *
     * @param widthA     width of the first image in pixels
     * @param heightA    height of the first image in pixels
     * @param widthB     width of the second image in pixels
     * @param heightB    height of the second image in pixels
     * @param inkA       share of ink cells in the first image (0-1)
     * @param inkB       share of ink cells in the second image (0-1)
     * @param meanDiff   mean gray-level difference per cell (0-255), or NaN if sizes differ
     * @param maxTileDiff largest mean gray-level difference of any tile (0-255), or NaN if sizes differ
     * @param badTiles   number of tiles that fail the tile check, or -1 if sizes differ
     * @param comparable true if the page passes all four checks
     * @param reason     the first check the page failed, or "ok"
     */
    record Result(int widthA, int heightA, int widthB, int heightB,
                  double inkA, double inkB, double meanDiff, double maxTileDiff, int badTiles,
                  boolean comparable, String reason) {
    }

    /** Utility class; not instantiated. */
    private OutputComparison() {
    }

    /**
     * Compares two renderings of the same page.
     *
     * @param a the first library's rendering
     * @param b the second library's rendering
     * @return the measurements and verdict
     */
    static Result compare(BufferedImage a, BufferedImage b) {
        int wa = a.getWidth(), ha = a.getHeight(), wb = b.getWidth(), hb = b.getHeight();
        if (Math.abs(wa - wb) > MAX_SIZE_DIFF || Math.abs(ha - hb) > MAX_SIZE_DIFF) {
            return new Result(wa, ha, wb, hb, Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1, false, "size");
        }
        int w = Math.min(wa, wb), h = Math.min(ha, hb);
        double[] ga = cells(a, w, h), gb = cells(b, w, h);
        double diff = 0;
        int inkCellsA = 0, inkCellsB = 0;
        for (int i = 0; i < ga.length; i++) {
            diff += Math.abs(ga[i] - gb[i]);
            if (ga[i] < INK_LEVEL) inkCellsA++;
            if (gb[i] < INK_LEVEL) inkCellsB++;
        }
        double meanDiff = diff / ga.length;
        double inkA = (double) inkCellsA / ga.length, inkB = (double) inkCellsB / gb.length;
        double hi = Math.max(inkA, inkB), lo = Math.min(inkA, inkB);

        int cw = (w + CELL - 1) / CELL, ch = (h + CELL - 1) / CELL;
        int badTiles = 0;
        double maxTileDiff = 0;
        for (int ty = 0; ty < ch; ty += TILE) {
            for (int tx = 0; tx < cw; tx += TILE) {
                double tileDiff = 0;
                int n = 0, tileInkA = 0, tileInkB = 0;
                for (int y = ty; y < Math.min(ty + TILE, ch); y++) {
                    for (int x = tx; x < Math.min(tx + TILE, cw); x++) {
                        int i = y * cw + x;
                        tileDiff += Math.abs(ga[i] - gb[i]);
                        if (ga[i] < INK_LEVEL) tileInkA++;
                        if (gb[i] < INK_LEVEL) tileInkB++;
                        n++;
                    }
                }
                tileDiff /= n;
                maxTileDiff = Math.max(maxTileDiff, tileDiff);
                double tHi = (double) Math.max(tileInkA, tileInkB) / n, tLo = (double) Math.min(tileInkA, tileInkB) / n;
                if (tileDiff > MAX_TILE_DIFF || (tileDiff >= MIN_DIFF_FOR_INK && tHi >= TILE_MIN_INK
                        && tLo / tHi < TILE_MIN_INK_RATIO)) badTiles++;
            }
        }

        String reason = "ok";
        if (meanDiff >= MIN_DIFF_FOR_INK && hi >= BLANK_INK && lo / hi < MIN_INK_RATIO) {
            reason = "ink";
        } else if (meanDiff > MAX_MEAN_DIFF) {
            reason = "pixels";
        } else if (badTiles > 0) {
            reason = "tiles";
        }
        return new Result(wa, ha, wb, hb, inkA, inkB, meanDiff, maxTileDiff, badTiles, reason.equals("ok"), reason);
    }

    /**
     * Averages the gray level of an image over square cells of {@link #CELL} pixels.
     *
     * @param img the image to average
     * @param w   width of the area to average, in pixels
     * @param h   height of the area to average, in pixels
     * @return the mean gray level (0-255) of each cell, row by row
     */
    private static double[] cells(BufferedImage img, int w, int h) {
        int cw = (w + CELL - 1) / CELL, ch = (h + CELL - 1) / CELL;
        double[] sum = new double[cw * ch];
        int[] count = new int[cw * ch];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            int base = (y / CELL) * cw;
            for (int x = 0; x < w; x++) {
                int p = row[x];
                int gray = (((p >> 16) & 0xff) * 299 + ((p >> 8) & 0xff) * 587 + (p & 0xff) * 114) / 1000;
                sum[base + x / CELL] += gray;
                count[base + x / CELL]++;
            }
        }
        for (int i = 0; i < sum.length; i++) sum[i] /= count[i];
        return sum;
    }

    /**
     * Writes the two renderings side by side as a PNG, for checking a verdict by eye.
     *
     * @param a      the first library's rendering, drawn on the left
     * @param b      the second library's rendering, drawn on the right
     * @param target the PNG file to write
     * @throws IOException if the file cannot be written
     */
    static void writeSideBySide(BufferedImage a, BufferedImage b, Path target) throws IOException {
        int gap = 10;
        BufferedImage out = new BufferedImage(a.getWidth() + gap + b.getWidth(),
                Math.max(a.getHeight(), b.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(a, 0, 0, null);
        g.drawImage(b, a.getWidth() + gap, 0, null);
        g.dispose();
        ImageIO.write(out, "png", target.toFile());
    }
}
