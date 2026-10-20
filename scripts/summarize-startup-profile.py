"""Publish only allowlisted timing/counter data from private startup diagnostics."""
import argparse
from collections import Counter
import json
from pathlib import Path
import statistics
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--input', required=True, type=Path)
p.add_argument('--jdk', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
args = p.parse_args()
raw = json.loads((args.input / 'summary.json').read_text(encoding='utf-8'))
selected = ['spring.context.refresh', 'spring.context.config-classes.parse',
            'spring.boot.webserver.create', 'flyway', 'flywayInitializer',
            'ledgerSchemaValidator', 'security', 'recoveryGate', 'dataSource']

def seconds(value):
    # jfr print emits these short recording durations as ISO-8601 PT...S values.
    if not value.startswith('PT') or not value.endswith('S'):
        raise ValueError('Unexpected duration format')
    return float(value[2:-1])

results = []
for index, run in enumerate(raw['runs'], 1):
    steps = run['events']
    exclusive = []
    for event in steps:
        intervals = sorted((max(event['start_ns'], child['start_ns']),
                            min(event['end_ns'], child['end_ns'])) for child in steps
                           if child['parent_id'] == event['id'])
        covered, end = 0, event['start_ns']
        for a, b in intervals:
            covered += max(0, b - max(a, end))
            end = max(end, b)
        exclusive.append(dict(name=event['name'], bean=event['tags'].get('beanName'),
                              exclusive_ms=round((event['duration_ns']-covered)/1e6, 3)))
    jfr = args.jdk / ('bin/jfr.exe' if (args.jdk / 'bin/jfr.exe').exists() else 'bin/jfr')
    data = subprocess.check_output([str(jfr), 'print', '--json', '--events',
        'jdk.GarbageCollection,jdk.GCPhasePause,jdk.ExecutionSample,jdk.GCHeapSummary,jdk.SocketRead',
        str(args.input / f'run-{index}/startup.jfr')])
    events = json.loads(data)['recording']['events']
    by_type = {kind: [x['values'] for x in events if x['type'] == kind] for kind in
               ['jdk.GarbageCollection', 'jdk.GCPhasePause', 'jdk.ExecutionSample',
                'jdk.GCHeapSummary', 'jdk.SocketRead']}
    methods = Counter()
    for sample in by_type['jdk.ExecutionSample']:
        frames = (sample.get('stackTrace') or {}).get('frames', [])
        if frames:
            method = frames[0]['method']
            methods[method['type']['name']+'.'+method['name']] += 1
    phase_ms = {name: round(sum(x['duration_ns'] for x in steps if x['name'] == name
                    or x['tags'].get('beanName') == name)/1e6, 3) for name in selected}
    results.append(dict(run=index, spring_run_seconds=round(run['spring_run_seconds'], 3),
        configuration=run['configuration'],
        event_count=len(steps), inclusive_phase_ms=phase_ms,
        exclusive_top_steps=sorted(exclusive, key=lambda x: x['exclusive_ms'], reverse=True)[:12],
        gc_count=len(by_type['jdk.GarbageCollection']),
        full_gc_count=sum(x['name'] == 'G1Full' for x in by_type['jdk.GarbageCollection']),
        gc_pause_ms=round(sum(seconds(x['duration']) for x in by_type['jdk.GCPhasePause'])*1000, 3),
        max_sampled_heap_used_mb=round(max(x['heapUsed'] for x in by_type['jdk.GCHeapSummary'])/1024**2, 3),
        execution_samples=len(by_type['jdk.ExecutionSample']),
        sampled_leaf_methods=methods.most_common(5),
        recorded_socket_reads=len(by_type['jdk.SocketRead']),
        recorded_socket_read_ms=round(sum(seconds(x['duration']) for x in by_type['jdk.SocketRead'])*1000, 3)))
summary = dict(scope=raw['scope'], jar_sha256=raw['jar_sha256'], jvm_flags=raw['jvm_flags'],
               method='One warmup, three measured fresh JVMs; pooled max 3/min idle 0, separate Flyway connection; local authority exemption',
               caveats=['Inclusive phases overlap: do not add them.',
                        'Exploded classpath and profiling overhead: not comparable to the previous packed-jar readiness benchmark.',
                        'Socket events are thresholded and may overlap. CPU samples are too few for stable percentages.',
                        'No production JFR, CPU quota, TLS, Neon wakeup or external signed checks are measured.'],
               median_spring_run_seconds=statistics.median(x['spring_run_seconds'] for x in results),
               median_phase_ms={name: statistics.median(x['inclusive_phase_ms'][name] for x in results) for name in selected},
               runs=results)
args.output.write_text(json.dumps(summary, indent=2), encoding='utf-8')
print(json.dumps({k: summary[k] for k in ['median_spring_run_seconds', 'median_phase_ms']}))
print(json.dumps([{k: x[k] for k in ['run','gc_count','full_gc_count','gc_pause_ms',
                                   'max_sampled_heap_used_mb','execution_samples','recorded_socket_read_ms']}
                  for x in results]))
