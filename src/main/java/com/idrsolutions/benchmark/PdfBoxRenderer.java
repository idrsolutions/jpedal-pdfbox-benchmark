package com.idrsolutions.benchmark;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Renders pages with Apache PDFBox's {@link PDFRenderer}, using PDFBox's default rendering
 * settings, optionally with image subsampling allowed.
 *
 * <p>Subsampling is off by default in PDFBox. With it on, PDFBox may skip image pixels it does
 * not need at the output resolution, which its documentation says can be faster and use less
 * memory but can lose quality, especially in images with fine detail.
 */
final class PdfBoxRenderer implements PageRenderer {

    /** Output resolution in dots per inch. */
    private final float dpi;

    /** True to let PDFBox subsample images before drawing them. */
    private final boolean subsampling;

    /** The open document, or null when no file is open. */
    private PDDocument document;

    /** The renderer for the open document, or null when no file is open. */
    private PDFRenderer renderer;

    /**
     * Creates a renderer that outputs pages at the given resolution with PDFBox's defaults.
     *
     * @param dpi output resolution in dots per inch
     */
    PdfBoxRenderer(float dpi) {
        this(dpi, false);
    }

    /**
     * Creates a renderer that outputs pages at the given resolution.
     *
     * @param dpi         output resolution in dots per inch
     * @param subsampling true to let PDFBox subsample images before drawing them
     */
    PdfBoxRenderer(float dpi, boolean subsampling) {
        this.dpi = dpi;
        this.subsampling = subsampling;
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return subsampling ? "PDFBox (subsampling)" : "PDFBox";
    }

    /** {@inheritDoc} */
    @Override
    public int open(Path file) throws Exception {
        document = Loader.loadPDF(file.toFile());
        renderer = new PDFRenderer(document);
        renderer.setSubsamplingAllowed(subsampling);
        return document.getNumberOfPages();
    }

    /** {@inheritDoc} */
    @Override
    public BufferedImage render(int pageIndex) throws Exception {
        return renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        if (document != null) {
            try {
                document.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                document = null;
                renderer = null;
            }
        }
    }
}
