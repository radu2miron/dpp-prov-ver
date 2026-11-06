#!/usr/bin/env python3
"""Controlled INSTALL-only causal-ablation comparison (Python 3, stdlib only).

Calls the repository's existing run-performance.py and install.jmx unchanged.
The REST API selects `submitEvent` vs `submitEventBaseline` at STARTUP from
DPP_BENCHMARK_MODE. This script never switches the server mode automatically.

Examples from repository root:
  python3 tests/performance/run-baseline-comparison.py --mode baseline
  # reset the ledger; restart the API with DPP_BENCHMARK_MODE=unified
  python3 tests/performance/run-baseline-comparison.py --mode unified
  python3 tests/performance/run-baseline-comparison.py --report-only

Each phase executes 100/200 INSTALL requests, 3 repetitions (6 independent
fixtures per phase). All operations are committed; no client retries. A new
ledger is required BETWEEN modes. Result validation checks Fabric outcomes
and the actual causal clock of a committed event to catch a wrong API mode.
"""

import argparse
import csv
import datetime as dt
import json
import math
from pathlib import Path
import re
import statistics
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

TEST_DIR = Path(__file__).resolve().parent
ROOT = TEST_DIR.parents[1]
DEFAULT_RESULTS = ROOT / 'results' / 'performance' / 'baseline-comparison'
LEVELS = (100, 200)
REPETITIONS = 3
RUN_FIELDS = [
    'mode', 'threads', 'repetition', 'run_id', 'samples',
    'http_201', 'fabric_valid', 'semantic_accepted', 'semantic_rejected',
    'mvcc_conflicts', 'phantom_conflicts', 'other_failures',
    'mean_ms', 'p95_ms', 'within_run_std_ms', 'observed_req_per_s',
    'verified_mode', 'jtl_file', 'fixture_file', 'completed_utc',
]
AGG_FIELDS = [
    'mode', 'threads', 'runs', 'total_accepted', 'total_samples',
    'mean_of_run_means_ms', 'stddev_of_run_means_ms',
    'mean_of_run_p95_ms', 'mean_observed_req_per_s',
]
COMPARISON_FIELDS = [
    'threads', 'baseline_mean_ms', 'unified_mean_ms',
    'additional_mean_latency_ms', 'additional_mean_latency_pct',
    'baseline_mean_observed_req_per_s', 'unified_mean_observed_req_per_s',
    'baseline_between_run_std_ms', 'unified_between_run_std_ms',
]


def utcnow():
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()


def read_rows(path):
    if not path.exists():
        return []
    with path.open(newline='', encoding='utf-8-sig') as handle:
        return list(csv.DictReader(handle))


