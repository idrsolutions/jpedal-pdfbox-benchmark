package com.idrsolutions.benchmark;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Writes {@code memory-summary.md} from the CSVs that {@link MemoryBenchmark} writes, comparing
 * two libraries (JPedal and PDFBox unless {@code --libraries} names others) on the files both
 * converted.
 *
 * <p>If a {@code comparison.csv} from {@link Benchmark} is given, the headline figures only use
 * files whose output passed the output check on every page, the same set as the speed results.
 *
 * <p>Usage: {@code java -cp benchmark.jar com.idrsolutions.benchmark.MemorySummary
 * [--libraries a,b] <results-dir> [comparison.csv]}
 */
public final class MemorySummary {

    /** Bytes in a megabyte, for reporting. */
    private static final double MB = 1024.0 * 1024.0;

    /**
     * One library's figures for one file.
     *
     * @param pages      number of pages
     * @param allocated  bytes allocated converting the file
     * @param peak       largest heap held after any page, relative to before the file was opened
     * @param afterClose heap held after closing the file, relative to before it was opened
     */
    private record FileMemory(int pages, long allocated, long peak, long afterClose) {
    }

    /** Writes the summary; not instantiated. */
    private MemorySummary() {
    }

    /**
     * Reads the memory and sweep CSVs in a results directory and writes the summary there.
     *
     * @param args optionally {@code --libraries a,b}, then results-dir, then optionally the path
     *             of a comparison.csv
     * @throws IOException if a CSV cannot be read or the summary cannot be written
     */
    public static void main(String[] args) throws IOException {
        String libraries = Libraries.DEFAULT;
        if (args.length >= 2 && args[0].equals("--libraries")) {
            libraries = args[1];
            args = Arrays.copyOfRange(args, 2, args.length);
        }
        String[] keys = Libraries.pair(libraries);
        String nameA = Libraries.supplier(keys[0], 72f).get().name();
        String nameB = Libraries.supplier(keys[1], 72f).get().name();
        String fileA = Libraries.fileKey(keys[0]), fileB = Libraries.fileKey(keys[1]);
        Path dir = Paths.get(args[0]);
        Map<String, FileMemory> libA = read(dir.resolve("memory-" + fileA + ".csv"));
        Map<String, FileMemory> libB = read(dir.resolve("memory-" + fileB + ".csv"));
        Set<String> comparable = args.length > 1 ? comparableFiles(Paths.get(args[1])) : null;

        List<String> both = new ArrayList<>();
        for (String f : libA.keySet()) {
            if (libB.containsKey(f) && libA.get(f).pages() == libB.get(f).pages()
                    && (comparable == null || comparable.contains(f))) {
                both.add(f);
            }
        }
        both.sort(String::compareTo);

        try (PrintWriter md = new PrintWriter(Files.newBufferedWriter(dir.resolve("memory-summary.md")))) {
            md.printf("# %s vs %s: memory%n", nameA, nameB);
            md.println();
            md.printf("Files: %d%s%n", both.size(), comparable == null ? " (both libraries converted every page)"
                    : " (both libraries converted every page and the output check passed)");
            md.println();
            md.printf("| Measure | %s | %s |%n", nameA, nameB);
            md.println("|---|---|---|");
            row(md, "Total allocated (MB)", both, libA, libB, m -> m.allocated(), "sum");
            row(md, "Median allocated per file (MB)", both, libA, libB, m -> m.allocated(), "median");
            row(md, "Median allocated per page (MB)", both, libA, libB,
                    m -> m.pages() == 0 ? 0 : m.allocated() / m.pages(), "median");
            row(md, "Median peak retained per file (MB)", both, libA, libB, m -> m.peak(), "median");
            row(md, "95th percentile peak retained (MB)", both, libA, libB, m -> m.peak(), "p95");
            row(md, "Largest peak retained (MB)", both, libA, libB, m -> m.peak(), "max");
            row(md, "Median held after close (MB)", both, libA, libB, m -> m.afterClose(), "median");
            row(md, "Largest held after close (MB)", both, libA, libB, m -> m.afterClose(), "max");
            long aLess = both.stream().filter(f -> libA.get(f).allocated() < libB.get(f).allocated()).count();
            md.printf(Locale.ROOT, "%n%s allocates less on %d of %d files (%.0f%%).%n", nameA, aLess, both.size(),
                    100.0 * aLess / Math.max(1, both.size()));
            md.println();
            md.println("Allocated: bytes the rendering thread allocated, the load on the garbage collector.");
            md.println("Peak retained: largest memory held by live objects after any page, relative to before");
            md.println("the file was opened. Held after close: live memory still held after the file was closed.");

            md.println();
            md.println("## Files each library failed to convert, by maximum heap");
            md.println();
            Map<Long, int[]> sweep = sweep(dir, fileA, fileB);
            if (sweep.isEmpty()) {
                md.println("No sweep results found.");
            } else {
                md.printf("| Max heap (MB) | %s failures | %s failures |%n", nameA, nameB);
                md.println("|---|---|---|");
                sweep.forEach((heap, f) -> md.printf("| %d | %s | %s |%n", heap,
                        f[0] < 0 ? "not run" : Integer.toString(f[0]), f[1] < 0 ? "not run" : Integer.toString(f[1])));
            }
        }
    }

