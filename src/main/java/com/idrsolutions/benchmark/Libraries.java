package com.idrsolutions.benchmark;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Looks up the {@link PageRenderer} for a library by the name given on the command line.
 *
 * <p>"jpedal", "pdfbox" and "pdfbox-subsampled" (PDFBox with image subsampling allowed) are
 * built in. Any other name is taken as the fully qualified class
 * name of a {@link PageRenderer} on the classpath, which must have a public constructor taking
 * the output resolution as a {@code float} in dots per inch. So testing another library means
 * writing one class and naming it, with no change to the benchmark itself.
 */
final class Libraries {

    /** Default pair of libraries compared when none is given. */
    static final String DEFAULT = "jpedal,pdfbox";

    /** Library versions recorded at build time, for the built-in libraries. */
    private static final Properties VERSIONS = new Properties();

    static {
        try (InputStream in = Libraries.class.getResourceAsStream("/versions.properties")) {
            if (in != null) VERSIONS.load(in);
        } catch (IOException e) {
            // Versions are only reported in the summary; leave them unknown.
        }
    }

    /** Utility class; not instantiated. */
    private Libraries() {
    }

    /**
     * Returns a source of fresh renderers for a library.
     *
     * @param key "jpedal", "pdfbox", "pdfbox-subsampled", or the fully qualified class name of a
     *            {@link PageRenderer}
     * @param dpi output resolution in dots per inch
     * @return a supplier that creates a new renderer each time it is called
     * @throws IllegalArgumentException if the class cannot be found or has no {@code (float)} constructor
     */
    static Supplier<PageRenderer> supplier(String key, float dpi) {
        switch (key) {
            case "jpedal":
                return () -> new JPedalRenderer(dpi);
            case "pdfbox":
                return () -> new PdfBoxRenderer(dpi);
            case "pdfbox-subsampled":
                return () -> new PdfBoxRenderer(dpi, true);
            default:
                Constructor<? extends PageRenderer> constructor;
                try {
                    constructor = Class.forName(key).asSubclass(PageRenderer.class).getConstructor(float.class);
                } catch (ReflectiveOperationException | ClassCastException e) {
                    throw new IllegalArgumentException("Library must be jpedal, pdfbox or the class name of a "
                            + "PageRenderer with a public (float dpi) constructor: " + key, e);
                }
                return () -> {
                    try {
                        return constructor.newInstance(dpi);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("Could not create " + key, e);
                    }
                };
        }
    }

    /**
     * Describes the version of a library, for the summary's environment section.
     *
     * @param key the name the library was selected by
     * @return the version, or "unknown" if it cannot be found
     */
    static String version(String key) {
        switch (key) {
            case "jpedal":
                return VERSIONS.getProperty("jpedal.artifactId", "jpedal") + " "
                        + VERSIONS.getProperty("jpedal.version", "unknown");
            case "pdfbox":
            case "pdfbox-subsampled":
                return VERSIONS.getProperty("pdfbox.version", "unknown");
            default:
                try {
                    String v = Class.forName(key).getPackage().getImplementationVersion();
                    return v == null ? "unknown" : v;
                } catch (ClassNotFoundException e) {
                    return "unknown";
                }
        }
    }

    /**
     * Turns a library name into the form used in output file names: lower case, with anything
     * other than letters and digits replaced by underscores.
     *
     * @param key the name the library was selected by
     * @return the file-name form, for example "jpedal" or "com_example_myrenderer"
     */
    static String fileKey(String key) {
        return key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    /**
     * Splits a comma-separated pair of library names.
     *
     * @param list two names separated by a comma, for example "jpedal,pdfbox"
     * @return the two names
     * @throws IllegalArgumentException if the list does not hold exactly two different names
     */
    static String[] pair(String list) {
        String[] keys = list.split(",");
        if (keys.length != 2 || keys[0].isBlank() || keys[1].isBlank() || keys[0].equals(keys[1])) {
            throw new IllegalArgumentException("--libraries needs two different names, for example jpedal,pdfbox: " + list);
        }
        return new String[]{keys[0].trim(), keys[1].trim()};
    }
}