def write_rows(path, fields, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    with tmp.open('w', newline='', encoding='utf-8') as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    tmp.replace(path)


def check_api(base_url):
    with urllib.request.urlopen(base_url + '/api/health', timeout=15) as response:
        data = json.load(response)
        if response.status != 200 or data.get('status') != 'UP':
            raise RuntimeError(f'API health check failed: {data}')
    print('API_HEALTH=UP (this does NOT verify Fabric connectivity)', flush=True)


def verify_actual_mode(base_url, fixture_csv, expected_mode):
    """Check the *committed* event through the API, not an untrusted CLI label.

    Causal clocks are set on-chain for Unified INSTALLs and omitted by the
    benchmark Baseline. This guard catches a forgotten API restart/mode switch.
    """
    fixtures = read_rows(fixture_csv)
    if not fixtures or not fixtures[0].get('eventId'):
        raise RuntimeError(f'Cannot read committed event ID from {fixture_csv}')
    event_id = fixtures[0]['eventId']
    url = base_url + '/api/events/' + urllib.parse.quote(event_id, safe='')
    with urllib.request.urlopen(url, timeout=30) as response:
        event = json.load(response)
    if event.get('id') != event_id:
        raise RuntimeError(f'Read-back event ID mismatch: {event.get("id")!r} != {event_id!r}')
    clock = event.get('vectorClock') or {}
    if not isinstance(clock, dict):
        raise RuntimeError('Stored vectorClock is not a JSON object.')
    actual_mode = 'unified' if clock else 'baseline'
    if actual_mode != expected_mode:
        raise RuntimeError(
            f'WRONG API MODE: asked for {expected_mode}, but committed {event_id} '
            f'has a {"nonempty" if clock else "empty"} vectorClock, indicating {actual_mode}. '
            'Stop the experiment, set DPP_BENCHMARK_MODE correctly, restart the API, '
            'and use a fresh ledger before rerunning the affected phase.')
    print(f'VERIFIED_ROUTING={actual_mode} (committed event {event_id})', flush=True)
    return actual_mode


def latency_std_from_jtl(jtl_file, expected_samples):
    rows = read_rows(jtl_file)
    if len(rows) != expected_samples:
        raise RuntimeError(f'{jtl_file}: expected {expected_samples} samples, got {len(rows)}')
    durations = [int(r['elapsed']) for r in rows]
    return round(statistics.stdev(durations), 2) if len(durations) > 1 else 0.0


def require_accepted(summary_row, threads):
    expected = {
        'samples': threads,
        'http_201': threads,
        'fabric_valid': threads,
        'semantic_accepted': threads,
        'semantic_rejected': 0,
        'mvcc_conflicts': 0,
        'phantom_conflicts': 0,
        'other_failures': 0,
    }
    mismatches = []
    for k, value in expected.items():
        try:
            actual = int(summary_row[k])
        except (ValueError, KeyError, TypeError):
            actual = None
        if actual != value:
            mismatches.append(f'{k}: {actual!r} (expected {value})')
    if summary_row.get('mode') != 'install':
        mismatches.append('summary mode is not install')
    if mismatches:
        raise RuntimeError('INSTALL benchmark failed its acceptance checks:\n  ' + '\n  '.join(mismatches))


def check_phase_manifest(directory, args):
    """Prevent accidental reuse of a phase's results with different settings."""
    manifest = directory / 'study-config.json'
    settings = {
        'study': 'INSTALL causal-ablation baseline, synchronized single burst',
        'mode': args.mode, 'threads': list(LEVELS), 'repetitions': REPETITIONS,
        'ramp_up_s': 1, 'base_url': args.base_url.rstrip('/'),
        'prep_workers': args.prep_workers,
        'routing_check': 'committed LifecycleEvent vectorClock',
    }
    if manifest.exists():
        existing = json.loads(manifest.read_text(encoding='utf-8'))
        if existing != settings:
            raise RuntimeError(f'{manifest} describes a different experiment. '
                               'Use a new --results directory; do not mix the datasets.')
    else:
        directory.mkdir(parents=True, exist_ok=True)
        manifest.write_text(json.dumps(settings, indent=2) + '\n', encoding='utf-8')


def planned_runs():
    # Same sequence in both modes so ledger growth is approximately matched.
    return [(n, concurrency) for n in range(1, REPETITIONS + 1) for concurrency in LEVELS]


def run_install(args, repetition, threads, phase_dir):
    runner = TEST_DIR / 'run-performance.py'
    jmx = TEST_DIR / 'install.jmx'
    if not runner.is_file() or not jmx.is_file():
        raise RuntimeError('Missing run-performance.py or install.jmx next to this script.')
    command = [sys.executable, str(runner), 'install',
               '--threads', str(threads), '--ramp-up', '1',
               '--base-url', args.base_url.rstrip('/'),
               '--prep-workers', str(args.prep_workers),
               '--results', str(phase_dir)]
    label = f'repetition-{repetition:02d}-threads-{threads}'
    log = phase_dir / f'{label}.log'
    print(f'\n=== {args.mode.upper()} / repetition {repetition}/{REPETITIONS} / {threads} clients ===', flush=True)
    print('COMMAND=' + ' '.join(command), flush=True)
    captured = []
    with log.open('w', encoding='utf-8') as output:
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              universal_newlines=True, bufsize=1) as proc:
            for line in proc.stdout:
                output.write(line)
                output.flush()
                print(line, end='', flush=True)
                captured.append(line)
            return_code = proc.wait()
    if return_code != 0:
        raise RuntimeError(f'Benchmark exited with {return_code}. See {log}')
    stdout = ''.join(captured)
    found = re.search(r'^RUN_ID=(\S+)$', stdout, re.MULTILINE)
    if not found:
        raise RuntimeError(f'No RUN_ID found in {log}')
    run_id = found.group(1)
    rows = read_rows(phase_dir / 'summary.csv')
    matching = [r for r in rows if r.get('run_id') == run_id]
    if len(matching) != 1:
        raise RuntimeError(f'Expected one summary row for {run_id}, got {len(matching)}')
    row = matching[0]
    require_accepted(row, threads)
    fixture = phase_dir / f'{run_id}-install-data.csv'
    jtl = phase_dir / row['jtl_file']
    if not fixture.is_file() or not jtl.is_file():
        raise RuntimeError(f'Missing fixture or JTL for {run_id}')
    if len(read_rows(fixture)) != threads:
        raise RuntimeError(f'Fixture for {run_id} does not contain {threads} unique test operations')
    within_std_ms = latency_std_from_jtl(jtl, threads)
    verified_mode = verify_actual_mode(args.base_url.rstrip('/'), fixture, args.mode)
    return {
        'mode': args.mode, 'threads': threads, 'repetition': repetition,
        'run_id': run_id, 'samples': row['samples'], 'http_201': row['http_201'],
        'fabric_valid': row['fabric_valid'],
        'semantic_accepted': row['semantic_accepted'],
        'semantic_rejected': row['semantic_rejected'],
        'mvcc_conflicts': row['mvcc_conflicts'],
        'phantom_conflicts': row['phantom_conflicts'],
        'other_failures': row['other_failures'],
        'mean_ms': row['mean_ms'], 'p95_ms': row['p95_ms'],
        'within_run_std_ms': within_std_ms,
        'observed_req_per_s': row['observed_req_per_s'],
        'verified_mode': verified_mode,
        'jtl_file': str(jtl.relative_to(args.results.resolve())),
        'fixture_file': str(fixture.relative_to(args.results.resolve())),
        'completed_utc': utcnow(),
    }


