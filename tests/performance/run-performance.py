#!/usr/bin/env python3
"""DPP-Prov-Ver synchronous REST/Fabric benchmarks; Python 3 stdlib only.

Examples (from repository root):
  python3 tests/performance/run-performance.py create --threads 1 --duration 60
  python3 tests/performance/run-performance.py create --threads 10 --duration 60
  python3 tests/performance/run-performance.py install --threads 10
  python3 tests/performance/run-performance.py get --threads 50 --duration 60
  python3 tests/performance/run-performance.py get --get-resource asset --threads 50 --duration 60
  python3 tests/performance/run-performance.py history --threads 1 --history-depth 5
  python3 tests/performance/run-performance.py conflict-slot --threads 2 --ramp-up 0
  python3 tests/performance/run-performance.py conflict-component --threads 2 --ramp-up 0
  python3 tests/performance/run-performance.py conflict-rate --threads 100 --conflict-rate 5
  python3 tests/performance/run-performance.py conflict-rate --threads 100 --conflict-rate 10
  python3 tests/performance/run-performance.py conflict-rate --threads 100 --conflict-rate 20
  python3 tests/performance/run-performance.py idempotency --threads 2 --ramp-up 0

INSTALL = a synchronized burst: one prepared component/actor/slot per thread.
UPDATE = a 60-second sustained load: one precreated DPP per thread, with
schemaVersion changed on every request.
GET = sustained Fabric evaluateTransaction() reads: one pre-created DPP (default)
or asset per client. Every response is checked for HTTP 200, expected ID and docType.
HISTORY = sustained latest effective-time reconstruction (asOfPosition=0)
with N precommitted INSTALL events per DPP. Configurable query time: before/middle/end.
History fixtures may be reused to hold ledger size constant across concurrency runs.
CONFLICT-SLOT = two synchronized INSTALL requests by different actors/components
into the same host slot, followed by an untimed accepted-state check.
CONFLICT-COMPONENT = two synchronized INSTALL requests for ONE shared component
into TWO different host passports and distinct slots; checks I2 after commit.
CONFLICT-RATE = synchronized burst of 100 independent INSTALL attempts except
5/10/20 deliberately competing same-slot PAIRS (5/10/20 expected exclusions).
An untimed full accepted-state postcheck validates each winner and slot.
IDEMPOTENCY = sequential first INSTALL / identical replay / operationId collision;
then synchronized exact duplicate INSTALL. Both states checked after all requests.
Setup is excluded from measurement. Deliberate replay requests are the sole exception
to the no-retry performance-test policy; the Fabric client does not auto-retry.
"""

import argparse
import concurrent.futures
import csv
import datetime as dt
import json
import math
import os
import tempfile
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
TEST_DIR = Path(__file__).resolve().parent
DEFAULT_RESULTS = ROOT / 'results' / 'performance'
SUMMARY_FIELDS = [
    'run_id', 'mode', 'threads', 'ramp_up_s', 'duration_s',
    'samples', 'http_201', 'fabric_valid', 'semantic_accepted',
    'semantic_rejected', 'mvcc_conflicts', 'phantom_conflicts',
    'other_failures', 'mean_ms', 'p95_ms', 'observed_req_per_s',
    'expected_batch_timeout_ms', 'expected_max_message_count', 'jtl_file',
    'http_200', 'read_ok', 'history_depth', 'history_at', 'history_fixture',
    'history_ok',
    'conflict_result', 'conflict_winner', 'conflict_competitor',
    'conflict_fixture', 'conflict_state_ok',
    'planned_conflict_rate_pct', 'planned_conflict_pairs',
    'planned_contested_requests', 'expected_accepted',
    'observed_conflict_exclusions', 'observed_conflict_rate_pct',
    'verified_active_slots',
    'idempotency_result', 'idempotency_report',
]


def utcnow():
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')


