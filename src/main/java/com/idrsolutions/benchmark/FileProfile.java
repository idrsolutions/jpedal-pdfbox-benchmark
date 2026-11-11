package com.idrsolutions.benchmark;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Describes every PDF in a directory, so {@link Breakdown} can compare the libraries by kind of
 * file. Writes one CSV row per file with:
 * <ul>
 *   <li>the PDF version from the file's first line ({@code %PDF-1.x}), and the catalog's
 *       /Version if it overrides it;</li>
 *   <li>the page count;</li>
 *   <li>the content type: a file is image-heavy when image streams make up more than half its
 *       size, and takes the name of the compression that dominates (JPEG, Flate, black-and-white
 *       scan, JPEG 2000); otherwise it is text with small images or text and vector;</li>
 *   <li>its fonts: embedded and not embedded, CID (Type 0) and simple, and Type 3.</li>
 * </ul>
 * PDFBox reads the files here only to describe them; nothing is timed.
 *
 * <p>Usage: {@code java -cp benchmark.jar com.idrsolutions.benchmark.FileProfile <pdf-dir> <out.csv>}
 */
public final class FileProfile {

    /** Bytes searched for the version header; some files have junk before {@code %PDF}. */
    private static final int HEADER_SEARCH = 1024;

    /** Finds the version in a {@code %PDF-1.x} header. */
    private static final Pattern HEADER = Pattern.compile("%PDF-(\\d\\.\\d)");

    /** Deepest form XObject nesting followed when collecting fonts and images. */
    private static final int MAX_DEPTH = 8;

    /** Describes files; not instantiated. */
    private FileProfile() {
    }

    /**
     * Profiles every PDF in a directory and writes the CSV.
     *
     * @param args pdf-dir and output CSV path
     * @throws IOException if the directory cannot be read or the CSV cannot be written
     */
    public static void main(String[] args) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(Paths.get(args[0]))) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted().collect(Collectors.toList());
        }
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Paths.get(args[1])))) {
            out.println("file,header_version,catalog_version,pages,content_type,fonts_embedded,"
                    + "fonts_not_embedded,fonts_cid,fonts_simple,fonts_type3,error");
            for (Path f : files) {
                out.println(f.getFileName() + "," + profile(f));
            }
        }
        System.out.printf("Profiled %d files%n", files.size());
    }

    /**
     * Describes one PDF as CSV cells.
     *
     * @param file the PDF
     * @return header version, catalog version, pages, content type, five font counts and an
     *         error (empty when the file was read), comma-separated
     */
    static String profile(Path file) {
        String header = headerVersion(file);
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            String catalog = doc.getDocumentCatalog().getVersion();
            int[] fonts = new int[5];
            Map<String, Long> imageBytes = new HashMap<>();
            Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (PDPage page : doc.getPages()) {
                walk(page.getResources(), fonts, imageBytes, seen, 0);
            }
            return header + "," + (catalog == null ? "" : catalog) + "," + doc.getNumberOfPages() + ","
                    + contentType(imageBytes, Files.size(file)) + "," + fonts[0] + "," + fonts[1] + ","
                    + fonts[2] + "," + fonts[3] + "," + fonts[4] + ",";
        } catch (Throwable t) {
            return header + ",,,,,,,,," + t.getClass().getSimpleName();
        }
    }

    /**
     * Reads the version from the {@code %PDF-1.x} header near the start of the file.
     *
     * @param file the PDF
     * @return the version, for example "1.4", or an empty string if there is no header
     */
    static String headerVersion(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Matcher m = HEADER.matcher(new String(in.readNBytes(HEADER_SEARCH), StandardCharsets.ISO_8859_1));
            return m.find() ? m.group(1) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Counts the fonts and image bytes reachable from a resource dictionary, following form
     * XObjects. Each font and image is counted once, however many pages use it.
     *
     * @param res        the resources to scan
     * @param fonts      counts to add to: embedded, not embedded, CID, simple, Type 3
     * @param imageBytes encoded image bytes to add to, by compression
     * @param seen       objects already counted
     * @param depth      current form nesting depth
     */
    private static void walk(PDResources res, int[] fonts, Map<String, Long> imageBytes, Set<COSBase> seen,
                             int depth) {
        if (res == null || depth > MAX_DEPTH) return;
        for (COSName name : res.getFontNames()) {
            try {
                PDFont font = res.getFont(name);
                if (font == null || !seen.add(font.getCOSObject())) continue;
                if (font instanceof PDType3Font) {
                    fonts[4]++;
                    continue;
                }
                fonts[font.isEmbedded() ? 0 : 1]++;
                fonts[font instanceof PDType0Font ? 2 : 3]++;
            } catch (IOException e) {
                // A font that can't be read is left out of the counts.
            }
        }
        COSDictionary xobjects = res.getCOSObject().getCOSDictionary(COSName.XOBJECT);
        if (xobjects == null) return;
        for (COSName name : xobjects.keySet()) {
            if (!(xobjects.getDictionaryObject(name) instanceof COSStream s) || !seen.add(s)) continue;
            if (COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE))) {
                imageBytes.merge(compression(s), s.getLength(), Long::sum);
            } else if (COSName.FORM.equals(s.getCOSName(COSName.SUBTYPE))) {
                walk(new PDFormXObject(s).getResources(), fonts, imageBytes, seen, depth + 1);
            }
        }
    }

    /**
     * Names an image stream's compression from its last filter.
     *
     * @param image the image stream
     * @return "JPEG", "JBIG2", "CCITT", "JPEG2000" or "Flate"
     */
    private static String compression(COSStream image) {
        COSBase filters = image.getFilters();
        COSName last = filters instanceof COSName n ? n
                : filters instanceof COSArray a && a.size() > 0 && a.getObject(a.size() - 1) instanceof COSName n2 ? n2
                : null;
        String f = last == null ? "" : last.getName();
        return f.contains("DCT") ? "JPEG" : f.contains("JBIG2") ? "JBIG2" : f.contains("CCITT") ? "CCITT"
                : f.contains("JPX") ? "JPEG2000" : "Flate";
    }

    /**
     * Classifies a file by how much of it is image data.
     *
     * @param imageBytes encoded image bytes by compression
     * @param fileSize   the file's size in bytes
     * @return the content type used in the breakdown tables
     */
    static String contentType(Map<String, Long> imageBytes, long fileSize) {
        long total = imageBytes.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) return "Text and vector";
        if (total * 2 < fileSize) return "Text with small images";
        String top = Collections.max(imageBytes.entrySet(), Map.Entry.comparingByValue()).getKey();
        return switch (top) {
            case "JPEG" -> "Mostly JPEG images";
            case "JBIG2", "CCITT" -> "Mostly black-and-white scans";
            case "JPEG2000" -> "Mostly JPEG 2000";
            default -> "Mostly Flate images";
        };
    }
}