def read_study_rows(results):
    rows = []
    for mode in ('baseline', 'unified'):
        rows += read_rows(results / mode / 'runs.csv')
    return rows


def make_reports(results):
    rows = read_study_rows(results)
    aggregated = []
    by_key = {}
    for row in rows:
        key = (row['mode'], int(row['threads']))
        by_key.setdefault(key, []).append(row)
    for (mode, threads), group in sorted(by_key.items()):
        means = [float(r['mean_ms']) for r in group]
        p95s = [float(r['p95_ms']) for r in group]
        rates = [float(r['observed_req_per_s']) for r in group]
        aggregated.append({
            'mode': mode, 'threads': threads, 'runs': len(group),
            'total_accepted': sum(int(r['semantic_accepted']) for r in group),
            'total_samples': sum(int(r['samples']) for r in group),
            'mean_of_run_means_ms': round(statistics.mean(means), 2),
            'stddev_of_run_means_ms': round(statistics.stdev(means), 2) if len(means) > 1 else '',
            'mean_of_run_p95_ms': round(statistics.mean(p95s), 2),
            'mean_observed_req_per_s': round(statistics.mean(rates), 2),
        })
    write_rows(results / 'aggregate.csv', AGG_FIELDS, aggregated)

    indexed = {(x['mode'], x['threads']): x for x in aggregated}
    comparisons = []
    for threads in LEVELS:
        b = indexed.get(('baseline', threads))
        u = indexed.get(('unified', threads))
        if not b or not u or int(b['runs']) != REPETITIONS or int(u['runs']) != REPETITIONS:
            continue
        b_ms = float(b['mean_of_run_means_ms'])
        u_ms = float(u['mean_of_run_means_ms'])
        comparisons.append({
            'threads': threads,
            'baseline_mean_ms': b_ms, 'unified_mean_ms': u_ms,
            'additional_mean_latency_ms': round(u_ms - b_ms, 2),
            'additional_mean_latency_pct': round(100 * (u_ms - b_ms) / b_ms, 2) if b_ms else '',
            'baseline_mean_observed_req_per_s': b['mean_observed_req_per_s'],
            'unified_mean_observed_req_per_s': u['mean_observed_req_per_s'],
            'baseline_between_run_std_ms': b['stddev_of_run_means_ms'],
            'unified_between_run_std_ms': u['stddev_of_run_means_ms'],
        })
    write_rows(results / 'comparison.csv', COMPARISON_FIELDS, comparisons)
    print('\nAGGREGATE=' + str(results / 'aggregate.csv'))
    print('COMPARISON=' + str(results / 'comparison.csv'))
    for row in aggregated:
        print(f"{row['mode']:8s} {row['threads']} clients: {row['runs']} runs, "
              f"mean={row['mean_of_run_means_ms']} ms, "
              f"std_of_run_means={row['stddev_of_run_means_ms']}, "
              f"mean_p95={row['mean_of_run_p95_ms']} ms")
    if len(comparisons) != len(LEVELS):
        print('NOTE: comparison.csv is incomplete until all six VERIFIED runs '
              'exist for BOTH modes.', flush=True)
    return comparisons