def post_json(base_url, endpoint, payload, *, require_accepted=False):
    data = json.dumps(payload, separators=(',', ':')).encode('utf-8')
    req = urllib.request.Request(base_url + endpoint, data=data,
                                 headers={'Content-Type': 'application/json'}, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            raw = response.read()
            code = response.status
    except urllib.error.HTTPError as exc:
        code = exc.code
        raw = exc.read()
    try:
        body = json.loads(raw)
    except (ValueError, UnicodeError):
        body = {'_raw': raw[:300].decode('utf-8', 'replace')}
    if code != 201 or body.get('fabricStatus') != 'VALID':
        raise RuntimeError(f'{endpoint}: expected HTTP 201 / VALID, got {code}: {body}')
    if require_accepted and body.get('semanticOutcome') != 'ACCEPTED':
        raise RuntimeError(f'{endpoint}: expected semantic ACCEPTED, got {body}')
    return body


def prepare_install(base_url, result_dir, run_id, threads, workers):
    # A new host and passport per run avoid cross-run slot/causal interference.
    host = run_id + '-host'
    dpp = run_id + '-dpp'
    post_json(base_url, '/api/assets', {
        'docType': 'ASSET', 'id': host, 'assetType': 'MACHINE',
    })
    post_json(base_url, '/api/passports', {
        'docType': 'DIGITAL_PRODUCT_PASSPORT', 'id': dpp,
        'rootAssetId': host, 'schemaVersion': '1.0',
    })

    # Actor heads (CAUSAL_ACTOR_HEAD) are per actor in chaincode. Sharing one actor
    # between simultaneous INSTALL events would cause artificial MVCC conflicts.
    def make_row(i):
        actor = f'{run_id}-actor-{i:04d}'
        component = f'{run_id}-motor-{i:04d}'
        post_json(base_url, '/api/actors', {
            'docType': 'ACTOR', 'id': actor,
            'organizationId': 'Org1MSP' if i % 2 else 'Org2MSP',
            'role': 'MANUFACTURER',
        })
        post_json(base_url, '/api/assets', {
            'docType': 'ASSET', 'id': component, 'assetType': 'MOTOR',
        })
        event = f'{run_id}-install-{i:04d}'
        return {'actorId': actor, 'componentId': component,
                'eventId': event, 'operationId': 'op-' + event,
                'slotId': f'drive-{i:04d}'}

    print(f'SETUP: creating {threads} actors + {threads} motors (max {workers} workers); NOT timed by JMeter', flush=True)
    rows = [None] * threads
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        jobs = {pool.submit(make_row, i): i for i in range(1, threads + 1)}
        for job in concurrent.futures.as_completed(jobs):
            i = jobs[job]
            rows[i - 1] = job.result()
            if i % 25 == 0:
                print(f'SETUP: completed row {i} / {threads}', flush=True)

    data_file = result_dir / f'{run_id}-install-data.csv'
    with data_file.open('w', newline='', encoding='utf-8') as fd:
        writer = csv.DictWriter(fd, fieldnames=['actorId', 'componentId', 'eventId', 'operationId', 'slotId'])
        writer.writeheader()
        writer.writerows(rows)
    return host, dpp, data_file




def prepare_conflict_slot(base_url, result_dir, run_id, workers):
    """Prepare exactly TWO rival component installs into ONE host slot.

    Separate actors avoid artificial actor-head MVCC contention. Each candidate
    has a unique id and operationId; the only intended resource conflict is
    the single-capacity host slot. Preparation is NOT timed by JMeter.
    """
    host = f'{run_id}-host'
    dpp = f'{run_id}-dpp'
    slot = 'drive-conflict-1'
    post_json(base_url, '/api/assets', {
        'docType': 'ASSET', 'id': host, 'assetType': 'MACHINE',
    })
    post_json(base_url, '/api/passports', {
        'docType': 'DIGITAL_PRODUCT_PASSPORT', 'id': dpp,
        'rootAssetId': host, 'schemaVersion': '1.0',
    })

    def prepare_one(i):
        actor = f'{run_id}-actor-{i:04d}'
        motor = f'{run_id}-motor-{i:04d}'
        event = f'{run_id}-install-{i:04d}'
        post_json(base_url, '/api/actors', {
            'docType': 'ACTOR', 'id': actor,
            'organizationId': 'Org1MSP' if i == 1 else 'Org2MSP',
            'role': 'MANUFACTURER',
        })
        post_json(base_url, '/api/assets', {
            'docType': 'ASSET', 'id': motor, 'assetType': 'MOTOR',
        })
        return {'actorId': actor, 'componentId': motor, 'eventId': event,
                'operationId': 'op-' + event, 'slotId': slot}

    with concurrent.futures.ThreadPoolExecutor(max_workers=min(workers, 2)) as pool:
        futures = [pool.submit(prepare_one, i) for i in (1, 2)]
        rows = [f.result() for f in futures]
    csv_file = result_dir / f'{run_id}-conflict-slot-data.csv'
    with csv_file.open('w', newline='', encoding='utf-8') as f:
        out = csv.DictWriter(f, fieldnames=['actorId', 'componentId', 'eventId',
                                            'operationId', 'slotId'])
        out.writeheader()
        out.writerows(rows)
    return host, dpp, slot, csv_file, rows


def verify_conflict_slot(base_url, jtl, host, dpp, slot, rows, effective_time):
    """Require exactly one committed ACCEPTED and one expected competitor.

    MVCC abort and committed semantic REJECTED are distinct valid outcomes.
    Check the accepted physical state AFTER the measured burst, as an untimed
    evaluateTransaction. This makes correctness stronger than HTTP/JTL alone.
    """
    with jtl.open(newline='', encoding='utf-8-sig') as f:
        samples = list(csv.DictReader(f))
    if len(samples) != 2:
        raise RuntimeError(f'Expected exactly 2 INSTALL samples, got {len(samples)}')
    cases = [x.get('conflictCase', '') for x in samples]
    accepted = [i for i, case in enumerate(cases) if case == 'ACCEPTED']
    competing = [i for i, case in enumerate(cases)
                 if case in ('SEMANTIC_REJECTED', 'MVCC_READ_CONFLICT')]
    if len(accepted) != 1 or len(competing) != 1:
        raise RuntimeError('Unexpected contest outcomes ' + str(cases) +
                           ': require one ACCEPTED and one semantic REJECTED or MVCC')
    if any(x.get('success', '').lower() != 'true' for x in samples):
        raise RuntimeError('JMeter rejected one or more outcomes: ' + str(cases))

    # JMeter uses the CSV file rows in reading order (shareMode.all); match
    # accepted result to event id directly via response ID, not row order.
    winner_event_ids = [x.get('eventId', '') for x in samples if x.get('conflictCase') == 'ACCEPTED']
    if not winner_event_ids or winner_event_ids[0] not in {r['eventId'] for r in rows}:
        raise RuntimeError('Missing/unknown winner event id in JTL: ' + str(winner_event_ids))
    winner_id = winner_event_ids[0]
    winner = next(r for r in rows if r['eventId'] == winner_id)
    loser = next(r for r in rows if r['eventId'] != winner_id)
    # Confirm the event-level accepted decision exists on ledger.
    accepted_decision = get_json(base_url, '/api/decisions/' +
                                 urllib.parse.quote('decision:' + winner_id, safe=''))
    if accepted_decision.get('outcome') != 'ACCEPTED' or accepted_decision.get('eventId') != winner_id:
        raise RuntimeError(f'Winner decision mismatch: {accepted_decision}')
    competing_case = cases[competing[0]]
    if competing_case == 'SEMANTIC_REJECTED':
        rejected_decision = get_json(base_url, '/api/decisions/' +
                                     urllib.parse.quote('decision:' + loser['eventId'], safe=''))
        if rejected_decision.get('outcome') != 'REJECTED' or rejected_decision.get('eventId') != loser['eventId']:
            raise RuntimeError(f'Competitor rejection decision mismatch: {rejected_decision}')

    endpoint = ('/api/passports/' + urllib.parse.quote(dpp, safe='') + '/state?' +
                urllib.parse.urlencode({'effectiveTime': effective_time, 'asOfPosition': 0}))
    state = get_json(base_url, endpoint)
    active = state.get('activeIntervals')
    all_intervals = state.get('intervals')
    if state.get('dppId') != dpp or not isinstance(active, list) or not isinstance(all_intervals, list):
        raise RuntimeError('Invalid reconstructed state: ' + str(state)[:600])
    if len(active) != 1 or len(all_intervals) != 1:
        raise RuntimeError(f'Expected exactly 1 active/history interval, got {len(active)}/{len(all_intervals)}')
    interval = active[0]
    if all_intervals[0] != interval:
        raise RuntimeError('Reconstructed current and historical intervals differ')
    if (interval.get('hostId') != host or interval.get('slotId') != slot
            or interval.get('componentId') != winner['componentId']
            or interval.get('id') != winner_id or interval.get('validTo') is not None):
        raise RuntimeError('Wrong accepted interval: ' + str(interval))
    print(f'POSTCHECK=PASS exactly one component in shared slot: {winner["componentId"]}', flush=True)
    return {'conflict_result': 'PASS', 'conflict_winner': winner_id,
            'conflict_competitor': competing_case,
            'conflict_state_ok': 1}



def prepare_conflict_component(base_url, result_dir, run_id, workers):
    """Two host/DPP pairs compete for one shared physical component (I2).

    Actors and host slots are distinct to avoid actor-head or slot-guard
    contention; the component key is the intended shared contention point.
    No INSTALL operations occur during fixture preparation.
    """
    shared_component = f'{run_id}-shared-motor'
    post_json(base_url, '/api/assets', {
        'docType': 'ASSET', 'id': shared_component, 'assetType': 'MOTOR',
    })

    def prepare_one(i):
        host = f'{run_id}-host-{i:04d}'
        dpp = f'{run_id}-dpp-{i:04d}'
        actor = f'{run_id}-actor-{i:04d}'
        event = f'{run_id}-install-{i:04d}'
        slot = f'drive-{i:04d}'
        post_json(base_url, '/api/assets', {
            'docType': 'ASSET', 'id': host, 'assetType': 'MACHINE',
        })
        post_json(base_url, '/api/passports', {
            'docType': 'DIGITAL_PRODUCT_PASSPORT', 'id': dpp,
            'rootAssetId': host, 'schemaVersion': '1.0',
        })
        post_json(base_url, '/api/actors', {
            'docType': 'ACTOR', 'id': actor,
            'organizationId': 'Org1MSP' if i == 1 else 'Org2MSP',
            'role': 'MANUFACTURER',
        })
        return {'actorId': actor, 'componentId': shared_component,
                'eventId': event, 'operationId': 'op-' + event,
                'hostId': host, 'dppId': dpp, 'slotId': slot}

    print('SETUP: two independent host/DPP/actor groups, ONE shared motor; NOT timed', flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(2, workers)) as pool:
        futures = [pool.submit(prepare_one, i) for i in (1, 2)]
        rows = [f.result() for f in futures]
    fields = ['actorId', 'componentId', 'eventId', 'operationId',
              'hostId', 'dppId', 'slotId']
    fixture = result_dir / f'{run_id}-conflict-component-data.csv'
    with fixture.open('w', newline='', encoding='utf-8') as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    return shared_component, fixture, rows


def verify_conflict_component(base_url, jtl, shared_component, rows, effective_time):
    """Prove one ACCEPTED event and one host containing the component at tau.

    An MVCC-invalid transaction MUST NOT have a committed decision; a VALID,
    semantically rejected transaction MUST have a REJECTED decision. We check
    both DPP histories independently rather than trusting the 2 HTTP results.
    """
    with jtl.open(newline='', encoding='utf-8-sig') as handle:
        samples = list(csv.DictReader(handle))
    if len(samples) != 2:
        raise RuntimeError(f'Expected exactly two INSTALL samples, got {len(samples)}')
    if any(sample.get('success', '').lower() != 'true' for sample in samples):
        raise RuntimeError('JMeter marked an unexpected INSTALL outcome as failure')
    case_by_id = {sample.get('eventId', ''): sample.get('conflictCase', '')
                  for sample in samples}
    rows_by_id = {row['eventId']: row for row in rows}
    if len(case_by_id) != 2 or set(case_by_id) != set(rows_by_id):
        raise RuntimeError(f'Fixture/JTL event IDs mismatch: {case_by_id}')
    accepted = [eid for eid, case in case_by_id.items() if case == 'ACCEPTED']
    excluded = [eid for eid, case in case_by_id.items()
                if case in ('SEMANTIC_REJECTED', 'MVCC_READ_CONFLICT')]
    if len(accepted) != 1 or len(excluded) != 1:
        raise RuntimeError('Expected one ACCEPTED and one excluded competitor: '
                           + str(case_by_id))
    winner_id = accepted[0]
    competitor_id = excluded[0]
    winner = rows_by_id[winner_id]
    outcome = case_by_id[competitor_id]
    if any(row['componentId'] != shared_component for row in rows):
        raise RuntimeError('Contenders do not share the same component')
    if len({row['hostId'] for row in rows}) != 2 or len({row['dppId'] for row in rows}) != 2:
        raise RuntimeError('Contenders must have different hosts and DPPs')
    if len({(row['hostId'], row['slotId']) for row in rows}) != 2:
        raise RuntimeError('Contenders must not share the same host-slot key')
    decision = get_json(base_url, '/api/decisions/' +
                        urllib.parse.quote('decision:' + winner_id, safe=''))
    if decision.get('outcome') != 'ACCEPTED' or decision.get('eventId') != winner_id:
        raise RuntimeError(f'Winner ACCEPTED decision invalid: {decision}')
    if outcome == 'SEMANTIC_REJECTED':
        reject_decision = get_json(base_url, '/api/decisions/' +
                                   urllib.parse.quote('decision:' + competitor_id, safe=''))
        if reject_decision.get('outcome') != 'REJECTED' or reject_decision.get('eventId') != competitor_id:
            raise RuntimeError(f'Competitor REJECTED decision invalid: {reject_decision}')

    active_total = 0
    for row in rows:
        url = ('/api/passports/' + urllib.parse.quote(row['dppId'], safe='') +
               '/state?' + urllib.parse.urlencode({'effectiveTime': effective_time,
                                                    'asOfPosition': 0}))
        state = get_json(base_url, url)
        if state.get('dppId') != row['dppId'] or not isinstance(state.get('activeIntervals'), list) \
                or not isinstance(state.get('intervals'), list):
            raise RuntimeError('Malformed reconstructed state for ' + row['dppId'])
        active = state['activeIntervals']
        intervals = state['intervals']
        expected = 1 if row['eventId'] == winner_id else 0
        if len(active) != expected or len(intervals) != expected:
            raise RuntimeError(f'Unexpected history for {row["dppId"]}: '
                               f'active={len(active)} history={len(intervals)}, expected {expected}')
        for interval in active:
            if (interval.get('componentId') != shared_component or
                    interval.get('hostId') != row['hostId'] or
                    interval.get('slotId') != row['slotId'] or
                    interval.get('id') != winner_id or
                    interval.get('validTo') is not None or
                    interval not in intervals):
                raise RuntimeError(f'Wrong current component placement in {row["dppId"]}: {interval}')
        active_total += len(active)
    if active_total != 1:
        raise RuntimeError(f'Exclusive direct host invariant violated: {active_total} active placements')
    print(f'POSTCHECK=PASS I2: component {shared_component} is present only in '
          f'{winner["dppId"]} / {winner["hostId"]}; competitor={outcome}', flush=True)
    return {'conflict_result': 'PASS', 'conflict_winner': winner_id,
            'conflict_competitor': outcome, 'conflict_state_ok': 1}



def prepare_conflict_rate(base_url, result_dir, run_id, threads, workers, rate):
    """Create 100 candidates: K shared-slot pairs, rest independent slots.

    K = 100 * rate / 100. Each shared-slot pair produces one EXPECTED
    excluded transaction; the realized winner is not predetermined. Thus
    5% here means five excluded candidates / 100 attempts, rather than 5%
    of pairs or 5% of the total candidates being members of a pair.
    """
    n_pairs = threads * rate // 100
    host = f'{run_id}-host'
    dpp = f'{run_id}-dpp'
    post_json(base_url, '/api/assets', {
        'docType': 'ASSET', 'id': host, 'assetType': 'MACHINE',
    })
    post_json(base_url, '/api/passports', {
        'docType': 'DIGITAL_PRODUCT_PASSPORT', 'id': dpp,
        'rootAssetId': host, 'schemaVersion': '1.0',
    })
    template = []
    for j in range(1, n_pairs + 1):
        for _ in range(2):
            template.append((f'pair-{j:03d}', f'contested-{j:03d}', '1'))
    for j in range(1, threads - 2*n_pairs + 1):
        template.append((f'free-{j:03d}', f'free-{j:03d}', '0'))
    assert len(template) == threads

    def make_candidate(i, group, slot, contended):
        actor = f'{run_id}-actor-{i:04d}'
        motor = f'{run_id}-motor-{i:04d}'
        event = f'{run_id}-install-{i:04d}'
        post_json(base_url, '/api/actors', {
            'docType': 'ACTOR', 'id': actor,
            'organizationId': 'Org1MSP' if i % 2 else 'Org2MSP',
            'role': 'MANUFACTURER',
        })
        post_json(base_url, '/api/assets', {
            'docType': 'ASSET', 'id': motor, 'assetType': 'MOTOR',
        })
        return {'actorId': actor, 'componentId': motor,
                'eventId': event, 'operationId': 'op-' + event,
                'hostId': host, 'dppId': dpp,
                'slotId': slot, 'groupId': group, 'contended': contended}

    print(f'SETUP: {threads} motors/actors; {n_pairs} shared-slot pairs, '
          f'{threads-2*n_pairs} independent slots (max {workers} workers); NOT timed', flush=True)
    rows = [None] * threads
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(make_candidate, i, *template[i-1]): i
                   for i in range(1, threads + 1)}
        done = 0
        for future in concurrent.futures.as_completed(futures):
            i = futures[future]
            rows[i - 1] = future.result()
            done += 1
            if done % 25 == 0 or done == threads:
                print(f'SETUP: {done}/{threads} candidate records ready', flush=True)

    # Ensure the CSV does not consistently assign contended groups to early
    # threads. Stable within a run and unrelated to the winner's actor/ID.
    import random
    random.Random(run_id).shuffle(rows)
    fields = ['actorId', 'componentId', 'eventId', 'operationId',
              'hostId', 'dppId', 'slotId', 'groupId', 'contended']
    fixture = result_dir / f'{run_id}-conflict-rate-data.csv'
    with fixture.open('w', newline='', encoding='utf-8') as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    return host, dpp, fixture, rows, n_pairs


