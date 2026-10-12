"""Real-provider download task acceptance, using only an owned target fixture."""
import asyncio
import hashlib
import json
import uuid


async def check_download(request, root, fixture, fixture_digest, opener, base_url):
    expected_size = int(root(f'wc -c < {fixture}').strip())
    answer, events = await request(
        'model-download', type='text', progress=True,
        arguments={'allowedTools': ['task.start', 'task.progress']},
        text=('请调用 task_start，direction=download、root=inbox、'
              'path=approval-fixture.bin。用返回的真实 id 调用 task_progress，'
              '直到 phase=ready。必须实际调用两个工具。不要读取其他文件或修改源文件。'
              '最后只输出 JSON 对象，包含 id、phase、sizeBytes、sha256，'
              '字段取自这个任务的最终进度，不得猜测。'),
    )
    tools = [event.get('tool') for event in events if event.get('phase') == 'tool']
    assert tools and set(tools) == {'task.start', 'task.progress'}, 'Expected both scoped task tools'
    payload = answer.strip()
    if payload.startswith('```json\n') and payload.endswith('```'):
        payload = payload[8:-3].strip()
    elif payload.startswith('```\n') and payload.endswith('```'):
        payload = payload[4:-3].strip()
    reported = json.loads(payload)
    identifier = str(uuid.UUID(reported['id']))
    assert identifier == reported['id'], 'Expected canonical target task ID'
    assert reported['phase'] == 'ready'
    assert reported['sizeBytes'] == expected_size and reported['sha256'] == fixture_digest
    actual, _ = await request('verify-model-task', type='tool', tool='task.progress', arguments={'id': identifier})
    actual = json.loads(actual)
    assert actual['id'] == identifier and actual['direction'] == 'download'
    assert actual['root'] == 'inbox' and actual['name'] == 'approval-fixture.bin'
    assert actual['phase'] == 'ready' and actual['sizeBytes'] == expected_size
    assert actual['sha256'] == fixture_digest and actual['error'] is None
    assert actual['dataPath'] == f'/transfers/{identifier}/data'

    def download():
        digest = hashlib.sha256()
        size = 0
        with opener.open(base_url + actual['dataPath'], timeout=30) as response:
            while chunk := response.read(64 * 1024):
                digest.update(chunk)
                size += len(chunk)
                assert size <= expected_size, 'Download exceeded declared size'
        return size, digest.hexdigest()

    assert await asyncio.to_thread(download) == (expected_size, fixture_digest)
    assert root(f'sha256sum {fixture}').split()[0] == fixture_digest
    print('PASS: real provider started/polled download task; ID, ready phase, size and SHA-256 match direct progress and HTTP bytes; source unchanged')