def main():
    parser = argparse.ArgumentParser(description='Minimal 12-run INSTALL Baseline vs Unified comparison')
    parser.add_argument('--mode', choices=['baseline', 'unified'],
                        help='Which mode is ALREADY active in the restarted Spring API')
    parser.add_argument('--report-only', action='store_true',
                        help='Rebuild aggregate/comparison files without executing any tests')
    parser.add_argument('--base-url', default='http://localhost:8081')
    parser.add_argument('--prep-workers', type=int, default=4)
    parser.add_argument('--results', type=Path, default=DEFAULT_RESULTS)
    args = parser.parse_args()
    args.results = args.results.resolve()
    if args.report_only:
        if args.mode:
            parser.error('--report-only does not take --mode')
        make_reports(args.results)
        return
    if args.mode is None:
        parser.error('select --mode baseline or --mode unified, or use --report-only')
    if args.prep_workers < 1:
        parser.error('--prep-workers must be >=1')
    parsed = urllib.parse.urlparse(args.base_url)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname:
        parser.error('--base-url must be a valid http(s) URL')
    phase_dir = args.results / args.mode
    check_phase_manifest(phase_dir, args)
    check_api(args.base_url.rstrip('/'))
    completed = read_rows(phase_dir / 'runs.csv')
    finished = {(int(row['repetition']), int(row['threads'])) for row in completed}
    planned = set(planned_runs())
    if not finished.issubset(planned):
        raise RuntimeError('runs.csv contains rows outside the planned study; use a new results directory.')
    if len(finished) != len(completed):
        raise RuntimeError('runs.csv contains duplicate repetition/thread pairs.')
    todo = [(rep, threads) for rep, threads in planned_runs() if (rep, threads) not in finished]
    print(f'PHASE={args.mode.upper()} READY; completed={len(completed)}/{len(planned)}, '
          f'remaining={len(todo)}', flush=True)
    print('WARNING: the API mode cannot be prevalidated without an INSTALL. '
          'After EACH benchmark the script reads back a committed event '
          'and checks its actual vectorClock.', flush=True)
    print('Ensure a separate clean ledger was used for this mode and that '
          'the API was RESTARTED with DPP_BENCHMARK_MODE=' + args.mode, flush=True)
    for repetition, threads in todo:
        result = run_install(args, repetition, threads, phase_dir)
        completed.append(result)
        write_rows(phase_dir / 'runs.csv', RUN_FIELDS, completed)
        print(f"VERIFIED_RUN={args.mode} rep={repetition} threads={threads} "
              f"mean={result['mean_ms']}ms p95={result['p95_ms']}ms", flush=True)
    make_reports(args.results)
    print(f'PHASE_COMPLETE={args.mode} ({len(completed)}/{len(planned)})', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, urllib.error.URLError) as error:
        print(f'ERROR: {error}', file=sys.stderr, flush=True)
        sys.exit(1)
