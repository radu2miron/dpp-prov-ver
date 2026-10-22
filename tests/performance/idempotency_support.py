"""Fixture creation and strict ledger post-verification for idempotency.jmx.

Python standard library only. The workload itself is driven by Apache JMeter.
"""
import csv
import json
from pathlib import Path
import urllib.parse

CASES = ('seq-first', 'seq-replay', 'seq-mutated', 'race', 'race')


def prepare(base_url, results, run_id, post_json):
    rows = []
    for name in ('seq', 'race'):
        prefix = f'{run_id}-{name}'
        host, dpp = prefix+'-host', prefix+'-dpp'
        actor, motor = prefix+'-actor', prefix+'-motor'
        event = prefix+'-install'
        post_json(base_url, '/api/assets', {'docType':'ASSET','id':host,'assetType':'MACHINE'})
        post_json(base_url, '/api/passports', {'docType':'DIGITAL_PRODUCT_PASSPORT',
                                             'id':dpp, 'rootAssetId':host,'schemaVersion':'1.0'})
        post_json(base_url, '/api/actors', {'docType':'ACTOR','id':actor,
                    'organizationId':'Org1MSP' if name=='seq' else 'Org2MSP',
                    'role':'MANUFACTURER'})
        post_json(base_url, '/api/assets', {'docType':'ASSET','id':motor,'assetType':'MOTOR'})
        rows.append({'caseId':name,'hostId':host,'dppId':dpp,'actorId':actor,
                     'componentId':motor,'eventId':event, 'operationId':'op-'+event,
                     'slotId':f'drive-{name}',
                     'mutationEventId':prefix+'-changed-payload',
                     'mutationSlotId':f'drive-{name}-different'})
    out = results / f'{run_id}-idempotency-data.csv'
    with out.open('w',newline='',encoding='utf-8') as f:
        writer=csv.DictWriter(f,fieldnames=list(rows[0])); writer.writeheader();writer.writerows(rows)
    return out, rows


def _decision_for(decisions, event_id):
    return [x for x in decisions if isinstance(x,dict) and x.get('eventId')==event_id]