def verify_conflict_rate(base_url, jtl, host, dpp, fixture_rows, rate, effective_time,
                         result_dir, run_id):
    """Strict no-retry verification: known fixture rows, pair outcomes and ledger state.

    Only the designated shared-slot pairs may yield an MVCC-invalid or
    semantically rejected INSTALL. All independent INSTALLs MUST be accepted.
    The valid outcomes for each pair are [ACCEPTED, EXCLUDED] in any order.
    """
    with jtl.open(newline='', encoding='utf-8-sig') as handle:
        jreader = csv.DictReader(handle)
        mandatory = {'eventId', 'groupId', 'slotId', 'contended', 'conflictCase',
                     'responseCode', 'fabricStatus', 'semanticOutcome',
                     'validationCode', 'success'}
        if not mandatory.issubset(jreader.fieldnames or []):
            raise RuntimeError(f'CONFLICT-RATE: missing JTL fields: '
                               f'{mandatory - set(jreader.fieldnames or [])}')
        samples = list(jreader)
    if len(samples) != len(fixture_rows):
        raise RuntimeError(f'Expected {len(fixture_rows)} samples, observed {len(samples)}')
    by_id = {r['eventId']: r for r in fixture_rows}
    measured = {r['eventId']: r for r in samples}
    if len(measured) != len(samples) or set(measured) != set(by_id):
        raise RuntimeError('Duplicate/missing event IDs or unexpected JMeter samples')

    groups = {}
    for original in fixture_rows:
        groups.setdefault(original['groupId'], []).append(original)
    pair_groups = {g: grp for g, grp in groups.items() if grp[0]['contended'] == '1'}
    n_pairs = len(pair_groups)
    if (n_pairs != len(fixture_rows) * rate // 100
            or any(len(grp) != 2 or len({x['slotId'] for x in grp}) != 1 or
                   any(x['contended'] != '1' for x in grp) for grp in pair_groups.values())
            or any(len(grp) != 1 or grp[0]['contended'] != '0'
                   for g,grp in groups.items() if g not in pair_groups)
            or len({grp[0]['slotId'] for grp in groups.values()}) != len(groups)):
        raise RuntimeError('Invalid fixture: expected disjoint pairs plus independent slots')
    if any(row['hostId'] != host or row['dppId'] != dpp for row in fixture_rows):
        raise RuntimeError('Invalid fixture host/DPP: all requests must target one root')

    for eid, row in measured.items():
        f = by_id[eid]
        if (row['groupId'] != f['groupId'] or row['slotId'] != f['slotId']
                or row['contended'] != f['contended']):
            raise RuntimeError(f'JTL group metadata mismatch for {eid}')
        if row['success'].lower() != 'true' or row['conflictCase'] == 'UNEXPECTED':
            raise RuntimeError(f'Unexpected response for {eid}: '
                               f'{row["responseCode"]}, {row["validationCode"]}, '
                               f'{row["conflictCase"]}')
        if row['conflictCase'] == 'ACCEPTED' and not (
                row['responseCode'] == '201' and row['fabricStatus'] == 'VALID'
                and row['semanticOutcome'] == 'ACCEPTED'):
            raise RuntimeError(f'Invalid ACCEPTED classification for {eid}')
        if row['conflictCase'] == 'SEMANTIC_REJECTED' and not (
                row['responseCode'] == '201' and row['fabricStatus'] == 'VALID'
                and row['semanticOutcome'] == 'REJECTED'):
            raise RuntimeError(f'Invalid semantic rejection classification for {eid}')
        if row['conflictCase'] == 'MVCC_READ_CONFLICT' and not (
                row['responseCode'] == '409' and row['validationCode'] == 'MVCC_READ_CONFLICT'):
            raise RuntimeError(f'Invalid MVCC classification for {eid}')

    expected_winners = {}
    exclusions = []
    for group_id, grp in groups.items():
        accepted = [r for r in grp if measured[r['eventId']]['conflictCase'] == 'ACCEPTED']
        excluded = [r for r in grp if measured[r['eventId']]['conflictCase'] in
                    ('SEMANTIC_REJECTED', 'MVCC_READ_CONFLICT')]
        if group_id in pair_groups:
            if len(accepted) != 1 or len(excluded) != 1:
                raise RuntimeError(f'Contested group {group_id}: require 1 accepted/1 excluded; '
                                   f'observed {[measured[r["eventId"]]["conflictCase"] for r in grp]}')
            exclusions.append(excluded[0]['eventId'])
        elif len(accepted) != 1 or excluded:
            raise RuntimeError(f'Independent group {group_id}: expected an ACCEPTED operation; '
                               f'observed {[measured[r["eventId"]]["conflictCase"] for r in grp]}')
        expected_winners[accepted[0]['slotId']] = accepted[0]

    # Read the ledger's committed decisions in one evaluate() call. The API
    # maps a missing single decision through a Fabric Gateway error (often HTTP
    # 502, not 404), so a negative individual GET is NOT a reliable absence test.
    # The decision-list endpoint exists in the current Spring Boot API.
    all_decisions = get_json(base_url, '/api/decisions')
    if not isinstance(all_decisions, list):
        raise RuntimeError('GET /api/decisions did not return a list')
    measured_ids = set(measured)
    decision_by_id = {}
    for decision in all_decisions:
        if not isinstance(decision, dict):
            raise RuntimeError('Malformed ledger decision in list')
        eid = decision.get('eventId')
        if eid in measured_ids:
            if eid in decision_by_id:
                raise RuntimeError(f'Duplicate ledger decisions for {eid}')
            decision_by_id[eid] = decision
    for eid, sample in measured.items():
        actual = decision_by_id.get(eid)
        if sample['conflictCase'] == 'MVCC_READ_CONFLICT':
            if actual is not None:
                raise RuntimeError(f'MVCC-aborted {eid} has a committed decision: {actual}')
        else:
            wanted = 'ACCEPTED' if sample['conflictCase'] == 'ACCEPTED' else 'REJECTED'
            if (actual is None or actual.get('outcome') != wanted or
                    actual.get('eventId') != eid or actual.get('id') != 'decision:' + eid):
                raise RuntimeError(f'Decision mismatch for {eid}, expected {wanted}: {actual}')

    endpoint = ('/api/passports/' + urllib.parse.quote(dpp, safe='') + '/state?' +
                urllib.parse.urlencode({'effectiveTime': effective_time,
                                        'asOfPosition': 0}))
    state = get_json(base_url, endpoint)
    active = state.get('activeIntervals')
    historical = state.get('intervals')
    if (state.get('dppId') != dpp or not isinstance(active, list)
            or not isinstance(historical, list)):
        raise RuntimeError('Malformed reconstructed state or incorrect DPP id')
    expected = len(expected_winners)
    if len(active) != expected or len(historical) != expected:
        raise RuntimeError(f'Wrong number of intervals: active={len(active)}, '
                           f'history={len(historical)}, expected={expected}')
    active_by_slot = {}
    for interval in active:
        slot = interval.get('slotId')
        if slot in active_by_slot or slot not in expected_winners:
            raise RuntimeError(f'Double-occupied/unexpected slot {slot}: {interval}')
        winner = expected_winners[slot]
        if (interval.get('hostId') != host or
                interval.get('componentId') != winner['componentId'] or
                interval.get('id') != winner['eventId'] or
                interval.get('validTo') is not None or interval not in historical):
            raise RuntimeError(f'Wrong accepted interval for {slot}: {interval}')
        active_by_slot[slot] = interval
    if len(active_by_slot) != expected or len({x['componentId'] for x in active}) != expected:
        raise RuntimeError('Component uniqueness or slot occupancy invariant violated')
    if len({x['id'] for x in historical}) != expected:
        raise RuntimeError('Wrong historical event count / duplicates')

    outcomes = {}
    for s in samples:
        outcomes[s['conflictCase']] = outcomes.get(s['conflictCase'], 0) + 1
    report = {'run_id': run_id, 'requested_rate_pct': rate,
              'total_requests': len(samples), 'planned_conflict_pairs': n_pairs,
              'contended_requests': 2*n_pairs, 'expected_accepted': expected,
              'outcomes': outcomes, 'postcheck': 'PASS',
              'verified_active_slots': len(active_by_slot)}
    report_path = result_dir / f'{run_id}-conflict-rate-report.json'
    report_path.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(f'POSTCHECK=PASS: {expected} distinct accepted slots; '
          f'{len(exclusions)} excluded competitors ({rate}% of {len(samples)} attempts)', flush=True)
    print(f'REPORT={report_path}', flush=True)
    return {'conflict_result': 'PASS', 'conflict_state_ok': 1,
            'conflict_competitor': 'MIXED' if len(set(measured[e]['conflictCase'] for e in exclusions)) > 1
                                   else measured[exclusions[0]]['conflictCase'] if exclusions else 'NONE',
            'planned_conflict_rate_pct': rate,
            'planned_conflict_pairs': n_pairs,
            'planned_contested_requests': 2*n_pairs,
            'expected_accepted': expected,
            'observed_conflict_exclusions': len(exclusions),
            'observed_conflict_rate_pct': round(100*len(exclusions)/len(samples), 2),
            'verified_active_slots': len(active_by_slot)}

