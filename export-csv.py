#!/usr/bin/env python3
"""Writes one CSV row per PDF from a benchmark run, for publishing alongside the results.

Usage: python3 export-csv.py <run-dir> <files.csv> <output.csv> [memory-dir]

<run-dir> is a speed run's output directory (timings.csv and comparison.csv). <files.csv> is
FileProfile's output for the same corpus. [memory-dir] is run-memory.sh's output directory; leave it
out to write speed columns only.

Each file's time is the sum over its pages of the median page time across the measured passes, as
in summary.md. Two times count as a draw when they are within 5 ms or 5% of the slower one.
Memory figures are in MB of 1,048,576 bytes, as in memory-summary.md.
"""
import collections
import csv
import glob
import os
import re
import statistics
import sys


def slug(name):
    """Turns a library name such as "PDFBox (subsampling)" into a column prefix such as "pdfbox_subsampling".

    @param name library name as it appears in timings.csv
    @return lower-case name with runs of other characters replaced by one underscore
    """
    return re.sub(r'[^a-z0-9]+', '_', name.lower()).strip('_')


def read_times(run):
    """Reads timings.csv and returns per-file times and errors.

    @param run speed run directory
    @return (library names in order of first appearance, {file: {library: ms}}, {file: set of errors})
    """
    pages = collections.defaultdict(list)
    errors = collections.defaultdict(set)
    libraries = []
    with open(os.path.join(run, 'timings.csv'), newline='') as f:
        for r in csv.DictReader(f):
            if r['library'] not in libraries:
                libraries.append(r['library'])
            if r['error']:
                errors[r['file']].add(f"{r['library']}: {r['error']}")
            else:
                pages[(r['file'], r['library'], int(r['page']))].append(float(r['ms']))
    times = collections.defaultdict(lambda: collections.defaultdict(float))
    for (file, library, _), ms in pages.items():
        times[file][library] += statistics.median(ms)
    return libraries, times, errors


def read_differences(run):
    """Reads comparison.csv and counts the pages whose output failed the check.

    @param run speed run directory
    @return (set of files that were compared, Counter of failed pages per file)
    """
    compared = set()
    differing = collections.Counter()
    with open(os.path.join(run, 'comparison.csv'), newline='') as f:
        for r in csv.DictReader(f):
            compared.add(r['file'])
            if r['verdict'] != 'ok':
                differing[r['file']] += 1
    return compared, differing


def read_memory(memory_dir, libraries):
    """Reads run-memory.sh's measure output for each library.

    The file for the first library is memory-jpedal.csv; for the second it is the other memory-*.csv
    in the directory, since run-memory.sh names it after the --libraries key rather than the
    library's display name.

    @param memory_dir run-memory.sh output directory, or None
    @param libraries library names from timings.csv, JPedal first
    @return {library: {file: row}}, empty when memory_dir is None
    """
    if not memory_dir:
        return {}
    first = os.path.join(memory_dir, 'memory-jpedal.csv')
    others = [p for p in glob.glob(os.path.join(memory_dir, 'memory-*.csv')) if p != first]
    if len(others) != 1:
        sys.exit(f"expected one memory-*.csv besides memory-jpedal.csv in {memory_dir}, found {others}")
    memory = {}
    for library, path in zip(libraries, (first, others[0])):
        with open(path, newline='') as f:
            memory[library] = {r['file']: r for r in csv.DictReader(f)}
    return memory


def mb(memory, library, file, column):
    """Returns one memory figure in MB, or an empty string when the file wasn't measured.

    @param memory result of read_memory
    @param library library name
    @param file PDF file name
    @param column allocated_bytes or peak_retained_bytes
    @return figure formatted to one decimal place, or ''
    """
    r = memory.get(library, {}).get(file)
    if not r or r['error'] or not r[column]:
        return ''
    return f"{int(r[column]) / 1048576:.1f}"


def main():
    """Reads the run, profile and memory output and writes the CSV."""
    if len(sys.argv) not in (4, 5):
        sys.exit(__doc__)
    run, files_csv, out = sys.argv[1:4]
    memory_dir = sys.argv[4] if len(sys.argv) == 5 else None

    libraries, times, errors = read_times(run)
    if len(libraries) != 2 or libraries[0] != 'JPedal':
        sys.exit(f"expected JPedal and one other library in timings.csv, found {libraries}")
    a, b = libraries
    compared, differing = read_differences(run)
    memory = read_memory(memory_dir, libraries)
    with open(files_csv, newline='') as f:
        profile = {r['file']: r for r in csv.DictReader(f)}

    pa, pb = slug(a), slug(b)
    columns = ['file', 'pages', 'pdf_version', 'content_type', 'fonts_embedded', 'fonts_not_embedded',
               'output_check', 'pages_differing', f'{pa}_ms', f'{pb}_ms', f'{pa}_div_{pb}', 'faster']
    if memory:
        columns += [f'{pa}_allocated_mb', f'{pb}_allocated_mb', f'{pa}_peak_held_mb', f'{pb}_peak_held_mb']
    columns.append('error')

    with open(out, 'w', newline='') as f:
        writer = csv.writer(f, lineterminator='\n')
        writer.writerow(columns)
        for file in sorted(profile):
            p = profile[file]
            ta = times[file].get(a)
            tb = times[file].get(b)
            timed = not errors[file] and ta is not None and tb is not None
            if errors[file]:
                check = 'error'
            elif file not in compared:
                check = 'not compared'
            else:
                check = 'fail' if differing[file] else 'pass'
            ratio = faster = ''
            if timed:
                ratio = f"{ta / tb:.3f}"
                if abs(ta - tb) <= 5 or abs(ta - tb) <= 0.05 * max(ta, tb):
                    faster = 'draw'
                else:
                    faster = a if ta < tb else b
            row = [file, p['pages'], p['header_version'], p['content_type'], p['fonts_embedded'],
                   p['fonts_not_embedded'], check, differing[file] if file in compared else '',
                   f"{ta:.1f}" if timed else '', f"{tb:.1f}" if timed else '', ratio, faster]
            if memory:
                row += [mb(memory, a, file, 'allocated_bytes'), mb(memory, b, file, 'allocated_bytes'),
                        mb(memory, a, file, 'peak_retained_bytes'), mb(memory, b, file, 'peak_retained_bytes')]
            row.append('; '.join(sorted(errors[file])) or p['error'])
            writer.writerow(row)


if __name__ == '__main__':
    main()
