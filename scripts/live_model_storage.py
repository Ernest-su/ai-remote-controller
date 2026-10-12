"""Read-only real-provider storage task verification against owned samples."""
import json
import uuid


async def check_storage(request, root, fixture, fixture_digest):
    expected_size = int(root(f'wc -c < {fixture}').strip())
    answer, events = await request(
        'model-storage', type='text', progress=True,
        arguments={'allowedTools': ['storage.analyze', 'task.progress']},
        text=('请调用 storage_analyze，root=inbox、path=空字符串、maxEntries=100。'
              '用返回的真实 id 调用 task_progress 直到 completed，再读取 result。'
              '两个工具必须实际执行。禁止修改文件。最后只输出 JSON 对象，'
              '字段为 id、phase、files、logicalBytes、complete，后三项来自扫描报告。'),
    )
    tools = [event.get('tool') for event in events if event.get('phase') == 'tool']
    assert tools and set(tools) == {'storage.analyze', 'task.progress'}
    payload = answer.strip()
    if payload.startswith('```json\n') and payload.endswith('```'):
        payload = payload[8:-3].strip()
    elif payload.startswith('```\n') and payload.endswith('```'):
        payload = payload[4:-3].strip()
    reported = json.loads(payload)
    identifier = str(uuid.UUID(reported['id']))
    assert reported['id'] == identifier and reported['phase'] == 'completed'
    actual, _ = await request('verify-storage', type='tool', tool='task.progress', arguments={'id': identifier})
    actual = json.loads(actual)
    assert actual['id'] == identifier and actual['direction'] == 'storage_scan'
    assert actual['root'] == 'inbox' and actual['name'] == ''
    assert actual['phase'] == 'completed' and actual['error'] is None
    report = actual['result']
    assert report['root'] == 'inbox' and report['path'] == ''
    assert report['complete'] is True and report['truncated'] is False
    assert report['totals']['files'] == 1 and report['totals']['logicalBytes'] == expected_size
    assert reported['complete'] is True and reported['files'] == 1 and reported['logicalBytes'] == expected_size
    assert len(report['largestFiles']) == 1
    assert report['largestFiles'][0]['path'] == 'approval-fixture.bin'
    assert root(f'sha256sum {fixture}').split()[0] == fixture_digest
    print('PASS: real provider started/polled storage scan; completed report matches owned file count/bytes and source SHA-256 is unchanged')