def prepare_update(base_url, result_dir, run_id, threads, workers):
    """Create one dedicated host asset and DPP per JMeter thread (outside timing).

    Each thread only ever updates its own DPP, so the benchmark does not
    intentionally create MVCC contention from shared read/write keys.
    """
    def make_row(i):
        host = f'{run_id}-host-{i}'
        dpp = f'{run_id}-dpp-{i}'
        post_json(base_url, '/api/assets', {
            'docType': 'ASSET', 'id': host,
            'externalId': 'urn:performance:' + host,
            'assetType': 'MACHINE', 'identityPolicyRef': 'POLICY-V1',
        })
        post_json(base_url, '/api/passports', {
            'docType': 'DIGITAL_PRODUCT_PASSPORT', 'id': dpp,
            'rootAssetId': host, 'schemaVersion': '1.0',
        })
        return {'thread': i, 'hostId': host, 'dppId': dpp}

    print(f'SETUP: creating {threads} host assets + {threads} DPPs '
          f'(max {workers} workers); NOT timed by JMeter', flush=True)
    rows = [None] * threads
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        jobs = {pool.submit(make_row, i): i for i in range(1, threads + 1)}
        for job in concurrent.futures.as_completed(jobs):
            i = jobs[job]
            rows[i - 1] = job.result()
            if i % 25 == 0:
                print(f'SETUP: completed row {i} / {threads}', flush=True)

    data_file = result_dir / f'{run_id}-update-data.csv'
    with data_file.open('w', newline='', encoding='utf-8') as fd:
        writer = csv.DictWriter(fd, fieldnames=['thread', 'hostId', 'dppId'])
        writer.writeheader()
        writer.writerows(rows)
    return data_file



