#!/bin/sh
# Runs the memory benchmark for two libraries, then writes memory-summary.md.
#
# Usage: ./run-memory.sh <pdf-dir> <output-dir> [comparison.csv]
#
# LIBRARIES picks the pair, as in Benchmark's --libraries (default jpedal,pdfbox):
#   LIBRARIES=jpedal,pdfbox-subsampled ./run-memory.sh corpus results
#
# Each library runs in its own JVM. The measure runs use a fixed 4 GB heap. The sweep runs
# render the corpus at 256 MB, 512 MB, 1 GB and 2 GB maximum heap to find the smallest heap
# each library needs. Memory figures are per JVM, so the four chains run in parallel; this
# slows them down but does not change the figures.
set -e
CORPUS="$1"
OUT="$2"
COMPARISON="$3"
LIBRARIES="${LIBRARIES:-jpedal,pdfbox}"
JAR=target/jpedal-pdfbox-benchmark-1.0.jar
JAVA="${JAVA:-java}"
MAIN=com.idrsolutions.benchmark.MemoryBenchmark
FLAGS="-XX:ParallelGCThreads=2"
mkdir -p "$OUT"

for LIB in $(echo "$LIBRARIES" | tr ',' ' '); do
  "$JAVA" $FLAGS -Xms4g -Xmx4g -cp "$JAR" $MAIN measure "$LIB" "$CORPUS" "$OUT" > "$OUT/measure-$LIB.log" 2>&1 &
  (
    for HEAP in 256m 512m 1g 2g; do
      "$JAVA" $FLAGS -Xmx$HEAP -cp "$JAR" $MAIN sweep "$LIB" "$CORPUS" "$OUT" > "$OUT/sweep-$LIB-$HEAP.log" 2>&1
    done
  ) &
done
wait

"$JAVA" -cp "$JAR" com.idrsolutions.benchmark.MemorySummary --libraries "$LIBRARIES" "$OUT" $COMPARISON
echo "Summary: $OUT/memory-summary.md"
