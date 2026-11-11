package com.idrsolutions.benchmark;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Times PDF-to-image conversion with two libraries on a directory of PDFs, and only reports
 * timings for files where both libraries produced comparable output. The libraries are JPedal and
 * Apache PDFBox unless {@code --libraries} names others; see {@link Libraries}.
 *
 * <p>The run has three stages, all single-threaded in one JVM:
 * <ol>
 *   <li>Output check (untimed): renders every page with both libraries and compares the
 *       images with {@link OutputComparison}. A file counts in the headline results only if
 *       both libraries open it, agree on its page count and produce comparable output on every
 *       page.</li>
 *   <li>Warmup pass (discarded): times every file once so class loading and JIT compilation
 *       do not land on the measured passes.</li>
 *   <li>Measured passes: times every file again, several times. Each pass shuffles the file
 *       order and each file picks at random which library runs first, so neither library
 *       always inherits the other's cache or garbage-collection state. A full garbage
 *       collection runs before each timing for the same reason.</li>
 * </ol>
 * A file's time runs from creating the library's document object to finishing its last page,
 * so it includes opening and parsing the file. The random seed is printed and written to the
 * summary, so a run can be repeated exactly.
 *
 * <p>Usage: {@code java -jar benchmark.jar [--libraries a,b] <pdf-dir> <output-dir> [dpi] [passes] [seed]}
 */
public final class Benchmark {

    /** Default output resolution in dots per inch. */
    private static final float DEFAULT_DPI = 150f;

    /** Default number of measured passes. */
    private static final int DEFAULT_PASSES = 3;

    /** Default random seed for file order and library order. */
    private static final long DEFAULT_SEED = 20261105L;

    /** Longest one library may take on one file before the file is excluded. */
    private static final long TIMEOUT_SECONDS = 300;

    /** Most side-by-side images written for pages that fail the output check. */
    private static final int MAX_FAILED_SAMPLES = 50;

    /** Most side-by-side images written for failing pages of any one file. */
    private static final int MAX_FAILED_SAMPLES_PER_FILE = 2;

    /** Number of side-by-side images written for randomly chosen pages that pass the check. */
    private static final int PASSED_SAMPLES = 25;

    /** Bootstrap resamples used for the confidence interval of the geometric mean ratio. */
    private static final int BOOTSTRAP_RESAMPLES = 10_000;

    /** Accumulates image widths so the JIT cannot treat a rendered image as unused. */
    private static long sink;

    /** Display names of the two libraries under test, from {@link PageRenderer#name()}; set once in main. */
    private static final String[] NAMES = new String[2];

    /** The names the two libraries were selected by on the command line; set once in main. */
    private static final String[] KEYS = new String[2];

    /**
     * Timing of one library on one file.
     *
     * @param pageMs time for each page in milliseconds; page 1 includes opening the file
     * @param error  why the run failed, or null if it succeeded
     */
    private record Timing(double[] pageMs, String error) {
    }

    /** Runs the benchmark; not instantiated. */
    private Benchmark() {
    }