    /**
     * Writes one table row comparing a statistic of one measure between the two libraries.
     *
     * @param md      the Markdown output
     * @param label   the row label
     * @param files   the files to include
     * @param libA  the first library's figures by file
     * @param libB  the second library's figures by file
     * @param measure extracts the measure, in bytes, from a file's figures
     * @param stat    "sum", "median", "p95" or "max"
     */
    private static void row(PrintWriter md, String label, List<String> files, Map<String, FileMemory> libA,
                            Map<String, FileMemory> libB, java.util.function.ToLongFunction<FileMemory> measure,
                            String stat) {
        md.printf(Locale.ROOT, "| %s | %.1f | %.1f |%n", label,
                stat(files.stream().mapToLong(f -> measure.applyAsLong(libA.get(f))).toArray(), stat) / MB,
                stat(files.stream().mapToLong(f -> measure.applyAsLong(libB.get(f))).toArray(), stat) / MB);
    }

    /**
     * Computes a statistic of a set of values.
     *
     * @param v    the values
     * @param stat "sum", "median", "p95" or "max"
     * @return the statistic, or 0 if there are no values
     */
    private static double stat(long[] v, String stat) {
        if (v.length == 0) return 0;
        Arrays.sort(v);
        return switch (stat) {
            case "sum" -> Arrays.stream(v).sum();
            case "median" -> v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2.0;
            case "p95" -> v[Math.min(v.length - 1, (int) Math.ceil(0.95 * v.length) - 1)];
            case "max" -> v[v.length - 1];
            default -> throw new IllegalArgumentException(stat);
        };
    }

    /**
     * Reads one library's memory CSV, keeping only files it converted without error.
     *
     * @param csv the memory CSV
     * @return figures by file name
     * @throws IOException if the file cannot be read
     */
    private static Map<String, FileMemory> read(Path csv) throws IOException {
        Map<String, FileMemory> out = new HashMap<>();
        List<String> lines = Files.readAllLines(csv);
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split(",", -1);
            if (!c[5].isEmpty() || c[1].isEmpty()) continue;
            out.put(c[0], new FileMemory(Integer.parseInt(c[1]), Long.parseLong(c[2]), Long.parseLong(c[3]),
                    Long.parseLong(c[4])));
        }
        return out;
    }

    /**
     * Reads the files whose every page passed the output check.
     *
     * @param csv a comparison.csv written by {@link Benchmark}
     * @return the names of the comparable files
     * @throws IOException if the file cannot be read
     */
    private static Set<String> comparableFiles(Path csv) throws IOException {
        Set<String> seen = new HashSet<>(), bad = new HashSet<>();
        List<String> lines = Files.readAllLines(csv);
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split(",", -1);
            seen.add(c[0]);
            if (!c[c.length - 1].equals("ok")) bad.add(c[0]);
        }
        seen.removeAll(bad);
        return seen;
    }

    /**
     * Counts each library's failures in every sweep CSV in the directory.
     *
     * @param dir   the results directory
     * @param fileA the first library's name as used in file names
     * @param fileB the second library's name as used in file names
     * @return by max heap in MB: {first library's failures, second's}, -1 where a library was not run
     * @throws IOException if a file cannot be read
     */
    private static Map<Long, int[]> sweep(Path dir, String fileA, String fileB) throws IOException {
        Map<Long, int[]> out = new TreeMap<>();
        try (Stream<Path> s = Files.list(dir)) {
            java.util.regex.Pattern name = java.util.regex.Pattern.compile("sweep-(.+)-(\\d+)\\.csv");
            for (Path p : (Iterable<Path>) s::iterator) {
                java.util.regex.Matcher m = name.matcher(p.getFileName().toString());
                if (!m.matches() || !(m.group(1).equals(fileA) || m.group(1).equals(fileB))) continue;
                long heap = Long.parseLong(m.group(2));
                int failed = (int) Files.readAllLines(p).stream().skip(1).filter(l -> !l.endsWith(",ok")).count();
                int[] f = out.computeIfAbsent(heap, k -> new int[]{-1, -1});
                f[m.group(1).equals(fileA) ? 0 : 1] = failed;
            }
        }
        return out;
    }
}