def verify(base_url, jtl, rows, effective_at, results, run_id, get_json):
    problems=[]; unknowns=[]
    with jtl.open(newline='',encoding='utf-8-sig') as f:
        reader=csv.DictReader(f)
        must={'idempotencyCase','responseCode','eventId','fabricStatus','semanticOutcome',
              'validationCode','fabricError','fabricStage','fabricMessage','elapsed'}
        missing=must-set(reader.fieldnames or [])
        if missing: raise RuntimeError(f'IDEMPOTENCY: missing JTL columns {missing}')
        samples=list(reader)
    phases={p:[s for s in samples if s['idempotencyCase']==p] for p in
            ('seq-first','seq-replay','seq-mutated','race')}
    if len(samples)!=5 or [len(phases[p]) for p in phases] != [1,1,1,2]:
        problems.append('Expected exactly five samples: 1 seq-first, 1 seq-replay, 1 seq-mutated and 2 race')
    byname={r['caseId']:r for r in rows}
    seq=byname['seq']; race=byname['race']
    for phase, target in (('seq-first',seq),('seq-replay',seq),('seq-mutated',seq),('race',race)):
        for s in phases[phase]:
            expected_id=target['mutationEventId'] if phase=='seq-mutated' else target['eventId']
            if s['eventId']!=expected_id:
                problems.append(f'{phase}: JTL eventId mismatch: {s["eventId"]}')

    def is_accepted(s, expected):
        return (s['responseCode']=='201' and s['fabricStatus']=='VALID'
                and s['semanticOutcome']=='ACCEPTED' and s['eventId']==expected)

    if len(phases['seq-first'])==1 and not is_accepted(phases['seq-first'][0],seq['eventId']):
        problems.append('Sequential initial POST did not commit ACCEPTED')
    if len(phases['seq-replay'])==1 and not is_accepted(phases['seq-replay'][0],seq['eventId']):
        problems.append('Exact sequential replay did not return committed ACCEPTED')
    if len(phases['seq-mutated'])==1:
        s=phases['seq-mutated'][0]
        message=(s.get('fabricMessage') or '') + ' ' + (s.get('responseMessage') or '')
        if s['responseCode']=='422' and s['fabricError']=='FABRIC_ENDORSE':
            if 'operationId is already associated with another request' not in message:
                unknowns.append('Mutated payload blocked at ENDORSE, but chaincode reason unavailable')
        elif (s['responseCode']=='400' and 'operationId is already associated with another request' in message):
            pass
        else:
            problems.append('Mutated request not rejected as expected: HTTP '+s['responseCode'])
    if len(phases['race'])==2:
        concurrent=phases['race']
        if not any(is_accepted(s,race['eventId']) for s in concurrent):
            problems.append('No concurrent request returned committed ACCEPTED')
        for s in concurrent:
            if is_accepted(s,race['eventId']): continue
            if s['responseCode']=='409' and s['validationCode']=='MVCC_READ_CONFLICT': continue
            if s['responseCode']=='422' and s['fabricError']=='FABRIC_ENDORSE':
                unknowns.append('Concurrent duplicate had unclassified ENDORSE failure (HTTP 422)')
            else:
                problems.append('Unexpected concurrent retry response: HTTP '+s['responseCode']+
                                ', validation='+s.get('validationCode',''))

    # Authoritative post-check: new accepted events and decisions MUST be unique.
    # Full collection evaluates are run after the JMeter workload, outside timing.
    ledger={}
    try:
        events=get_json(base_url,'/api/events')
        decisions=get_json(base_url,'/api/decisions')
        if not isinstance(events,list) or not isinstance(decisions,list):
            raise ValueError('Expected event and decision lists')
        tracked=[seq['eventId'],race['eventId'],seq['mutationEventId']]
        ledger['events']={eid:sum(isinstance(x,dict) and x.get('id')==eid for x in events)
                          for eid in tracked}
        ledger['decisions']={eid:len(_decision_for(decisions,eid)) for eid in tracked}
        for entry in (seq,race):
            eid=entry['eventId']
            if ledger['events'][eid]!=1 or ledger['decisions'][eid]!=1:
                problems.append(f'Expected exactly ONE persisted event/decision for {eid}')
            matched=_decision_for(decisions,eid)
            if len(matched)==1 and (matched[0].get('outcome')!='ACCEPTED' or
                                   matched[0].get('id')!='decision:'+eid):
                problems.append(f'Committed decision for {eid} is not ACCEPTED')
        if ledger['events'][seq['mutationEventId']] or ledger['decisions'][seq['mutationEventId']]:
            problems.append('Mutated request produced a persisted event or decision')
        for entry in (seq,race):
            dpp=entry['dppId']
            endpoint=('/api/passports/'+urllib.parse.quote(dpp,safe='')+'/state?'+
                    urllib.parse.urlencode({'effectiveTime':effective_at,'asOfPosition':0}))
            state=get_json(base_url,endpoint)
            active=state.get('activeIntervals') if isinstance(state,dict) else None
            history=state.get('intervals') if isinstance(state,dict) else None
            ledger[entry['caseId']+'_state']={'dppId':dpp,
                'active_count':len(active) if isinstance(active,list) else None,
                'history_count':len(history) if isinstance(history,list) else None}
            expected={'id':entry['eventId'],'hostId':entry['hostId'],
                      'componentId':entry['componentId'],'slotId':entry['slotId']}
            if (not isinstance(state,dict) or state.get('dppId')!=dpp or
                not isinstance(active,list) or len(active)!=1 or
                not isinstance(history,list) or len(history)!=1 or
                any(active[0].get(k)!=v for k,v in expected.items()) or
                active[0].get('validTo') is not None or
                history[0].get('id')!=entry['eventId']):
                problems.append('State reconstruction did not show exactly one unique interval for '+dpp)
    except Exception as exc:
        problems.append('Ledger postcheck unavailable or failed: '+str(exc))

    status=('FAIL' if problems else
            'CONSISTENCY_PASS_CAUSE_UNVERIFIED' if unknowns else 'PASS')
    report={
        'run_id':run_id,'mode':'idempotency','status':status,
        'scenario':'sequential first + exact replay + same operationId modified payload; concurrent exact duplicate',
        'requests':len(samples),
        'outcomes':{
            phase:[{'http':s['responseCode'],'eventId':s['eventId'],
                    'fabricStatus':s.get('fabricStatus',''),
                    'semanticOutcome':s.get('semanticOutcome',''),
                    'validationCode':s.get('validationCode',''),
                    'fabricError':s.get('fabricError',''),
                    'fabricStage':s.get('fabricStage',''),
                    'fabricMessage':s.get('fabricMessage',''),
                    'latencyMs':int(s['elapsed'])} for s in phase_rows]
            for phase,phase_rows in phases.items()},
        'ledger':ledger,'problems':problems,'unknown_endorsement_causes':unknowns,
        'timing_note':'Five functional requests in sequential and synchronized groups, not a throughput benchmark',
    }
    report_path=results/f'{run_id}-idempotency-report.json'
    report_path.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print('IDEMPOTENCY_POSTCHECK='+status+'\nREPORT='+str(report_path),flush=True)
    if problems:
        raise RuntimeError('IDEMPOTENCY failed: '+ '; '.join(problems)+'; see '+str(report_path))
    return {'idempotency_result':status,'idempotency_report':str(report_path),
            'conflict_result':status,'conflict_state_ok':1}