def prepare_get(base_url, result_dir, run_id, threads, workers, resource):
    """Create dedicated read targets outside the JMeter measurement window.

    The GET plan repeatedly reads the same immutable target for each client.
    This measures point-lookup performance of a small/hot working set, NOT
    scaling with ledger size or historical reconstruction.
    """
    def make_row(i):
        if resource == 'asset':
            asset_id = f'{run_id}-get-asset-{i}'
            post_json(base_url, '/api/assets', {
                'docType': 'ASSET', 'id': asset_id,
                'assetType': 'MACHINE',
                'externalId': 'urn:performance:' + asset_id,
                'identityPolicyRef': 'POLICY-V1',
            })
            return {'thread': i, 'resource': 'asset', 'id': asset_id}

        host_id = f'{run_id}-get-host-{i}'
        dpp_id = f'{run_id}-get-dpp-{i}'
        post_json(base_url, '/api/assets', {
            'docType': 'ASSET', 'id': host_id,
            'assetType': 'MACHINE',
            'externalId': 'urn:performance:' + host_id,
            'identityPolicyRef': 'POLICY-V1',
        })
        post_json(base_url, '/api/passports', {
            'docType': 'DIGITAL_PRODUCT_PASSPORT', 'id': dpp_id,
            'rootAssetId': host_id, 'schemaVersion': '1.0',
        })
        return {'thread': i, 'resource': 'passport', 'id': dpp_id}

    label = 'assets' if resource == 'asset' else 'host assets + DPPs'
    print(f'SETUP: creating {threads} {label} for GET '
          f'(max {workers} workers); NOT timed by JMeter', flush=True)
    rows = [None] * threads
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        jobs = {pool.submit(make_row, i): i for i in range(1, threads + 1)}
        for job in concurrent.futures.as_completed(jobs):
            i = jobs[job]
            rows[i - 1] = job.result()
            if i % 25 == 0:
                print(f'SETUP: completed row {i} / {threads}', flush=True)

    data_file = result_dir / f'{run_id}-get-data.csv'
    with data_file.open('w', newline='', encoding='utf-8') as fd:
        writer = csv.DictWriter(fd, fieldnames=['thread', 'resource', 'id'])
        writer.writeheader()
        writer.writerows(rows)
    # The JMX builds these IDs from runId and threadNum; the CSV is a setup audit.
    return data_file


def get_json(base_url, endpoint):
    req = urllib.request.Request(base_url + endpoint, headers={'Accept': 'application/json'})
    with urllib.request.urlopen(req, timeout=120) as response:
        if response.status != 200:
            raise RuntimeError(f'GET {endpoint}: unexpected HTTP {response.status}')
        return json.load(response)


