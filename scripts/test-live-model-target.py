#!/usr/bin/env python3
"""Read-only real provider check using a target-local private config copy and owned service."""
import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import urllib.request
import uuid
import zipfile
import websockets
from live_model_transfer import check_download
from live_model_storage import check_storage

ROOT=Path(__file__).resolve().parent.parent

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target',required=True)
    parser.add_argument('--host',required=True)
    parser.add_argument('--with-apps',action='store_true',help='Also require actual apps.list execution and compare the first sorted package')
    parser.add_argument('--with-file-proposal',action='store_true',help='Require a model deletion proposal for an owned fixture, reject it and verify unchanged bytes')
    parser.add_argument('--with-file-read',action='store_true',help='Require actual files.list/files.stat calls for an owned fixture and compare independent metadata')
    parser.add_argument('--with-download-task',action='store_true',help='Require model task.start/task.progress, then verify the owned download through HTTP')
    parser.add_argument('--with-storage-task',action='store_true',help='Require model storage.analyze/task.progress and verify owned scan totals')
    args=parser.parse_args()
    def adb(*command):
        return subprocess.check_output(['adb','-s',args.target,*command],text=True,timeout=60)
    def root(command):return adb('shell',f'su 0 sh -c {shlex.quote(command)}')
    assert adb('get-state').strip()=='device'
    assert root('id -u').strip()=='0'
    main_binary='/data/local/tmp/airc/airc-runtime'
    main_state='/data/local/tmp/airc/state'
    before=json.loads(adb('shell',f'{main_binary} service status --state-dir {main_state}'))
    abi=adb('shell','getprop ro.product.cpu.abi').strip()
    assert abi in ('armeabi-v7a','arm64-v8a','x86_64')
    asset=ROOT/'androidApp/src/main/assets/runtime'/f'airc-runtime-{abi}'
    apk=ROOT/'androidApp/build/outputs/apk/debug/androidApp-debug.apk'
    with zipfile.ZipFile(apk) as archive:
        assert hashlib.sha256(asset.read_bytes()).digest()==hashlib.sha256(archive.read(f'assets/runtime/airc-runtime-{abi}')).digest()
    directory=f'/data/local/tmp/airc-live-model-{uuid.uuid4()}'
    binary=f'{directory}/airc-runtime'
    created=copied=False
    try:
        adb('shell',f'mkdir -m 700 {directory}');created=True
        adb('push',str(asset),binary);copied=True
        root(f'chmod 700 {binary}')
        source=f'{main_state}/model.json'
        root(f'test -f {source} && test ! -L {source} && mkdir -m 700 {directory}/state && cp -- {source} {directory}/state/model.json && chmod 600 {directory}/state/model.json')
        fixture=f'{directory}/state/files/approval-fixture.bin'
        if args.with_file_proposal or args.with_file_read or args.with_download_task or args.with_storage_task:
            root(f'mkdir -m 700 {directory}/state/files && printf %s airc-owned-approval-fixture > {fixture}')
            fixture_digest=root(f'sha256sum {fixture}').split()[0]
        service=json.loads(root(f'{binary} service start --state-dir {directory}/state --port 0 --no-mdns'))
        assert service.get('ready') and service.get('running')
        port=service['port']
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        status=json.load(opener.open(f'http://{args.host}:{port}/status',timeout=10))
        assert status.get('textToolScope') is True and status.get('modelConfigured') is True
        async def check():
            async with websockets.connect(f'ws://{args.host}:{port}/ws',max_size=1024*1024) as socket:
                async def request(identifier,**body):
                    await socket.send(json.dumps({'id':identifier,'protocol':1,**body}))
                    events=[]
                    while True:
                        response=json.loads(await asyncio.wait_for(socket.recv(),180))
                        assert response['id']==identifier
                        if response.get('type')=='assistant.state':events.append(response);continue
                        assert response['ok'],'Target request failed; response omitted to protect provider data'
                        return response['result'],events
                info,_=await request('baseline',type='tool',tool='device.info',arguments={})
                baseline=json.loads(info)
                allowed=['device.info']
                prompt='请调用 device_info 工具读取当前设备信息，只原样报告 model 和 sdk 两个字段。不得根据猜测回答。'
                expected_package=None
                if args.with_apps:
                    apps,_=await request('baseline-apps',type='tool',tool='apps.list',arguments={})
                    packages=sorted(app['package'] for app in json.loads(apps)['apps'])
                    assert packages,'Target application list is empty'
                    expected_package=packages[0]
                    allowed.append('apps.list')
                    prompt='请分别调用 device_info 和 apps_list，原样报告设备 model、sdk，以及应用列表按 package 字典序排序后的第一个包名。两个工具都必须执行，不得根据猜测回答。'
                answer,events=await request('readonly',type='text',progress=True,
                    arguments={'allowedTools':allowed},text=prompt)
                tools=[event.get('tool') for event in events if event.get('phase')=='tool']
                assert tools and set(tools)==set(allowed),'Expected actual execution of every scoped read-only tool'
                assert baseline['model'] in answer and str(baseline['sdk']) in answer,'Provider fields do not match independent device info'
                if expected_package is not None:
                    assert expected_package in answer,'Provider package does not match independent application list'
                print('PASS: real provider executed only '+', '.join(allowed)+'; reported fields match independent target results')
                if args.with_file_read:
                    listing,_=await request('baseline-files',type='tool',tool='files.list',arguments={'root':'inbox','path':'','limit':100})
                    metadata,_=await request('baseline-stat',type='tool',tool='files.stat',arguments={'root':'inbox','path':'approval-fixture.bin'})
                    metadata=json.loads(metadata)
                    expected_size=int(root(f'wc -c < {fixture}').strip())
                    entries=[entry for entry in json.loads(listing)['entries'] if entry['name']=='approval-fixture.bin']
                    assert len(entries)==1 and entries[0]['kind']=='file' and entries[0]['sizeBytes']==expected_size
                    assert metadata['root']=='inbox' and metadata['path']=='approval-fixture.bin'
                    assert metadata['kind']=='file' and metadata['sizeBytes']==expected_size
                    answer,events=await request('file-read',type='text',progress=True,
                        arguments={'allowedTools':['files.list','files.stat']},
                        text='请调用 files_list 列出 root=inbox、path=空字符串 的文件，再调用 files_stat 读取 root=inbox、path=approval-fixture.bin 的元信息。两个工具都必须实际执行。原样报告这个文件的名称和 sizeBytes 数值，不得修改文件或根据猜测回答。')
                    tools=[event.get('tool') for event in events if event.get('phase')=='tool']
                    assert tools and set(tools)=={'files.list','files.stat'},'Expected actual execution of both scoped file tools'
                    assert 'approval-fixture.bin' in answer and str(expected_size) in answer,'Provider metadata does not match independent file results'
                    assert root(f'sha256sum {fixture}').split()[0]==fixture_digest,'Read-only model request changed fixture bytes'
                    print('PASS: real provider executed files.list/files.stat; reported owned filename/size match independent results and SHA-256 is unchanged')
                if args.with_download_task:
                    await check_download(request,root,fixture,fixture_digest,opener,f'http://{args.host}:{port}')
                if args.with_storage_task:
                    await check_storage(request,root,fixture,fixture_digest)
                if args.with_file_proposal:
                    _,events=await request('proposal',type='text',progress=True,
                        arguments={'allowedTools':['files.delete']},
                        text='请调用 files_delete 为 root=inbox、path=approval-fixture.bin、recursive=false 准备删除提案。仅准备等待人工确认，不得执行删除或宣称已经删除。')
                    tools=[event.get('tool') for event in events if event.get('phase')=='tool']
                    assert tools and set(tools)=={'files.delete'},'Expected scoped deletion proposal execution'
                    pending,_=await request('pending',type='tool',tool='files.pending',arguments={})
                    proposals=json.loads(pending)
                    assert proposals,'Model did not prepare a pending proposal'
                    for proposal in proposals:
                        assert proposal['kind']=='delete' and proposal['root']=='inbox' and proposal['path']=='approval-fixture.bin'
                        assert proposal['approvalRequired'] is True and proposal['recursive'] is False
                    assert root(f'sha256sum {fixture}').split()[0]==fixture_digest,'File changed before approval'
                    for index,proposal in enumerate(proposals):
                        rejected,_=await request(f'reject-{index}',type='approve',arguments={'id':proposal['id'],'accepted':False})
                        assert json.loads(rejected).get('rejected') is True
                    pending,_=await request('pending-after-rejection',type='tool',tool='files.pending',arguments={})
                    assert json.loads(pending)==[]
                    assert root(f'sha256sum {fixture}').split()[0]==fixture_digest,'File changed after rejection'
                    print('PASS: real provider prepared owned deletion proposal; rejection cleared it and preserved file SHA-256')
        asyncio.run(asyncio.wait_for(check(),240))
    finally:
        try:
            if copied:root(f'{binary} service stop --state-dir {directory}/state')
        finally:
            if created:
                root(f'rm -rf -- {directory}')
                assert not root(f'if [ -e {directory} ]; then echo remains; fi').strip()
            after=json.loads(adb('shell',f'{main_binary} service status --state-dir {main_state}'))
            assert (before.get('running'),before.get('pid'))==(after.get('running'),after.get('pid'))
            print('Owned service/config copy removed; original main service state unchanged')

if __name__=='__main__':main()
