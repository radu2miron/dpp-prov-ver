#!/usr/bin/env python3
"""Non-destructive diagnostic for an existing CONFLICT-RATE run (no retries/writes).

Use after run-performance.py aborts, to inspect group outcomes and, optionally,
verify accepted state and decisions directly from the API. Requires Python stdlib.
This diagnostic NEVER converts an unexpected response into a passing test.
"""
import argparse
import collections
import csv
import datetime as dt
import json
from pathlib import Path
import urllib.parse
import urllib.request


def load_csv(path):
    with path.open(newline='', encoding='utf-8-sig') as handle:
        return list(csv.DictReader(handle))


def api_get(base, path):
    with urllib.request.urlopen(base.rstrip('/') + path, timeout=120) as response:
        return json.load(response)


def diagnose(jtl, fixture, base_url=None):
    samples = load_csv(jtl)
    rows = load_csv(fixture)
    if not samples or not rows:
        raise ValueError('Empty fixture or JTL')
    by_id = {r['eventId']: r for r in rows}
    samples_by_id = {r['eventId']: r for r in samples}
    if len(by_id) != len(rows) or len(samples_by_id) != len(samples):
        raise ValueError('Duplicate fixture/JTL event IDs')
    missing = sorted(set(by_id) - set(samples_by_id))
    extra = sorted(set(samples_by_id) - set(by_id))
    groups = collections.defaultdict(list)
    for fixture_row in rows:
        groups[fixture_row['groupId']].append(fixture_row)

    counts = collections.Counter(r.get('conflictCase') or 'UNCLASSIFIED' for r in samples)
    http_counts = collections.Counter(r.get('responseCode') or 'UNKNOWN' for r in samples)
    issues = []
    unexpected = []
    for r in samples:
        if r.get('conflictCase') in (None, '', 'UNEXPECTED') or r.get('success','').lower() != 'true':
            f = by_id.get(r['eventId'], {})
            unexpected.append({
                'eventId': r.get('eventId'),
                'groupId': f.get('groupId') or r.get('groupId'),
                'slotId': f.get('slotId') or r.get('slotId'),
                'contended': f.get('contended') or r.get('contended'),
                'http': r.get('responseCode'),
                'fabricStatus': r.get('fabricStatus'),
                'validationCode': r.get('validationCode'),
                'fabricError': r.get('fabricError'),
                'fabricStage': r.get('fabricStage'),
                'fabricMessage': r.get('fabricMessage'),
                'responseMessage': r.get('responseMessage'),
            })
    for g, members in sorted(groups.items()):
        outcomes = [samples_by_id.get(m['eventId'], {}).get('conflictCase', 'MISSING')
                    for m in members]
        accepted = outcomes.count('ACCEPTED')
        if len(members) == 2:
            if not(accepted == 1 and sorted(outcomes) in (
                    ['ACCEPTED','MVCC_READ_CONFLICT'], ['ACCEPTED','SEMANTIC_REJECTED'])):
                issues.append({'group':g,'contested':True,'outcomes':outcomes,
                               'events':[x['eventId'] for x in members]})
        elif len(members) == 1:
            if outcomes != ['ACCEPTED']:
                issues.append({'group':g,'contested':False,'outcomes':outcomes,
                               'events':[x['eventId'] for x in members]})
        else:
            issues.append({'group':g,'invalid_fixture_size':len(members)})
    report = {
        'jtl': str(jtl), 'fixture':str(fixture),
        'samples':len(samples), 'fixture_rows':len(rows),
        'http_status_counts':dict(http_counts),
        'classified_outcomes':dict(counts),
        'missing_event_ids':missing, 'unexpected_event_ids':extra,
        'unexpected_http_or_classifications':unexpected,
        'group_mismatches':issues,
    }
    if base_url:
        run_dpp = {r['dppId'] for r in rows}
        if len(run_dpp) != 1:
            raise ValueError('Expected one DPP in fixture')
        dpp = next(iter(run_dpp))
        effective = dt.datetime.now(dt.timezone.utc).isoformat().replace('+00:00','Z')
        endpoint = ('/api/passports/' + urllib.parse.quote(dpp, safe='') + '/state?' +
                    urllib.parse.urlencode({'effectiveTime':effective,'asOfPosition':0}))
        try:
            state = api_get(base_url, endpoint)
            active = state.get('activeIntervals', [])
            slots = [x.get('slotId') for x in active]
            comps = [x.get('componentId') for x in active]
            event_ids = [x.get('id') for x in active]
            accepted_event_ids = {r['eventId'] for r in samples if r.get('conflictCase')=='ACCEPTED'}
            state_ok = (state.get('dppId') == dpp and len(slots)==len(set(slots))
                        and len(comps)==len(set(comps)) and set(event_ids)==accepted_event_ids)
            report['ledger_state'] = {'active_intervals':len(active),
                'unique_slots':len(set(slots)), 'unique_components':len(set(comps)),
                'matches_jtl_accepted_ids':set(event_ids)==accepted_event_ids,
                'structurally_consistent_with_reported_accepts':state_ok}
        except Exception as e:
            report['ledger_state_error'] = f'{type(e).__name__}: {e}'
        try:
            decisions = api_get(base_url,'/api/decisions')
            tracked = {r['eventId'] for r in rows}
            d = {x.get('eventId'):x.get('outcome') for x in decisions if x.get('eventId') in tracked}
            report['ledger_decisions'] = {'accepted':sum(v=='ACCEPTED' for v in d.values()),
              'rejected':sum(v=='REJECTED' for v in d.values()), 'total':len(d),
              'no_decision_for_unexpected': {u['eventId']:u['eventId'] not in d for u in unexpected}}
        except Exception as e:
            report['ledger_decisions_error'] = f'{type(e).__name__}: {e}'
    report['status'] = ('UNVERIFIED_FAILURE' if unexpected or issues or missing or extra
                        else 'CLASSIFICATIONS_OK_LEDGER_UNVERIFIED')
    # A clean classification is not a PASS until the original strict verifier passes.
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jtl',type=Path,required=True)
    parser.add_argument('--fixture',type=Path,required=True)
    parser.add_argument('--base-url',help='Optional: read-only API checks, no writes')
    parser.add_argument('--output',type=Path,help='Optional output JSON path')
    args=parser.parse_args()
    report=diagnose(args.jtl,args.fixture,args.base_url)
    print(json.dumps(report,indent=2,ensure_ascii=False))
    if args.output:
        args.output.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    return 1 if report['status']=='UNVERIFIED_FAILURE' else 0

if __name__=='__main__':
    raise SystemExit(main())