    /**
     * Runs the output check, warmup and measured passes, then writes the CSVs and summary.
     *
     * @param args optionally {@code --libraries a,b}, then pdf-dir, output-dir, and optionally dpi,
     *             passes and seed
     * @throws Exception if the corpus cannot be read or the reports cannot be written
     */
    public static void main(String[] args) throws Exception {
        String libraries = Libraries.DEFAULT;
        if (args.length >= 2 && args[0].equals("--libraries")) {
            libraries = args[1];
            args = java.util.Arrays.copyOfRange(args, 2, args.length);
        }
        if (args.length < 2) {
            System.err.println("Usage: java -jar benchmark.jar [--libraries a,b] <pdf-dir> <output-dir> [dpi] [passes] [seed]");
            System.exit(1);
        }
        String[] keys = Libraries.pair(libraries);
        Path corpus = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        float dpi = args.length > 2 ? Float.parseFloat(args[2]) : DEFAULT_DPI;
        int passes = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_PASSES;
        long seed = args.length > 4 ? Long.parseLong(args[4]) : DEFAULT_SEED;
        Files.createDirectories(out.resolve("samples"));

        List<Path> files;
        try (Stream<Path> s = Files.walk(corpus)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted().collect(Collectors.toList());
        }
        System.out.printf("%d PDFs, %.0f DPI, %d measured passes, seed %d%n", files.size(), dpi, passes, seed);

        Supplier<PageRenderer> libA = Libraries.supplier(keys[0], dpi);
        Supplier<PageRenderer> libB = Libraries.supplier(keys[1], dpi);
        KEYS[0] = keys[0];
        KEYS[1] = keys[1];
        NAMES[0] = libA.get().name();
        NAMES[1] = libB.get().name();
        System.out.printf("Comparing %s with %s%n", NAMES[0], NAMES[1]);
        ExecutorService[] worker = {Executors.newSingleThreadExecutor()};

        Map<Path, String> status = checkOutput(files, libA, libB, out, seed, worker);
        List<Path> timed = files.stream().filter(f -> !status.get(f).startsWith("failed"))
                .collect(Collectors.toList());

        // [pass][library 0=A,1=B] -> file -> timing
        List<Map<Path, Timing>[]> results = new ArrayList<>();
        Random rnd = new Random(seed);
        for (int pass = 0; pass <= passes; pass++) {
            boolean warmup = pass == 0;
            System.out.printf("%s pass%s...%n", warmup ? "Warmup" : "Measured",
                    warmup ? "" : " " + pass + "/" + passes);
            List<Path> order = new ArrayList<>(timed);
            Collections.shuffle(order, rnd);
            @SuppressWarnings("unchecked")
            Map<Path, Timing>[] byLib = new Map[]{new HashMap<>(), new HashMap<>()};
            long start = System.nanoTime();
            int done = 0;
            for (Path f : order) {
                boolean aFirst = rnd.nextBoolean();
                for (int k = 0; k < 2; k++) {
                    int lib = (k == 0) == aFirst ? 0 : 1;
                    byLib[lib].put(f, time(lib == 0 ? libA.get() : libB.get(), f, worker));
                }
                if (++done % 200 == 0) {
                    System.out.printf("  %d/%d files (%.0fs)%n", done, order.size(), (System.nanoTime() - start) / 1e9);
                }
            }
            if (!warmup) results.add(byLib);
        }
        worker[0].shutdownNow();

        writeTimings(out.resolve("timings.csv"), timed, results);
        writeSummary(out.resolve("summary.md"), files, status, results, dpi, passes, seed);
        System.out.println("Done: " + out.toAbsolutePath() + " (sink " + sink + ")");
        System.exit(0);
    }

