#!/usr/bin/env python3
"""Read-only verification of application metadata and background occupied-space statistics."""
import argparse
import asyncio
import json
from pathlib import Path
import time

import websockets


async def verify(url, output, require_incremental=False):
    async with websockets.connect(url, proxy=None) as socket:
        sequence = 0

        async def request(tool, arguments=None, ok=True):
            nonlocal sequence
            sequence += 1
            await socket.send(json.dumps(dict(id=str(sequence), protocol=1, type='tool', tool=tool, arguments=arguments or {})))
            response = json.loads(await asyncio.wait_for(socket.recv(), 40))
            assert response['id'] == str(sequence) and response['ok'] == ok, response
            return json.loads(response['result']) if ok else response

        async def progress(task_id):
            value = await request('task.progress', dict(id=task_id))
            assert value['id'] == task_id and value['root'] == 'apps' and value['direction'] == 'app_sizes' and value['unit'] == 'items', value
            return value

        base = await request('apps.list')
        assert base['source'] == 'package_manager' and base['apps'], base
        assert any(a.get('firstInstallTime', 0) for a in base['apps']), base
        await request('apps.list', dict(includeSizes='invalid'), ok=False)
        start = time.monotonic()
        initial = await request('apps.list', dict(includeSizes=True))
        assert time.monotonic() - start < 25, 'Storage scan blocked the initial application list'
        task = initial['sizeTask']
        task_id = task['id']
        first_partial = None
        last_processed = 0

        def observe_partial(value):
            nonlocal first_partial, last_processed
            partial = value.get('result')
            if value['phase'] == 'scanning' and partial is not None:
                processed = partial['processed']
                assert partial['partial'] and processed == len(partial['apps']) and processed >= last_processed, partial
                last_processed = processed
                if processed and first_partial is None:
                    first_partial = time.monotonic() - start
                    print(f'PASS incremental result before completion: {processed} apps after {first_partial:.1f}s', flush=True)

        # Observe the first value before unrelated reads can delay its measurement.
        early_deadline = time.monotonic() + 25
        while time.monotonic() < early_deadline:
            task = await progress(task_id)
            observe_partial(task)
            if first_partial is not None or task['phase'] in ('completed', 'failed', 'cancelled'):
                break
            await asyncio.sleep(.1)
        repeated = await request('apps.list', dict(includeSizes=True))
        if repeated['sizeTask']['id'] != task_id:
            assert (await progress(task_id))['phase'] == 'completed', 'Duplicated an active storage scan'
        plain_start = time.monotonic()
        plain = await request('apps.list')
        assert 'sizeTask' not in plain and time.monotonic() - plain_start < 25, 'Normal list blocked behind storage scan'
        print('PASS installation metadata; size requests return a background task; ordinary list stays available; active work is shared', flush=True)
        deadline = time.monotonic() + 340
        while time.monotonic() < deadline:
            task = await progress(task_id)
            observe_partial(task)
            if task['phase'] in ('completed', 'failed', 'cancelled'):
                break
            await asyncio.sleep(.3)
        assert task['phase'] == 'completed', task
        assert not require_incremental or first_partial is not None, 'No incremental result was observed before completion'
        snapshot = task['result']
        assert snapshot['sizesRequested'] and snapshot['userId'] == base['userId'], snapshot
        known = [a for a in snapshot['apps'] if a.get('sizeBytes') is not None]
        assert known and all(isinstance(a['sizeBytes'], int) and a['sizeBytes'] >= 0 for a in known), snapshot
        assert any(a['sizeBytes'] > 0 for a in known), snapshot
        if output:
            output.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2))
        print(f"PASS system occupied-space snapshot: {len(known)}/{len(snapshot['apps'])} known; {time.monotonic()-start:.1f}s; no applications modified", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True, help='Explicit ws://host:port/ws runtime')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--require-incremental', action='store_true', help='Require a partial snapshot before completion on a slower target')
    args = parser.parse_args()
    asyncio.run(verify(args.url, args.output, args.require_incremental))


if __name__ == '__main__':
    main()
