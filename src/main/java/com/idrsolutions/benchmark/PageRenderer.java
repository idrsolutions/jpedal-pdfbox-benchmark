package com.idrsolutions.benchmark;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * One PDF library's PDF-to-image conversion, reduced to the three calls the benchmark times:
 * open a file, render a page, close the file. Each implementation uses the library's documented
 * public API and renders at the same DPI, so both libraries produce images of the same size.
 *
 * <p>To benchmark another library, implement this interface with a public constructor that
 * takes the output resolution as a {@code float} in dots per inch, put the class on the
 * classpath and pass its fully qualified name to {@code --libraries}. See {@link Libraries}.
 */
public interface PageRenderer extends AutoCloseable {

    /**
     * Returns the library name used in reports.
     *
     * @return the library name, for example "JPedal"
     */
    String name();

    /**
     * Opens a PDF file, ready for rendering.
     *
     * @param file the PDF to open
     * @return the number of pages in the file
     * @throws Exception if the library cannot open the file
     */
    int open(Path file) throws Exception;

    /**
     * Renders one page of the open file as an opaque RGB image at the configured DPI.
     *
     * @param pageIndex zero-based page index
     * @return the rendered page
     * @throws Exception if the library fails to render the page
     */
    BufferedImage render(int pageIndex) throws Exception;

    /**
     * Closes the open file and releases the library's resources for it.
     */
    @Override
    void close();
}
