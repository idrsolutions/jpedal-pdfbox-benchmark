package com.idrsolutions.benchmark;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.JMException;
import javax.management.ObjectName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Measures the memory one library uses to convert every page of every PDF in a directory, using
 * the same renderers and resolution as {@link Benchmark}. The library is "jpedal", "pdfbox" or the
 * class name of any other {@link PageRenderer}; see {@link Libraries}. Run one library per JVM: memory one
 * library leaves behind could otherwise be collected while the other is being measured.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@code measure}: one discarded warmup pass, then one measured pass recording for each
 *       file the bytes the rendering thread allocated (the load on the garbage collector), the
 *       largest amount of memory held by live objects after any page (what the open document and
 *       the library's caches hold, relative to before the file was opened), and the heap still in
 *       use after closing the file (static caches or leaks). Writes
 *       {@code memory-<library>.csv}.</li>
 *   <li>{@code sweep}: renders every file once and records which ones fail. Run it at several
 *       {@code -Xmx} values to find the smallest heap each library needs for the corpus. Writes
 *       {@code sweep-<library>-<max heap MB>.csv}.</li>
 * </ul>
 * Peak heap including garbage is not reported: it depends on when the collector happens to run,
 * not on the library.
 *
 * <p>Usage: {@code java -cp benchmark.jar com.idrsolutions.benchmark.MemoryBenchmark
 * <measure|sweep> <library> <pdf-dir> <output-dir> [dpi]}
 */
public final class MemoryBenchmark {

    /** Source of per-thread allocation counts. */
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** Finds the live-bytes figure on the "Total" line of a class histogram. */
    private static final Pattern TOTAL = Pattern.compile("(?m)^Total\\s+\\d+\\s+(\\d+)\\s*$");

    /** Default output resolution in dots per inch, matching {@link Benchmark}. */
    private static final float DEFAULT_DPI = 150f;

    /** Accumulates image widths so the JIT cannot treat a rendered image as unused. */
    private static long sink;

    /** Runs the benchmark; not instantiated. */
    private MemoryBenchmark() {
    }

    /**
     * Runs the chosen mode for the chosen library over every PDF in a directory.
     *
     * @param args mode, library ("jpedal", "pdfbox" or a {@link PageRenderer} class name), pdf-dir,
     *             output-dir, then optionally dpi
     * @throws Exception if the corpus cannot be read or the CSV cannot be written
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: MemoryBenchmark <measure|sweep> <library> <pdf-dir> <output-dir> [dpi]");
            System.exit(1);
        }
        String mode = args[0], library = args[1];
        Path corpus = Paths.get(args[2]), out = Paths.get(args[3]);
        float dpi = args.length > 4 ? Float.parseFloat(args[4]) : DEFAULT_DPI;
        Supplier<PageRenderer> renderers = Libraries.supplier(library, dpi);
        Files.createDirectories(out);
        List<Path> files;
        try (Stream<Path> s = Files.walk(corpus)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted().collect(Collectors.toList());
        }
        ExecutorService[] worker = {Executors.newSingleThreadExecutor()};
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        System.out.printf("%s %s: %d PDFs, %.0f DPI, max heap %d MB%n", mode, library, files.size(), dpi, maxHeapMb);

        if (mode.equals("sweep")) {
            sweep(files, renderers, worker, out.resolve("sweep-" + Libraries.fileKey(library) + "-" + maxHeapMb + ".csv"));
        } else if (mode.equals("measure")) {
            System.out.println("Warmup pass...");
            for (Path f : files) measureFile(f, renderers.get(), worker, false);
            System.out.println("Measured pass...");
            try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("memory-" + Libraries.fileKey(library) + ".csv")))) {
                csv.println("file,pages,allocated_bytes,peak_retained_bytes,after_close_bytes,error");
                int done = 0;
                for (Path f : files) {
                    csv.println(f.getFileName() + "," + measureFile(f, renderers.get(), worker, true));
                    if (++done % 200 == 0) System.out.printf("  %d/%d files%n", done, files.size());
                }
            }
        } else {
            throw new IllegalArgumentException("Mode must be measure or sweep: " + mode);
        }
        worker[0].shutdownNow();
        System.out.println("Done (sink " + sink + ")");
        System.exit(0);
    }

    /**
     * Converts every page of a file and returns its memory figures as CSV cells.
     *
     * @param file     the PDF to convert
     * @param renderer a fresh renderer for the library under test
     * @param worker   holder for the thread that runs library calls, replaced after a timeout
     * @param measured true to run full GCs and measure retained heap, false for the warmup pass
     * @return pages, allocated bytes, peak retained bytes, after-close bytes and error, comma-separated
     */
    private static String measureFile(Path file, PageRenderer renderer, ExecutorService[] worker, boolean measured) {
        String[] cells = {null};
        String status = Benchmark.runWithTimeout(worker, () -> {
            long base = measured ? collectAndMeasure() : 0;
            long start = allocated();
            long allocatedTotal = 0, peak = Long.MIN_VALUE;
            int pages;
            try {
                pages = renderer.open(file);
                for (int i = 0; i < pages; i++) {
                    // Render in a separate method so no reference to the page image can outlive it: with the
                    // image still reachable, a large page (about 100MB at 150dpi for a poster) was counted as
                    // memory the library held.
                    renderAndDiscard(renderer, i);
                    allocatedTotal += allocated() - start;
                    if (measured) peak = Math.max(peak, collectAndMeasure() - base);
                    // Rebase after the GC so its own bookkeeping is not counted against the next page.
                    start = allocated();
                }
            } catch (Throwable t) {
                renderer.close();
                return "failed: " + t.getClass().getSimpleName();
            }
            renderer.close();
            long afterClose = measured ? collectAndMeasure() - base : 0;
            cells[0] = pages + "," + allocatedTotal + "," + (pages == 0 ? 0 : peak) + "," + afterClose + ",";
            return "ok";
        });
        return cells[0] != null ? cells[0] : ",,,," + status.replace("failed: ", "");
    }

    /**
     * Renders one page and lets the image go before returning, so the caller's measurement cannot
     * see it.
     *
     * @param renderer the library under test, with a file open
     * @param page     zero-based page index
     * @throws Exception if the library fails to render the page
     */
    private static void renderAndDiscard(PageRenderer renderer, int page) throws Exception {
        sink += renderer.render(page).getWidth();
    }

    /**
     * Renders every page of every file once, without measuring, and records which files fail.
     *
     * @param files     the PDFs to convert
     * @param renderers creates a fresh renderer for each file
     * @param worker    holder for the thread that runs library calls, replaced after a timeout
     * @param target    the CSV to write
     * @throws IOException if the CSV cannot be written
     */
    private static void sweep(List<Path> files, Supplier<PageRenderer> renderers, ExecutorService[] worker,
                              Path target) throws IOException {
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(target))) {
            csv.println("file,status");
            int done = 0, failed = 0;
            for (Path f : files) {
                PageRenderer renderer = renderers.get();
                String status = Benchmark.runWithTimeout(worker, () -> {
                    try {
                        int pages = renderer.open(f);
                        for (int i = 0; i < pages; i++) sink += renderer.render(i).getWidth();
                        return "ok";
                    } catch (Throwable t) {
                        return "failed: " + t.getClass().getSimpleName();
                    } finally {
                        renderer.close();
                    }
                });
                if (!status.equals("ok")) failed++;
                csv.println(f.getFileName() + "," + status);
                if (++done % 200 == 0) System.out.printf("  %d/%d files, %d failed%n", done, files.size(), failed);
            }
            System.out.printf("  %d of %d files failed%n", failed, files.size());
        }
    }

    /**
     * Reads the bytes the current thread has allocated so far.
     *
     * @return cumulative bytes allocated by this thread
     */
    @SuppressWarnings("deprecation")
    private static long allocated() {
        return THREADS.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    /**
     * Measures the bytes held by live objects, by asking the JVM for a class histogram: a full GC
     * followed by a walk of every reachable object. The heap's "used" figure is not reliable here.
     * After a full GC it can still include memory the collector has not yet returned, which showed
     * up as 100MB "held" after closing poster-size pages with no live object behind it.
     *
     * @return bytes held by reachable objects
     */
    @SuppressWarnings({"deprecation", "removal"})
    private static long collectAndMeasure() {
        System.runFinalization();
        try {
            String histogram = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                    new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                    new Object[]{null}, new String[]{String[].class.getName()});
            // The last line reads "Total <instances> <bytes>"
            Matcher m = TOTAL.matcher(histogram);
            if (m.find()) return Long.parseLong(m.group(1));
            throw new IllegalStateException("No total in class histogram");
        } catch (JMException e) {
            throw new IllegalStateException("Could not take a class histogram", e);
        }
    }
}
