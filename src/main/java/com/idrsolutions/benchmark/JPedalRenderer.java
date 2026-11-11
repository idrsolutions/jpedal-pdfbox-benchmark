package com.idrsolutions.benchmark;

import org.jpedal.examples.images.ConvertPagesToImages;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * Renders pages with JPedal's {@link ConvertPagesToImages}, the class JPedal's documentation
 * recommends for converting PDF pages to {@link BufferedImage}s.
 */
final class JPedalRenderer implements PageRenderer {

    /** Points per inch in PDF user space; JPedal's page scaling is relative to this. */
    private static final float POINTS_PER_INCH = 72f;

    /** Output resolution in dots per inch. */
    private final float dpi;

    /** The converter for the open file, or null when no file is open. */
    private ConvertPagesToImages converter;

    /**
     * Creates a renderer that outputs pages at the given resolution.
     *
     * @param dpi output resolution in dots per inch
     */
    JPedalRenderer(float dpi) {
        this.dpi = dpi;
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "JPedal";
    }

    /** {@inheritDoc} */
    @Override
    public int open(Path file) throws Exception {
        converter = new ConvertPagesToImages(file.toString());
        converter.setPageScaling(dpi / POINTS_PER_INCH);
        if (!converter.openPDFFile()) {
            throw new IllegalStateException("JPedal could not open " + file);
        }
        return converter.getPageCount();
    }

    /** {@inheritDoc} */
    @Override
    public BufferedImage render(int pageIndex) throws Exception {
        return converter.getPageAsImage(pageIndex + 1, false);
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        if (converter != null) {
            converter.closePDFfile();
            converter = null;
        }
    }
}
