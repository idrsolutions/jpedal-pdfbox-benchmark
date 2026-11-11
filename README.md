# JPedal vs PDFBox: PDF-to-image benchmark

PDF-to-image conversion timing with [JPedal](https://www.idrsolutions.com/jpedal/) and
[Apache PDFBox](https://pdfbox.apache.org/) on a public corpus, and only counts files where both
libraries produced comparable output. Boilerplate code time any Java PDF Library included.

## Comparing like with like

The output generated needs to be comparable (comparing apples with apples). So before timing anything, 
the benchmark renders every page with both libraries and compares the images (`OutputComparison`):

- **Size:** width and height must agree to within 1 pixel.
- **Ink:** the share of non-background area must agree to within 20%, wherever the pages differ by
  a mean of 10 gray levels or more. Below that, light gray content can flip between "ink" and
  "background" on a difference too small to see.
- **Mean difference:** the average gray-level difference over 4x4 px cells must be at most 12 (of 255).
- **Tiles:** no 64x64 px tile may differ strongly (mean difference above 60), or lose most of its
  ink with a mean difference of 10 or more. This catches a skipped image or a missing block of text
  that is too small to move the page-wide figures.

Antialiasing and font hinting always differ between libraries, so the check tolerates small
differences and flags missing or different content. A file counts in the headline results only if
every page passes. `comparison.csv` holds every page's measurements and `samples/` holds
side-by-side images of up to 50 failed pages (at most 2 per file) and 25 passed pages (at most 1
per file), chosen at random across the whole corpus, so you can check the verdicts and apply
stricter thresholds. The comparison does not detect a missing thin line, such as a link
annotation's border. The summary also reports the same statistics for every file
both libraries rendered, so you can see whether the exclusions changed the result.

## Method

- Both libraries render at the same DPI (default 150) to opaque RGB, using each library's
  documented API and default rendering settings:
  - JPedal: `ConvertPagesToImages` with `setPageScaling(dpi / 72)` and `getPageAsImage(page, false)`
  - PDFBox: `PDFRenderer.renderImageWithDPI(page, dpi, ImageType.RGB)`
- PDFBox has the optional JPEG2000 (`jai-imageio-jpeg2000`) and JBIG2 (`jbig2-imageio`) decoders on
  the classpath, so it decodes those images instead of skipping them.
- A file's time runs from creating the library's document object to finishing its last page, so it
  includes opening and parsing the file.
- One JVM, single-threaded. One warmup pass through the corpus is discarded, then several measured
  passes run (default 3). Each pass shuffles the file order, each file picks at random which library
  runs first, and a full GC runs before each timing, so neither library inherits the other's
  caches or garbage. The random seed is recorded, so a run can be repeated exactly.
- Each page's time is the median across measured passes. Results report total time, the geometric
  mean of per-file JPedal/PDFBox ratios with a 95% bootstrap confidence interval, median file time,
  and the share of files where each library is faster.
- A library that takes more than 300 s on one file is stopped, and the file is excluded.

## Corpus

The results we publish use the 2,075 PDFs in ZIP files 500 to 504 of
[Govdocs1](https://digitalcorpora.org/corpora/file-corpora/files/), a corpus of documents collected
from US government websites that Digital Corpora describes as freely available for research and,
to the best of its knowledge, freely redistributable. We picked these five ZIP files at random before
running the benchmark, and we did not use them in JPedal performance work or to tune the output check.
The output-check thresholds were calibrated on PDFs from a different Govdocs1 ZIP file (000).
To check the results hold on other files, we repeated the benchmark on the PDFs in ZIP files 015
to 019, which we also did not use in JPedal performance work or to tune the output check.
The benchmark runs on any other Govdocs1 ZIP files, or any folder of PDFs, too.

```bash
mkdir -p corpus-download corpus
for t in 500 501 502 503 504; do
  curl -L -o corpus-download/$t.zip https://downloads.digitalcorpora.org/corpora/files/govdocs1/zipfiles/$t.zip
  unzip -q -j corpus-download/$t.zip '*.pdf' -d corpus/$t
done
```

## Build and run

JPedal is not on Maven Central. It comes from IDRsolutions' Maven repository, which needs a customer
login or a free trial token in `~/.m2/settings.xml`; see
[Add JPedal as a Maven dependency](https://www.idrsolutions.com/docs/jpedal/tutorials/add-jpedal-as-a-maven-dependency).
Trial users build with `-Djpedal.artifactId=jpedal-trial`. The trial adds a watermark to its output, so its
times won't match the full release's.

```bash
mvn clean package
java -Xms4g -Xmx4g -jar target/jpedal-pdfbox-benchmark-1.0.jar corpus results 150 3
```

Arguments: PDF directory, output directory, then optionally DPI (150), measured passes (3) and
random seed. Output:

- `summary.md`: results, exclusions, method and environment
- `comparison.csv`: output-check measurements for every page
- `timings.csv`: every measured page time
- `samples/`: side-by-side images, JPedal on the left and PDFBox on the right

Use Java 17.0.20 or later; some earlier Java 17 builds render incorrectly on recent macOS.

## Breaking results down by kind of file

`FileProfile` describes every PDF in the corpus: its PDF version from the file's first line
(`%PDF-1.x`, plus the catalog's `/Version` if it overrides it), page count, content type, and its
fonts (embedded or not, CID or simple, Type 3). A file counts as image-heavy when image streams make
up more than half its size, and takes the name of the compression that dominates. `Breakdown` then
compares the two libraries from a benchmark run for each content type, font embedding, CID fonts,
PDF version and page-count band, using only files whose output passed the check.

```bash
java -cp target/jpedal-pdfbox-benchmark-1.0.jar com.idrsolutions.benchmark.FileProfile corpus results/files.csv
java -cp target/jpedal-pdfbox-benchmark-1.0.jar com.idrsolutions.benchmark.Breakdown results results/files.csv
```

This writes `results/breakdown.md`.

## Exporting per-file results

`export-csv.py` writes one row per PDF for publishing with the results: pages, PDF version, content
type, font embedding, whether the output check passed, each library's time and the ratio between
them, and which library was faster, counting times within 5 ms or 5% as a draw. Give it a memory
run's output directory as well to add each library's allocated and peak retained memory.

```bash
python3 export-csv.py results results/files.csv results/per-file.csv memory-results
```

## Memory

`MemoryBenchmark` uses the same renderers and resolution as the speed benchmark and runs one
library per JVM, so memory one library leaves behind cannot be counted against the other. It has
two modes:

- **measure:** one discarded warmup pass, then one measured pass that records for each file:
  - **allocated:** bytes the rendering thread allocated, which is the load on the garbage
    collector. Both libraries do all their work on the calling thread, so this count is complete.
  - **peak retained:** the largest amount of memory held by live objects after any page, relative
    to before the file was opened. This is what the open document and the library's caches hold.
  - **held after close:** memory held by live objects after the file is closed, which shows static caches
    or leaks.
- **sweep:** renders the corpus once at a given `-Xmx` and records which files fail, to find the
  smallest heap each library needs.

Retained memory is counted from a JVM class histogram, which collects garbage and then adds up every
reachable object. The heap's "used" figure is not used: after a full GC it can still include memory
the collector has not returned. Peak heap including garbage is not reported, because it depends on 
when the collector runs rather than on the library.

```bash
./run-memory.sh corpus results results/comparison.csv
```

This runs both libraries' measure passes and sweeps at 256 MB, 512 MB, 1 GB and 2 GB, then writes
`memory-summary.md`. Passing the speed run's `comparison.csv` limits the headline figures to the
files whose output passed the output check.

## Testing another library

The benchmark compares any two Java PDF libraries. JPedal, PDFBox and PDFBox with image subsampling
(`jpedal`, `pdfbox`, `pdfbox-subsampled`) are built in. To add another, you write one class that
implements `com.idrsolutions.benchmark.PageRenderer`, put it on the classpath and name it on the
command line. Nothing in the benchmark itself changes.

The walk-through below uses **FooBar PDF**, a made-up library, so the steps are concrete. Its API
(`com.foobar.pdf.FooBarDocument` and friends) doesn't exist; swap in the real library's calls.

### 1. Write the renderer

A renderer opens a file, renders one page at a time and closes the file. It needs a public
constructor that takes the output resolution in dots per inch.

```java
package example;

import com.foobar.pdf.FooBarDocument;      // hypothetical FooBar PDF API
import com.foobar.pdf.FooBarRenderOptions; // hypothetical
import com.idrsolutions.benchmark.PageRenderer;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

/** Renders pages with the (made-up) FooBar PDF library, for the benchmark. */
public class FooBarRenderer implements PageRenderer {

    /** Output resolution in dots per inch. */
    private final float dpi;

    /** The open document, or null when no file is open. */
    private FooBarDocument document;

    /**
     * Called by the benchmark with the DPI it was given (150 by default).
     *
     * @param dpi output resolution in dots per inch
     */
    public FooBarRenderer(float dpi) {
        this.dpi = dpi;
    }

    /**
     * Names the library "FooBar PDF" in reports and CSV column prefixes.
     * <p>{@inheritDoc}
     */
    @Override
    public String name() {
        return "FooBar PDF";
    }

    /**
     * Loads the file with FooBarDocument.
     * <p>{@inheritDoc}
     */
    @Override
    public int open(Path file) throws Exception {
        document = FooBarDocument.load(file);
        return document.getPageCount();
    }

    /**
     * Renders with FooBar at the benchmark's DPI on an opaque background.
     * <p>{@inheritDoc}
     */
    @Override
    public BufferedImage render(int pageIndex) throws Exception {
        FooBarRenderOptions options = new FooBarRenderOptions()
                .dpi(dpi)                      // same resolution as the other library
                .transparentBackground(false); // opaque output, as JPedal and PDFBox give
        return document.renderPage(pageIndex, options);
    }

    /**
     * Closes the FooBarDocument, if one is open.
     * <p>{@inheritDoc}
     */
    @Override
    public void close() {
        if (document != null) {
            document.close();
            document = null;
        }
    }
}
```

The page index the benchmark passes is 0-based, and the time `open` takes counts towards page 1.
Keep the renderer to the library's documented, default way of rendering a page to an image. The
benchmark is only fair if each library does what a typical developer would write.

### 2. Compile it against the benchmark and the library

```bash
mvn clean package                      # builds target/jpedal-pdfbox-benchmark-1.0.jar
mkdir -p ext
javac -cp "target/jpedal-pdfbox-benchmark-1.0.jar:/path/to/foobar-pdf.jar" \
    -d ext src-example/FooBarRenderer.java
```

Add any other jars FooBar needs to the classpath here and in the steps below.

### 3. Check it renders the same pages

Start with a small run on a handful of files, one measured pass:

```bash
CP="target/jpedal-pdfbox-benchmark-1.0.jar:/path/to/foobar-pdf.jar:ext"
java -Xms4g -Xmx4g -cp "$CP" com.idrsolutions.benchmark.Benchmark \
    --libraries jpedal,example.FooBarRenderer small-corpus results-foobar-smoke 150 1
```

Open `results-foobar-smoke/samples/`: JPedal is on the left of each image, FooBar on the right. If
every page fails the size check, the DPI or page box handling differs; if pages come out blank or
transparent, check the background setting. Fix the renderer until most pages pass.

### 4. Run the speed benchmark

```bash
java -Xms4g -Xmx4g -cp "$CP" com.idrsolutions.benchmark.Benchmark \
    --libraries jpedal,example.FooBarRenderer corpus results-foobar
```

`results-foobar/summary.md` reports "JPedal vs FooBar PDF", and the CSV columns use `jpedal_` and
`foobar_pdf_` prefixes. To compare FooBar with PDFBox instead, pass `--libraries pdfbox,example.FooBarRenderer`.

### 5. Run the memory benchmark

`MemoryBenchmark` takes the class name in place of `jpedal` or `pdfbox`, one library per JVM:

```bash
for LIB in jpedal example.FooBarRenderer; do
  java -Xms4g -Xmx4g -cp "$CP" com.idrsolutions.benchmark.MemoryBenchmark measure $LIB corpus results-foobar
  for HEAP in 256m 512m 1g 2g; do
    java -Xmx$HEAP -cp "$CP" com.idrsolutions.benchmark.MemoryBenchmark sweep $LIB corpus results-foobar
  done
done
java -cp "$CP" com.idrsolutions.benchmark.MemorySummary \
    --libraries jpedal,example.FooBarRenderer results-foobar results-foobar/comparison.csv
```

FooBar's output files are named `memory-example_foobarrenderer.csv` and `sweep-example_foobarrenderer-<MB>.csv`.

### 6. Before you try another library

- **Check the license.** Some commercial licenses forbid publishing benchmarks, or limit what a
  trial or evaluation copy may be used for. An evaluation build may also add a watermark or stop
  after a few pages, which changes what is being timed.
- **Check the library's optional components.** Like PDFBox's JBIG2 and JPEG 2000 decoders, a library
  may need extra jars to render every image. Without them it may skip images and look faster.
- **Check native code.** If the library runs a separate process or native engine, the per-thread
  allocation figures won't see that memory, so the memory comparison won't be like for like.
- **Report the exclusions.** Say how many files failed the output check for each pair, and why.

## License

Apache License 2.0. See [LICENSE](LICENSE).