def history_query_params(depth, reference, at):
    """Timestamps are anchored 24h in the past; each INSTALL is 1s apart."""
    before = reference
    middle = reference + dt.timedelta(seconds=max(1, depth // 2), milliseconds=500)
    end = reference + dt.timedelta(seconds=depth + 1)
    times = {name: moment.strftime('%Y-%m-%dT%H:%M:%SZ') if name != 'middle'
             else moment.isoformat(timespec='milliseconds').replace('+00:00', 'Z')
             for name, moment in [('before', before), ('middle', middle), ('end', end)]}
    # N==0 also gives empty state at all times
    expected = {'before': 0, 'middle': max(1, depth // 2) if depth else 0, 'end': depth}
    return times, expected


def prepare_history(base_url, result_dir, run_id, targets, workers, depth):
    """Precommit accepted INSTALL events, one actor, host and DPP per read target.

    Each target has `depth` independent components in distinct slots, with
    effective timestamps in ascending order. Setup is not measured by JMeter.
    Avoid parallel operations from the SAME actor: those could produce MVCC.
    """
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    base = now - dt.timedelta(days=1)
    # Require past effective time to prevent future-dated assertions.
    if depth >= 23 * 3600:
        raise ValueError('history-depth must be below 82800 (all events must be in the past)')
    times, _ = history_query_params(depth, base, 'end')
    print(f'SETUP: {targets} DPPs with {depth} accepted INSTALL events each '
          f'({targets * depth} events, {workers} parallel targets). Not timed by JMeter.', flush=True)
    def make_row(i):
        suffix = str(i)
        host = f'{run_id}-history-host-{suffix}'
        dpp = f'{run_id}-history-dpp-{suffix}'
        actor = f'{run_id}-history-actor-{suffix}'
        post_json(base_url, '/api/assets', {
            'docType': 'ASSET', 'id': host, 'assetType': 'MACHINE',
        })
        post_json(base_url, '/api/passports', {
            'docType': 'DIGITAL_PRODUCT_PASSPORT', 'id': dpp,
            'rootAssetId': host, 'schemaVersion': '1.0',
        })
        if depth:
            post_json(base_url, '/api/actors', {
                'docType': 'ACTOR', 'id': actor,
                'organizationId': 'Org1MSP' if i % 2 else 'Org2MSP',
                'role': 'MANUFACTURER',
            })
        for j in range(1, depth + 1):
            component = f'{run_id}-history-motor-{i}-{j}'
            event = f'{run_id}-history-install-{i}-{j}'
            post_json(base_url, '/api/assets', {
                'docType': 'ASSET', 'id': component, 'assetType': 'MOTOR',
            })
            effective_at = (base + dt.timedelta(seconds=j)).strftime('%Y-%m-%dT%H:%M:%SZ')
            post_json(base_url, '/api/events', {
                'docType': 'LIFECYCLE_EVENT', 'id': event,
                'operationId': 'op-' + event,
                'type': 'INSTALL', 'actorId': actor,
                'dppIds': [dpp], 'affectedAssetIds': [host, component],
                'parentEventIds': [], 'effectiveTime': effective_at,
                'issuedAt': now.strftime('%Y-%m-%dT%H:%M:%SZ'),
                'data': {'componentId': component, 'hostId': host,
                         'slotId': f'drive-{j}'},
            }, require_accepted=True)
        return {'thread': i, 'dppId': dpp, 'historyDepth': depth,
                'beforeTime': times['before'], 'middleTime': times['middle'],
                'endTime': times['end']}

    rows = [None] * targets
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        jobs = {pool.submit(make_row, i): i for i in range(1, targets + 1)}
        for job in concurrent.futures.as_completed(jobs):
            i = jobs[job]
            rows[i-1] = job.result()
            if i % 10 == 0 or i == targets:
                print(f'SETUP: completed target {i}/{targets}', flush=True)
    data_file = result_dir / f'{run_id}-history-data.csv'
    with data_file.open('w', newline='', encoding='utf-8') as fd:
        writer = csv.DictWriter(fd, fieldnames=['thread', 'dppId', 'historyDepth',
                                                'beforeTime', 'middleTime', 'endTime'])
        writer.writeheader()
        writer.writerows(rows)
    return data_file


def read_history_fixture(path, threads):
    with path.open(newline='', encoding='utf-8-sig') as fd:
        rows = list(csv.DictReader(fd))
    needed = {'thread', 'dppId', 'historyDepth', 'beforeTime', 'middleTime', 'endTime'}
    if not rows or not needed.issubset(rows[0]):
        raise RuntimeError(f'Invalid history fixture: {path}')
    if len(rows) < threads:
        raise RuntimeError(f'Fixture has {len(rows)} targets; need {threads}.')
    first = rows[0]['dppId']
    if not first.endswith('-history-dpp-1'):
        raise RuntimeError('Unexpected fixture DPP naming convention.')
    prefix = first[:-1]
    depth = int(rows[0]['historyDepth'])
    for idx, row in enumerate(rows, 1):
        if int(row['thread']) != idx or row['dppId'] != prefix + str(idx) or int(row['historyDepth']) != depth:
            raise RuntimeError(f'Non-contiguous or inconsistent history fixture row {idx}')
        if any(row[time] != rows[0][time] for time in ('beforeTime', 'middleTime', 'endTime')):
            raise RuntimeError('Fixture rows have inconsistent query times')
    return rows, prefix, depth


def verify_history_setup(base_url, dpp_id, effective_time, expected):
    endpoint = '/api/passports/' + urllib.parse.quote(dpp_id, safe='') + '/state?'
    endpoint += urllib.parse.urlencode({'effectiveTime': effective_time, 'asOfPosition': 0})
    body = get_json(base_url, endpoint)
    if body.get('dppId') != dpp_id or not isinstance(body.get('activeIntervals'), list) \
            or not isinstance(body.get('intervals'), list) \
            or len(body['activeIntervals']) != expected or len(body['intervals']) != expected:
        raise RuntimeError(f'History preflight failed for {dpp_id}: '
                           f'expected {expected} intervals, got {str(body)[:500]}')
    print(f'PREFLIGHT=PASS history state for {dpp_id}: {expected} intervals', flush=True)


def p95(values):
    ordered = sorted(values)
    return ordered[math.ceil(len(ordered) * 0.95) - 1]


def summarize(jtl, run_id, args, duration_s):
    with jtl.open(newline='', encoding='utf-8-sig') as handle:
        reader = csv.DictReader(handle)
        required = {'timeStamp', 'elapsed', 'responseCode', 'success',
                    'fabricStatus', 'semanticOutcome', 'validationCode'}
        if not reader.fieldnames or not required.issubset(reader.fieldnames):
            raise RuntimeError('JTL is missing required CSV headers/semantic fields. '
                               f'Found {reader.fieldnames}; ensure -Jsample_variables is respected.')
        rows = list(reader)
    if not rows:
        raise RuntimeError('JMeter produced an empty JTL: no request was measured.')
    elapsed = [int(r['elapsed']) for r in rows]
    started = [int(r['timeStamp']) for r in rows]
    # timeStamp is sample START. For writes, elapsed includes commit confirmation;
    # GET uses evaluateTransaction() and does NOT wait for a block commit.
    window_ms = max(s + ms for s, ms in zip(started, elapsed)) - min(started)
    count = len(rows)
    mvcc = sum('MVCC_READ_CONFLICT' in r.get('validationCode', '') for r in rows)
    phantom = sum('PHANTOM_READ_CONFLICT' in r.get('validationCode', '') for r in rows)
    if args.mode in ('get', 'history'):
        status_field = 'readStatus' if args.mode == 'get' else 'historyStatus'
        if status_field not in (reader.fieldnames or []):
            raise RuntimeError(f'{args.mode.upper()} JTL is missing {status_field}. Check JMX and -Jsample_variables.')
        ok = sum(r.get('success', '').lower() == 'true' and
                 r['responseCode'] == '200' and r.get(status_field) == 'MATCH' for r in rows)
    else:
        ok = sum(r.get('success', '').lower() == 'true' for r in rows)
    ans = {
        'run_id': run_id, 'mode': args.mode,
        'threads': args.threads, 'ramp_up_s': args.ramp_up,
        'duration_s': duration_s,
        'samples': count,
        'http_201': sum(r['responseCode'] == '201' for r in rows),
        'fabric_valid': sum(r['fabricStatus'] == 'VALID' for r in rows),
        'semantic_accepted': sum(r['semanticOutcome'] == 'ACCEPTED' for r in rows),
        'semantic_rejected': sum(r['semanticOutcome'] == 'REJECTED' for r in rows),
        'mvcc_conflicts': mvcc, 'phantom_conflicts': phantom,
        'other_failures': (count - ok) if args.mode in ('conflict-slot', 'conflict-component', 'conflict-rate', 'idempotency') else (count - ok - mvcc - phantom),
        'mean_ms': round(statistics.mean(elapsed), 1),
        'p95_ms': p95(elapsed),
        'observed_req_per_s': round(1000 * count / max(1, window_ms), 2),
        'expected_batch_timeout_ms': 500,
        'expected_max_message_count': 50,
        'jtl_file': jtl.name,
        'http_200': sum(r['responseCode'] == '200' for r in rows),
        'read_ok': ok if args.mode in ('get', 'history') else 0,
        'history_depth': args.history_depth if args.mode == 'history' else '',
        'history_at': args.history_at if args.mode == 'history' else '',
        'history_fixture': str(args.history_fixture or '') if args.mode == 'history' else '',
        'history_ok': ok if args.mode == 'history' else 0,
        'conflict_result': '', 'conflict_winner': '',
        'conflict_competitor': '', 'conflict_fixture': '',
        'conflict_state_ok': '',
    }
    return ans



def append_summary(summary, row):
    """Append with a backward-compatible migration of the existing summary.csv.

    New GET-related columns are appended to the old header; all historical
    rows and their original values are retained (new cells remain empty).
    """
    if summary.exists():
        with summary.open(newline='', encoding='utf-8-sig') as fd:
            old_header = next(csv.reader(fd), None)
        if not old_header:
            raise RuntimeError(f'Existing summary has no CSV header: {summary}')
        missing = [field for field in SUMMARY_FIELDS if field not in old_header]
        if missing:
            combined_header = old_header + missing
            temp_name = None
            try:
                with tempfile.NamedTemporaryFile('w', encoding='utf-8', newline='',
                                                 prefix='summary-', suffix='.tmp',
                                                 dir=summary.parent, delete=False) as out:
                    temp_name = out.name
                    writer = csv.DictWriter(out, fieldnames=combined_header)
                    writer.writeheader()
                    with summary.open(newline='', encoding='utf-8-sig') as source:
                        writer.writerows(csv.DictReader(source))
                os.replace(temp_name, summary)
            finally:
                if temp_name and os.path.exists(temp_name):
                    os.unlink(temp_name)
            header = combined_header
        else:
            header = old_header
        with summary.open('a', newline='', encoding='utf-8') as fd:
            csv.DictWriter(fd, fieldnames=header, extrasaction='ignore').writerow(row)
    else:
        with summary.open('w', newline='', encoding='utf-8') as fd:
            writer = csv.DictWriter(fd, fieldnames=SUMMARY_FIELDS)
            writer.writeheader()
            writer.writerow(row)


def main():
    parser = argparse.ArgumentParser(description='Run DPP benchmarks using JMeter; no automatic retries.')
    parser.add_argument('mode', choices=['create', 'install', 'update', 'get', 'history', 'conflict-slot', 'conflict-component', 'conflict-rate', 'idempotency'])
    parser.add_argument('--threads', type=int, default=10)
    parser.add_argument('--conflict-rate', type=int, choices=[0, 5, 10, 20], default=None,
                        help='CONFLICT-RATE: excluded candidates per 100 operations (0,5,10,20)')
    parser.add_argument('--duration', type=int, default=60, help='CREATE/UPDATE/GET/HISTORY: seconds of sustained load')
    parser.add_argument('--ramp-up', type=int, default=None, help='seconds: default CREATE/UPDATE/GET/HISTORY 10, INSTALL 1')
    parser.add_argument('--base-url', default='http://localhost:8081')
    parser.add_argument('--prep-workers', type=int, default=4, help='concurrent setup calls for INSTALL/UPDATE/GET/HISTORY')
    parser.add_argument('--results', type=Path, default=DEFAULT_RESULTS)
    parser.add_argument('--get-resource', choices=['passport', 'asset'], default='passport',
                        help='GET target: passport (default) or asset')
    parser.add_argument('--history-depth', type=int, default=10,
                        help='Accepted INSTALL events per DPP for HISTORY (default 10)')
    parser.add_argument('--history-at', choices=['before', 'middle', 'end'], default='end',
                        help='Effective time for HISTORY: before, halfway or after the installs')
    parser.add_argument('--history-fixture', type=Path,
                        help='Reuse a previously created *-history-data.csv (no setup writes)')
    parser.add_argument('--history-prep-targets', type=int,
                        help='Number of read targets to precreate; at least --threads (default threads)')
    args = parser.parse_args()
    args.ramp_up = args.ramp_up if args.ramp_up is not None else (0 if args.mode in ('conflict-slot', 'conflict-component', 'conflict-rate', 'idempotency') else (1 if args.mode == 'install' else 10))
    if args.mode == 'idempotency' and args.threads != 2:
        parser.error('idempotency requires --threads 2 (two simultaneous retries)')
    if args.mode == 'conflict-rate' and args.threads != 100:
        parser.error('conflict-rate requires --threads 100: rate is exact excluded requests out of 100')
    if args.mode == 'conflict-rate' and args.conflict_rate is None:
        parser.error('conflict-rate requires --conflict-rate {0,5,10,20}')
    if args.mode != 'conflict-rate' and args.conflict_rate is not None:
        parser.error('--conflict-rate is only valid for mode conflict-rate')
    if args.mode in ('conflict-slot', 'conflict-component') and args.threads != 2:
        parser.error(f'{args.mode} requires exactly --threads 2 (one per competitor)')
    if (args.threads < 1 or args.ramp_up < 0 or args.duration < 1
            or args.prep_workers < 1 or args.history_depth < 0
            or args.history_depth >= 82800
            or (args.history_prep_targets is not None and args.history_prep_targets < args.threads)):
        parser.error('threads, duration and prep-workers must be >=1; ramp-up >= 0')
    base_url = args.base_url.rstrip('/')
    parsed = urllib.parse.urlparse(base_url)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname:
        parser.error('base-url must be an http(s) URL')

    executable = os.getenv('JMETER', 'jmeter')
    if not shutil.which(executable) and not Path(executable).is_file():
        sys.exit('JMeter not found. Install JMeter 5.6.x and set JMETER=/path/to/apache-jmeter/bin/jmeter')

    result_dir = args.results.resolve()
    result_dir.mkdir(parents=True, exist_ok=True)
    run_id = f'perf-{args.mode}-{utcnow().replace("-", "").replace(":", "")}-{uuid.uuid4().hex[:8]}'
    jtl = result_dir / f'{run_id}.jtl'
    print(f'RUN_ID={run_id}\nMODE={args.mode}\nTHREADS={args.threads}\nJTL={jtl}', flush=True)
    print('EXPECTED_ORDERER_CONFIG=BatchTimeout 500ms, MaxMessageCount 50 (verify actual channel config externally)', flush=True)
    print('RETRIES=0 (both requests and Fabric submit); data preparation is outside JMeter measurement', flush=True)
    props = [
        f'-Jhost={parsed.hostname}',
        f'-Jport={parsed.port or (443 if parsed.scheme == "https" else 80)}',
        f'-Jprotocol={parsed.scheme}',
        f'-Jthreads={args.threads}',
        f'-JrampUp={args.ramp_up}',
        f'-JrunId={run_id}',
        '-Jsample_variables=fabricStatus,semanticOutcome,validationCode,readStatus,historyStatus,conflictCase,eventId,groupId,slotId,contended,fabricError,fabricStage,fabricMessage,idempotencyCase',
        '-Jjmeter.save.saveservice.output_format=csv',
        '-Jjmeter.save.saveservice.timestamp_format=ms',
    ]
    duration_s = args.duration if args.mode in ('create', 'update', 'get', 'history') else 0
    if args.mode in ('create', 'update', 'get', 'history'):
        if args.mode == 'update':
            data_file = prepare_update(base_url, result_dir, run_id, args.threads, args.prep_workers)
            print(f'DATA_FILE={data_file}', flush=True)
            print('UPDATE_TARGET=one distinct DPP per client; changes schemaVersion, immutable rootAssetId', flush=True)
            print('UPDATE_HTTP_SUCCESS=200 (legacy summary column http_201 stays 0 for UPDATE)', flush=True)
        if args.mode == 'get':
            data_file = prepare_get(base_url, result_dir, run_id, args.threads,
                                    args.prep_workers, args.get_resource)
            print(f'DATA_FILE={data_file}', flush=True)
            print(f'GET_TARGET={args.get_resource.upper()} by ID; Fabric evaluateTransaction; no commit', flush=True)
            prefix = f'{run_id}-get-' + ('dpp-' if args.get_resource == 'passport' else 'asset-')
            endpoint = '/api/passports' if args.get_resource == 'passport' else '/api/assets'
            doc_type = 'DIGITAL_PRODUCT_PASSPORT' if args.get_resource == 'passport' else 'ASSET'
            props += [f'-JgetEndpoint={endpoint}', f'-JgetIdPrefix={prefix}',
                      f'-JgetDocType={doc_type}']
        if args.mode == 'history':
            if args.history_fixture:
                data_file = args.history_fixture.resolve()
                print(f'SETUP=REUSE {data_file}; no new writes', flush=True)
            else:
                targets = args.history_prep_targets or args.threads
                data_file = prepare_history(base_url, result_dir, run_id, targets,
                                            args.prep_workers, args.history_depth)
            rows, prefix, args.history_depth = read_history_fixture(data_file, args.threads)
            active_count = {'before': 0, 'middle': max(1, args.history_depth // 2)
                            if args.history_depth else 0,
                            'end': args.history_depth}[args.history_at]
            query_time = rows[0][args.history_at + 'Time']
            verify_history_setup(base_url, rows[0]['dppId'], query_time, active_count)
            props += [f'-JhistoryDppPrefix={prefix}',
                      f'-JhistoryHostPrefix={prefix.replace("-history-dpp-", "-history-host-")}',
                      f'-JhistoryTime={query_time}', '-JhistoryAsOf=0',
                      f'-JhistoryExpectedActive={active_count}',
                      f'-JhistoryExpectedIntervals={active_count}']
            print(f'DATA_FILE={data_file}\nHISTORY_DEPTH={args.history_depth}'
                  f'\nHISTORY_AT={args.history_at}\nHISTORY_TARGETS={len(rows)}'
                  f'\nHISTORY_EXPECTED_INTERVALS={active_count}', flush=True)
            # Record the actually used fixture, including on a new setup.
            args.history_fixture = data_file
        print(f'WORKLOAD=sustained {args.mode.upper()} for {args.duration}s with {args.ramp_up}s ramp-up', flush=True)
        props += [f'-Jduration={args.duration}', '-Jloops=-1']
    elif args.mode == 'idempotency':
        import idempotency_support
        effective_at = utcnow()
        data_file, idempotency_rows = idempotency_support.prepare(
            base_url, result_dir, run_id, post_json)
        print(f'DATA_FILE={data_file}\nWORKLOAD=3 sequential posts + 2 synchronized exact duplicates; one logical effect per group',flush=True)
        print('TIMING=5 functional requests, NOT a sustained throughput run',flush=True)
        props += [f'-JeffectiveAt={effective_at}', f'-JissuedAt={effective_at}']
        for case in idempotency_rows:
            prefix = case['caseId']
            for key in ('hostId','dppId','actorId','componentId','eventId',
                        'operationId','slotId','mutationEventId','mutationSlotId'):
                props.append(f'-J{prefix}{key[0].upper()+key[1:]}={case[key]}')
    elif args.mode == 'conflict-rate':
        effective_at = utcnow()
        host, dpp, data_file, contenders, n_pairs = prepare_conflict_rate(
            base_url, result_dir, run_id, args.threads, args.prep_workers, args.conflict_rate)
        print(f'DATA_FILE={data_file}\nHOST_ID={host}\nDPP_ID={dpp}\n'
              f'PLANNED_CONFLICT_RATE_PCT={args.conflict_rate}\n'
              f'PLANNED_PAIRS={n_pairs}\n'
              f'EXPECTED_ACCEPTED={args.threads-n_pairs}', flush=True)
        print('WORKLOAD=single synchronized burst of 100 INSTALLs; '
              'independent groups and same-slot pairs; no retry', flush=True)
        props += [f'-JdataFile={data_file}', f'-JeffectiveAt={effective_at}',
                  f'-JissuedAt={effective_at}']
    elif args.mode == 'conflict-component':
        effective_at = utcnow()
        component, data_file, contenders = prepare_conflict_component(
            base_url, result_dir, run_id, args.prep_workers)
        print(f'DATA_FILE={data_file}\nSHARED_COMPONENT={component}', flush=True)
        print('WORKLOAD=two synchronized INSTALLs, same component, two host/DPP pairs; no retries', flush=True)
        props += [f'-JdataFile={data_file}',
                  f'-JeffectiveAt={effective_at}', f'-JissuedAt={effective_at}']
    elif args.mode == 'conflict-slot':
        effective_at = utcnow()
        host, dpp, slot, data_file, contenders = prepare_conflict_slot(
            base_url, result_dir, run_id, args.prep_workers)
        print(f'DATA_FILE={data_file}\nHOST_ID={host}\nDPP_ID={dpp}\nSHARED_SLOT={slot}', flush=True)
        print('WORKLOAD=two synchronized competing INSTALLs, one burst, no retry', flush=True)
        props += [f'-JhostId={host}', f'-JdppId={dpp}', f'-JdataFile={data_file}',
                  f'-JeffectiveAt={effective_at}', f'-JissuedAt={effective_at}']
    else:
        effective_at = utcnow()
        host, dpp, data_file = prepare_install(base_url, result_dir, run_id, args.threads, args.prep_workers)
        print(f'DATA_FILE={data_file}\nHOST_ID={host}\nDPP_ID={dpp}', flush=True)
        print('WORKLOAD=single INSTALL per thread, synchronized burst (NOT sustained 60s)', flush=True)
        props += [
            f'-JhostId={host}', f'-JdppId={dpp}',
            f'-JdataFile={data_file}',
            f'-JeffectiveAt={effective_at}', f'-JissuedAt={effective_at}',
        ]

    command = [executable, '-n', '-t', str(TEST_DIR / f'{args.mode}.jmx'), '-l', str(jtl)] + props
    print('START_JMETER=' + utcnow(), flush=True)
    status = subprocess.run(command, check=False).returncode
    print('END_JMETER=' + utcnow(), flush=True)
    if status != 0:
        sys.exit(f'JMeter returned exit code {status}; inspect {jtl} and jmeter.log')

    row = summarize(jtl, run_id, args, duration_s)
    if args.mode in ('install', 'conflict-slot', 'conflict-component', 'conflict-rate') and row['samples'] != args.threads:
        sys.exit(f'Expected {args.threads} INSTALL samples; got {row["samples"]}. See JTL.')
    if args.mode == 'idempotency':
        import idempotency_support
        row.update(idempotency_support.verify(
            base_url, jtl, idempotency_rows, effective_at, result_dir, run_id, get_json))
        row['conflict_fixture'] = str(data_file)
    if args.mode == 'conflict-rate':
        row.update(verify_conflict_rate(base_url, jtl, host, dpp, contenders,
                                        args.conflict_rate, effective_at, result_dir, run_id))
        row['conflict_fixture'] = str(data_file)
    if args.mode == 'conflict-component':
        row.update(verify_conflict_component(base_url, jtl, component, contenders, effective_at))
        row['conflict_fixture'] = str(data_file)
    if args.mode == 'conflict-slot':
        row.update(verify_conflict_slot(base_url, jtl, host, dpp, slot, contenders, effective_at))
        row['conflict_fixture'] = str(data_file)
    summary = result_dir / 'summary.csv'
    append_summary(summary, row)
    if args.mode in ('conflict-slot', 'conflict-component', 'conflict-rate', 'idempotency'):
        print('RESULT=' + row['conflict_result'])
        if args.mode == 'idempotency':
            print('IDEMPOTENCY_REPORT=' + row['idempotency_report'])
        elif args.mode == 'conflict-rate':
            print('PLANNED_PAIRS=' + str(row['planned_conflict_pairs']) +
                  ' OBSERVED_EXCLUSIONS=' + str(row['observed_conflict_exclusions']) +
                  ' ACCEPTED=' + str(row['semantic_accepted']))
        else:
            print('CONFLICT_WINNER=' + row['conflict_winner'])
            print('CONFLICT_COMPETITOR=' + row['conflict_competitor'])
    else:
        print('RESULT=' + ('PASS' if row['other_failures'] == 0 and row['mvcc_conflicts'] == 0
                             and row['phantom_conflicts'] == 0 else 'HAS_ERRORS'))
    print('SUMMARY=' + str(summary))
    print('METRICS=' + json.dumps({k: row[k] for k in
          ('samples', 'http_200', 'read_ok', 'history_ok', 'fabric_valid',
           'semantic_accepted', 'semantic_rejected',
           'mvcc_conflicts', 'phantom_conflicts', 'other_failures',
           'mean_ms', 'p95_ms', 'observed_req_per_s')}))


if __name__ == '__main__':
    main()