    /**
     * Renders every page of every file with both libraries, compares the output and writes
     * {@code comparison.csv} plus side-by-side samples.
     *
     * @param files  the PDFs to check
     * @param rendersA creates a fresh renderer for the first library for each file
     * @param rendersB creates a fresh renderer for the second library for each file
     * @param out    the output directory
     * @param seed   seed for choosing which pages to write as side-by-side samples
     * @param worker holder for the thread that runs library calls, replaced after a timeout
     * @return each file's status: "comparable", "excluded: reason" or "failed: reason"
     * @throws IOException if the CSV cannot be written
     */
    private static Map<Path, String> checkOutput(List<Path> files, Supplier<PageRenderer> rendersA,
                                                 Supplier<PageRenderer> rendersB,
                                                 Path out, long seed, ExecutorService[] worker) throws IOException {
        System.out.println("Output check...");
        Map<Path, String> status = new LinkedHashMap<>();
        List<PageRef> passed = new ArrayList<>(), failed = new ArrayList<>();
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("comparison.csv")))) {
            String colA = column(0), colB = column(1);
            csv.println("file,page," + colA + "_w," + colA + "_h," + colB + "_w," + colB + "_h," + colA + "_ink," + colB + "_ink,mean_diff,max_tile_diff,bad_tiles,verdict");
            int done = 0;
            for (Path f : files) {
                String name = f.getFileName().toString();
                PageRenderer ra = rendersA.get(), rb = rendersB.get();
                List<OutputComparison.Result> pages = new ArrayList<>();
                String result = runWithTimeout(worker, () -> {
                    // Try both libraries even if the first fails, so a file either library cannot
                    // open is recorded against the right one.
                    int nj = -1, np = -1;
                    String jError = null, pError = null;
                    try {
                        nj = ra.open(f);
                    } catch (Throwable t) {
                        ra.close();
                        jError = NAMES[0] + " could not open (" + t.getClass().getSimpleName() + ")";
                    }
                    try {
                        np = rb.open(f);
                    } catch (Throwable t) {
                        rb.close();
                        pError = NAMES[1] + " could not open (" + t.getClass().getSimpleName() + ")";
                    }
                    if (jError != null || pError != null) {
                        ra.close();
                        rb.close();
                        return "failed: " + (jError == null ? pError : pError == null ? jError : jError + ", " + pError);
                    }
                    try {
                        if (nj != np) return "excluded: page count " + NAMES[0] + " " + nj + " vs " + NAMES[1] + " " + np;
                        for (int i = 0; i < nj; i++) {
                            BufferedImage a, b;
                            try {
                                a = ra.render(i);
                            } catch (Throwable t) {
                                return "excluded: " + NAMES[0] + " failed on page " + (i + 1);
                            }
                            try {
                                b = rb.render(i);
                            } catch (Throwable t) {
                                return "excluded: " + NAMES[1] + " failed on page " + (i + 1);
                            }
                            pages.add(OutputComparison.compare(a, b));
                        }
                        long bad = pages.stream().filter(r -> !r.comparable()).count();
                        return bad == 0 ? "comparable" : "excluded: " + bad + " of " + nj + " pages differ";
                    } finally {
                        ra.close();
                        rb.close();
                    }
                });
                status.put(f, result);
                List<OutputComparison.Result> checked = result.startsWith("failed") ? List.of() : List.copyOf(pages);
                for (int i = 0; i < checked.size(); i++) {
                    OutputComparison.Result r = checked.get(i);
                    csv.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%.4f,%.4f,%.2f,%.2f,%d,%s%n", name, i + 1,
                            r.widthA(), r.heightA(), r.widthB(), r.heightB(), r.inkA(), r.inkB(), r.meanDiff(),
                            r.maxTileDiff(), r.badTiles(), r.reason());
                    (r.comparable() ? passed : failed).add(new PageRef(f, i, r.comparable() ? "pass" : "fail-" + r.reason()));
                }
                if (++done % 100 == 0) System.out.printf("  %d/%d files%n", done, files.size());
            }
        }
        long comparable = status.values().stream().filter("comparable"::equals).count();
        System.out.printf("  %d of %d files comparable%n", comparable, files.size());

        Random rnd = new Random(seed);
        List<PageRef> samples = new ArrayList<>(pick(failed, MAX_FAILED_SAMPLES, MAX_FAILED_SAMPLES_PER_FILE, rnd));
        samples.addAll(pick(passed, PASSED_SAMPLES, 1, rnd));
        System.out.printf("Writing %d side-by-side samples...%n", samples.size());
        for (PageRef ref : samples) {
            PageRenderer ra = rendersA.get(), rb = rendersB.get();
            runWithTimeout(worker, () -> {
                try {
                    ra.open(ref.file());
                    rb.open(ref.file());
                    OutputComparison.writeSideBySide(ra.render(ref.pageIndex()), rb.render(ref.pageIndex()),
                            out.resolve("samples").resolve(ref.tag() + "_" + ref.file().getFileName()
                                    + "_p" + (ref.pageIndex() + 1) + ".png"));
                    return "ok";
                } finally {
                    ra.close();
                    rb.close();
                }
            });
        }
        return status;
    }

    /**
     * One page of one file and the output-check verdict that applies to it.
     *
     * @param file      the PDF
     * @param pageIndex zero-based page index
     * @param tag       "pass", or "fail-" plus the check the page failed; used in sample file names
     */
    private record PageRef(Path file, int pageIndex, String tag) {
    }

    /**
     * Picks pages at random, spread across files: at most {@code perFile} pages from any one file.
     *
     * @param pages   the pages to choose from
     * @param max     the most pages to pick
     * @param perFile the most pages to pick from any one file
     * @param rnd     the random source
     * @return the chosen pages
     */
    private static List<PageRef> pick(List<PageRef> pages, int max, int perFile, Random rnd) {
        List<PageRef> shuffled = new ArrayList<>(pages);
        Collections.shuffle(shuffled, rnd);
        Map<Path, Integer> perFileCount = new HashMap<>();
        List<PageRef> chosen = new ArrayList<>();
        for (PageRef ref : shuffled) {
            if (chosen.size() == max) break;
            int n = perFileCount.getOrDefault(ref.file(), 0);
            if (n < perFile) {
                chosen.add(ref);
                perFileCount.put(ref.file(), n + 1);
            }
        }
        return chosen;
    }

    /**
     * Times one library converting every page of one file.
     *
     * @param renderer the library to time
     * @param file     the PDF to convert
     * @param worker   holder for the thread that runs library calls, replaced after a timeout
     * @return per-page times, or the error that stopped the run
     */
    private static Timing time(PageRenderer renderer, Path file, ExecutorService[] worker) {
        System.gc();
        Timing[] result = {null};
        String error = runWithTimeout(worker, () -> {
            long t0 = System.nanoTime();
            try {
                int pages = renderer.open(file);
                double[] ms = new double[pages];
                for (int i = 0; i < pages; i++) {
                    BufferedImage img = renderer.render(i);
                    long t1 = System.nanoTime();
                    ms[i] = (t1 - t0) / 1e6;
                    t0 = t1;
                    sink += img.getWidth();
                }
                result[0] = new Timing(ms, null);
                return null;
            } catch (Throwable t) {
                return t.getClass().getSimpleName();
            } finally {
                renderer.close();
            }
        });
        return result[0] != null ? result[0] : new Timing(null, error);
    }

    /**
     * Runs a task on the worker thread, giving up after {@link #TIMEOUT_SECONDS}. A library
     * call cannot be interrupted safely, so on timeout the stuck thread is abandoned and a new
     * worker replaces it.
     *
     * @param worker holder for the worker thread
     * @param task   the task to run; returns a status string
     * @return the task's result, "failed: timeout" or "failed: " plus the exception name
     */
    static String runWithTimeout(ExecutorService[] worker, java.util.concurrent.Callable<String> task) {
        Future<String> future = worker[0].submit(task);
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            worker[0].shutdownNow();
            worker[0] = Executors.newSingleThreadExecutor();
            return "failed: timeout";
        } catch (Exception e) {
            return "failed: " + e.getClass().getSimpleName();
        }
    }

    /**
     * Writes every measured page time as one CSV row.
     *
     * @param target  the CSV file
     * @param files   the timed files
     * @param results measured passes, each holding JPedal's and PDFBox's timings by file
     * @throws IOException if the file cannot be written
     */
    private static void writeTimings(Path target, List<Path> files, List<Map<Path, Timing>[]> results)
            throws IOException {
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(target))) {
            csv.println("file,pass,library,page,ms,error");
            for (Path f : files) {
                for (int p = 0; p < results.size(); p++) {
                    for (int lib = 0; lib < 2; lib++) {
                        Timing t = results.get(p)[lib].get(f);
                        String libName = NAMES[lib];
                        if (t.pageMs() == null) {
                            csv.printf("%s,%d,%s,,,%s%n", f.getFileName(), p + 1, libName, t.error());
                        } else {
                            for (int i = 0; i < t.pageMs().length; i++) {
                                csv.printf(Locale.ROOT, "%s,%d,%s,%d,%.3f,%n", f.getFileName(), p + 1, libName,
                                        i + 1, t.pageMs()[i]);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes the headline results, the exclusions and the test environment as Markdown.
     *
     * @param target  the Markdown file
     * @param files   every PDF in the corpus
     * @param status  each file's output-check status
     * @param results measured passes, each holding JPedal's and PDFBox's timings by file
     * @param dpi     output resolution
     * @param passes  number of measured passes
     * @param seed    random seed used
     * @throws IOException if the file cannot be written
     */
    private static void writeSummary(Path target, List<Path> files, Map<Path, String> status,
                                     List<Map<Path, Timing>[]> results, float dpi, int passes, long seed)
            throws IOException {
        // Headline set: comparable in the output check and timed without error in every pass.
        List<Path> used = new ArrayList<>();
        Map<Path, String> finalStatus = new LinkedHashMap<>(status);
        for (Path f : files) {
            if (!"comparable".equals(status.get(f))) continue;
            boolean ok = results.stream().allMatch(r -> r[0].get(f).pageMs() != null && r[1].get(f).pageMs() != null);
            if (ok) used.add(f);
            else finalStatus.put(f, "excluded: failed during a timed pass");
        }
        // Secondary set: every file both libraries timed without error, whatever the output check said.
        List<Path> allTimed = files.stream().filter(f -> !status.get(f).startsWith("failed"))
                .filter(f -> results.stream().allMatch(r -> r[0].get(f).pageMs() != null && r[1].get(f).pageMs() != null
                        && r[0].get(f).pageMs().length == r[1].get(f).pageMs().length))
                .collect(Collectors.toList());

        Map<String, Long> reasons = finalStatus.values().stream().filter(s -> !s.equals("comparable"))
                .map(s -> s.replaceAll("\\d+ of \\d+ pages differ", "some pages differ")
                        .replaceAll("page \\d+$", "a page")
                        .replaceAll("page count .*", "page count differs"))
                .collect(Collectors.groupingBy(s -> s, java.util.TreeMap::new, Collectors.counting()));

        try (PrintWriter md = new PrintWriter(Files.newBufferedWriter(target))) {
            md.printf("# %s vs %s: PDF-to-image benchmark%n", NAMES[0], NAMES[1]);
            md.println();
            md.println("## Results (files with comparable output only)");
            md.println();
            writeStats(md, used, files.size(), results, seed);
            md.println();
            md.printf("Ratios below 1 mean %s took less time. Each file's time is the sum over its pages of the%n", NAMES[0]);
            md.println("median page time across the measured passes, and includes opening the file.");
            md.println();
            md.println("## Secondary: every file both libraries rendered, including excluded ones");
            md.println();
            md.println("Shown so readers can check that excluding files did not change the picture.");
            md.println();
            writeStats(md, allTimed, files.size(), results, seed);
            md.println();
            md.println("## Run-to-run variation (headline files)");
            md.println();
            md.printf("| Pass | %s total (s) | %s total (s) |%n", NAMES[0], NAMES[1]);
            md.println("|---|---|---|");
            for (int p = 0; p < results.size(); p++) {
                int pp = p;
                double j = used.stream().mapToDouble(f -> sum(results.get(pp)[0].get(f).pageMs())).sum();
                double q = used.stream().mapToDouble(f -> sum(results.get(pp)[1].get(f).pageMs())).sum();
                md.printf(Locale.ROOT, "| %d | %.1f | %.1f |%n", p + 1, j / 1000, q / 1000);
            }
            md.println();
            md.println("## Excluded files");
            md.println();
            md.println("| Reason | Files |");
            md.println("|---|---|");
            reasons.forEach((r, n) -> md.printf("| %s | %d |%n", r, n));
            md.println();
            md.println("## Method");
            md.println();
            md.printf(Locale.ROOT, "- Resolution: %.0f DPI, opaque RGB output, each library's default rendering settings%n", dpi);
            md.printf("- One warmup pass (discarded), then %d measured passes; single-threaded; one JVM%n", passes);
            md.println("- File order shuffled each pass; library order randomized per file; full GC before each timing");
            md.printf("- Random seed: %d%n", seed);
            md.printf(Locale.ROOT, "- Output check: sizes within %d px; mean gray difference at most %.0f over %dx%d px cells; "
                            + "ink share ratio at least %.2f where the mean difference is %.0f or more; "
                            + "no %dx%d px tile with mean difference above %.0f, or with mean difference of %.0f or more and ink ratio below %.2f%n",
                    OutputComparison.MAX_SIZE_DIFF, OutputComparison.MAX_MEAN_DIFF,
                    OutputComparison.CELL, OutputComparison.CELL,
                    OutputComparison.MIN_INK_RATIO, OutputComparison.MIN_DIFF_FOR_INK,
                    OutputComparison.CELL * OutputComparison.TILE,
                    OutputComparison.CELL * OutputComparison.TILE, OutputComparison.MAX_TILE_DIFF,
                    OutputComparison.MIN_DIFF_FOR_INK, OutputComparison.TILE_MIN_INK_RATIO);
            md.println();
            md.println("## Environment");
            md.println();
            md.printf("- %s: %s%n", NAMES[0], Libraries.version(KEYS[0]));
            md.printf("- %s: %s%n", NAMES[1], Libraries.version(KEYS[1]));
            md.printf("- Java: %s (%s)%n", System.getProperty("java.version"), System.getProperty("java.vm.name"));
            md.printf("- OS: %s %s %s%n", System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"));
            md.printf("- CPUs available: %d; max heap: %d MB%n", Runtime.getRuntime().availableProcessors(),
                    Runtime.getRuntime().maxMemory() / (1024 * 1024));
            md.printf("- JVM arguments: %s%n", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        }
    }

    /**
     * Writes the timing statistics for a set of files as a Markdown list.
     *
     * <p>Each file's time per library is the sum over its pages of the median page time across
     * the measured passes. The confidence interval for the geometric mean of per-file ratios
     * comes from a bootstrap over files.
     *
     * @param md      the Markdown output
     * @param set     the files to include; each must have timings in every pass
     * @param total   number of PDFs in the corpus, for the "x of y" line
     * @param results measured passes, each holding JPedal's and PDFBox's timings by file
     * @param seed    seed for the bootstrap
     */
    private static void writeStats(PrintWriter md, List<Path> set, int total, List<Map<Path, Timing>[]> results,
                                   long seed) {
        Function<Integer, double[]> fileTimes = lib -> set.stream().mapToDouble(f -> {
            int pages = results.get(0)[lib].get(f).pageMs().length;
            double t = 0;
            for (int i = 0; i < pages; i++) {
                double[] v = new double[results.size()];
                for (int p = 0; p < results.size(); p++) v[p] = results.get(p)[lib].get(f).pageMs()[i];
                t += median(v);
            }
            return t;
        }).toArray();
        double[] jt = fileTimes.apply(0), pt = fileTimes.apply(1);
        double[] logRatio = new double[set.size()];
        int aFaster = 0;
        for (int i = 0; i < set.size(); i++) {
            logRatio[i] = Math.log(jt[i] / pt[i]);
            if (jt[i] < pt[i]) aFaster++;
        }
        double geo = Math.exp(mean(logRatio));
        Random rnd = new Random(seed);
        double[] boot = new double[BOOTSTRAP_RESAMPLES];
        for (int b = 0; b < boot.length && logRatio.length > 0; b++) {
            double s = 0;
            for (int i = 0; i < logRatio.length; i++) s += logRatio[rnd.nextInt(logRatio.length)];
            boot[b] = Math.exp(s / logRatio.length);
        }
        java.util.Arrays.sort(boot);
        int pageCount = set.stream().mapToInt(f -> results.get(0)[0].get(f).pageMs().length).sum();
        md.printf(Locale.ROOT, "- Files: %d of %d (%d pages)%n", set.size(), total, pageCount);
        md.printf(Locale.ROOT, "- Total time, %s: %.1f s%n", NAMES[0], sum(jt) / 1000);
        md.printf(Locale.ROOT, "- Total time, %s: %.1f s%n", NAMES[1], sum(pt) / 1000);
        md.printf(Locale.ROOT, "- Ratio of totals (%s / %s): %.3f%n", NAMES[0], NAMES[1], sum(jt) / sum(pt));
        md.printf(Locale.ROOT, "- Geometric mean of per-file ratios (%s / %s): %.3f (95%% bootstrap CI %.3f to %.3f)%n",
                NAMES[0], NAMES[1], geo, boot[(int) (0.025 * boot.length)], boot[(int) (0.975 * boot.length) - 1]);
        md.printf(Locale.ROOT, "- Median file time: %s %.1f ms, %s %.1f ms%n", NAMES[0], median(jt.clone()), NAMES[1], median(pt.clone()));
        md.printf(Locale.ROOT, "- Files where %s is faster: %d of %d (%.0f%%)%n", NAMES[0], aFaster, set.size(),
                100.0 * aFaster / Math.max(1, set.size()));
    }

    /**
     * Returns a library's display name as a CSV column prefix: lower case, with anything other
     * than letters and digits replaced by underscores.
     *
     * @param lib 0 for the first library, 1 for the second
     * @return the column prefix, for example "jpedal"
     */
    private static String column(int lib) {
        return NAMES[lib].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    /**
     * Returns the median of the values; sorts the array in place.
     *
     * @param v the values
     * @return the median, or NaN if empty
     */
    private static double median(double[] v) {
        if (v.length == 0) return Double.NaN;
        java.util.Arrays.sort(v);
        int m = v.length / 2;
        return v.length % 2 == 1 ? v[m] : (v[m - 1] + v[m]) / 2;
    }

    /**
     * Returns the sum of the values.
     *
     * @param v the values
     * @return the sum
     */
    private static double sum(double[] v) {
        double s = 0;
        for (double d : v) s += d;
        return s;
    }

    /**
     * Returns the arithmetic mean of the values.
     *
     * @param v the values
     * @return the mean, or NaN if empty
     */
    private static double mean(double[] v) {
        return v.length == 0 ? Double.NaN : sum(v) / v.length;
    }
}
