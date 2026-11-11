package com.idrsolutions.benchmark;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Compares the two libraries of a {@link Benchmark} run by kind of file, using the profiles
 * {@link FileProfile} writes. Writes {@code breakdown.md} with one table for each of: content
 * type, font embedding, CID fonts, PDF version (from the file's first line) and page count.
 *
 * <p>Only files whose every page passed the output check, and that both libraries timed in every
 * pass, are included, the same set as the headline results. Each file's time is the sum over its
 * pages of the median page time across passes.
 *
 * <p>Usage: {@code java -cp benchmark.jar com.idrsolutions.benchmark.Breakdown <results-dir>
 * <files.csv>}
 */
public final class Breakdown {

    /**
     * One file's times and profile.
     *
     * @param timeA   total time for the first library, in milliseconds
     * @param timeB   total time for the second library, in milliseconds
     * @param pages   page count
     * @param profile the file's FileProfile row, by column name
     */
    private record FileResult(double timeA, double timeB, int pages, Map<String, String> profile) {
    }

    /** Writes the breakdown; not instantiated. */
    private Breakdown() {
    }

    /**
     * Reads a Benchmark run's CSVs and a FileProfile CSV and writes breakdown.md.
     *
     * @param args results-dir and the FileProfile CSV
     * @throws IOException if a CSV cannot be read or the report cannot be written
     */
    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args[0]);
        Map<String, Map<String, String>> profiles = readCsv(Paths.get(args[1]), "file");

        Set<String> failed = new HashSet<>();
        for (String line : lines(dir.resolve("comparison.csv"))) {
            String[] c = line.split(",", -1);
            if (!c[c.length - 1].equals("ok")) failed.add(c[0]);
        }

        // file -> library -> page -> times across passes
        Map<String, Map<String, Map<Integer, List<Double>>>> times = new HashMap<>();
        List<String> libraries = new ArrayList<>();
        Set<String> errors = new HashSet<>();
        for (String line : lines(dir.resolve("timings.csv"))) {
            String[] c = line.split(",", -1);
            if (!libraries.contains(c[2])) libraries.add(c[2]);
            if (c[4].isEmpty()) {
                errors.add(c[0]);
                continue;
            }
            times.computeIfAbsent(c[0], k -> new HashMap<>()).computeIfAbsent(c[2], k -> new TreeMap<>())
                    .computeIfAbsent(Integer.parseInt(c[3]), k -> new ArrayList<>()).add(Double.parseDouble(c[4]));
        }
        String a = libraries.get(0), b = libraries.get(1);

        List<FileResult> results = new ArrayList<>();
        times.forEach((file, byLib) -> {
            if (failed.contains(file) || errors.contains(file) || !profiles.containsKey(file)
                    || !byLib.containsKey(a) || !byLib.containsKey(b)) return;
            results.add(new FileResult(total(byLib.get(a)), total(byLib.get(b)), byLib.get(a).size(),
                    profiles.get(file)));
        });

        try (PrintWriter md = new PrintWriter(Files.newBufferedWriter(dir.resolve("breakdown.md")))) {
            md.printf("# %s vs %s by kind of file%n%n%d files with comparable output.%n", a, b, results.size());
            table(md, "Content type", a, b, results, r -> r.profile().get("content_type"));
            table(md, "Font embedding", a, b, results, Breakdown::embedding);
            table(md, "CID fonts", a, b, results, Breakdown::cid);
            table(md, "PDF version (first line)", a, b, results, r -> "PDF " + r.profile().get("header_version"));
            table(md, "Page count", a, b, results, Breakdown::pageBand);
        }
        System.out.println("Wrote " + dir.resolve("breakdown.md"));
    }

    /**
     * Groups a file by whether its fonts are embedded. Type 3 fonts are defined in the file, so
     * they count as embedded.
     *
     * @param r the file
     * @return "All fonts embedded", "No fonts embedded", "Some fonts embedded" or "No fonts"
     */
    static String embedding(FileResult r) {
        int embedded = count(r, "fonts_embedded") + count(r, "fonts_type3"), notEmbedded = count(r, "fonts_not_embedded");
        if (embedded + notEmbedded == 0) return "No fonts";
        if (notEmbedded == 0) return "All fonts embedded";
        return embedded == 0 ? "No fonts embedded" : "Some fonts embedded";
    }

    /**
     * Groups a file by whether it uses CID (Type 0) fonts.
     *
     * @param r the file
     * @return "CID fonts only", "Simple fonts only", "CID and simple fonts" or "No fonts"
     */
    static String cid(FileResult r) {
        int cid = count(r, "fonts_cid"), simple = count(r, "fonts_simple") + count(r, "fonts_type3");
        if (cid + simple == 0) return "No fonts";
        if (simple == 0) return "CID fonts only";
        return cid == 0 ? "Simple fonts only" : "CID and simple fonts";
    }

    /**
     * Groups a file by its page count. The labels start with a letter so they sort in order.
     *
     * @param r the file
     * @return the page-count band, for example "b) 2-3 pages"
     */
    static String pageBand(FileResult r) {
        int p = r.pages();
        return p <= 1 ? "a) 1 page" : p <= 3 ? "b) 2-3 pages" : p <= 10 ? "c) 4-10 pages"
                : p <= 50 ? "d) 11-50 pages" : "e) 51+ pages";
    }

    /**
     * Reads an integer column from a file's profile.
     *
     * @param r      the file
     * @param column the column name
     * @return the value, or 0 if it is empty
     */
    private static int count(FileResult r, String column) {
        String v = r.profile().get(column);
        return v == null || v.isEmpty() ? 0 : Integer.parseInt(v);
    }

    /**
     * Writes one Markdown table comparing the libraries for each group of files.
     *
     * @param md      the output
     * @param title   the table's heading
     * @param a       the first library's name
     * @param b       the second library's name
     * @param results the files
     * @param group   assigns each file to a group
     */
    private static void table(PrintWriter md, String title, String a, String b, List<FileResult> results,
                              Function<FileResult, String> group) {
        Map<String, List<FileResult>> groups = new TreeMap<>();
        for (FileResult r : results) groups.computeIfAbsent(group.apply(r), k -> new ArrayList<>()).add(r);
        md.printf("%n## %s%n%n", title);
        md.printf("| %s | Files | Pages | %s total | %s total | %s ÷ %s | %s faster on | Median file speedup |%n",
                title, a, b, a, b, a);
        md.println("|---|---|---|---|---|---|---|---|");
        for (Map.Entry<String, List<FileResult>> e : groups.entrySet()) {
            List<FileResult> g = e.getValue();
            double ta = g.stream().mapToDouble(FileResult::timeA).sum();
            double tb = g.stream().mapToDouble(FileResult::timeB).sum();
            long faster = g.stream().filter(r -> r.timeA() < r.timeB()).count();
            double[] speedup = g.stream().mapToDouble(r -> r.timeB() / r.timeA()).sorted().toArray();
            md.printf(Locale.ROOT, "| %s | %d | %d | %.1fs | %.1fs | %.2fx | %.0f%% | %.2fx |%n", e.getKey(), g.size(),
                    g.stream().mapToInt(FileResult::pages).sum(), ta / 1000, tb / 1000, ta / tb,
                    100.0 * faster / g.size(), median(speedup));
        }
    }

    /**
     * Sums the median time of each page across passes.
     *
     * @param pages page number to its times across passes, in milliseconds
     * @return the file's total time in milliseconds
     */
    private static double total(Map<Integer, List<Double>> pages) {
        double sum = 0;
        for (List<Double> v : pages.values()) {
            sum += median(v.stream().mapToDouble(Double::doubleValue).sorted().toArray());
        }
        return sum;
    }

    /**
     * Returns the median of sorted values.
     *
     * @param sorted the values, in ascending order
     * @return the median, or NaN if there are none
     */
    private static double median(double[] sorted) {
        if (sorted.length == 0) return Double.NaN;
        int m = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2;
    }

    /**
     * Reads a CSV with a header row into maps keyed by one column.
     *
     * @param csv the file
     * @param key the column to key rows by
     * @return each row as column name to value
     * @throws IOException if the file cannot be read
     */
    private static Map<String, Map<String, String>> readCsv(Path csv, String key) throws IOException {
        List<String> all = Files.readAllLines(csv);
        String[] header = all.get(0).split(",", -1);
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        for (String line : all.subList(1, all.size())) {
            String[] c = line.split(",", -1);
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < header.length && i < c.length; i++) row.put(header[i], c[i]);
            rows.put(row.get(key), row);
        }
        return rows;
    }

    /**
     * Reads a CSV's data rows, without the header.
     *
     * @param csv the file
     * @return the rows
     * @throws IOException if the file cannot be read
     */
    private static List<String> lines(Path csv) throws IOException {
        List<String> all = Files.readAllLines(csv);
        return all.subList(1, all.size());
    }
}
