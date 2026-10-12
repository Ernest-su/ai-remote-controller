"""Host transport/owned-child backpressure proof. Fixture bytes are not AVC."""
import asyncio
import json
import pathlib
import socket
import websockets


async def verify_backpressure(port, pid_file, runtime_pid):
    async def tool(channel, name, args=None):
        await channel.send(json.dumps(dict(id='backpressure', protocol=1, type='tool',
                                          tool=name, arguments=args or {})))
        return json.loads(await channel.recv())

    peer = None
    async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as owner:
        reply = await tool(owner, 'screen.mirror_start')
        assert reply['ok'], reply
        ticket = json.loads(reply['result'])
        try:
            def connect_without_reading_body():
                peer = socket.socket()
                peer.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
                peer.settimeout(3)
                peer.connect(('127.0.0.1', port))
                peer.sendall(f"GET {ticket['path']} HTTP/1.1\r\nHost: localhost\r\n\r\n".encode())
                headers = bytearray()
                while not headers.endswith(b'\r\n\r\n'):
                    headers.extend(peer.recv(1))
                    assert len(headers) <= 16384
                assert headers.startswith(b'HTTP/1.1 200'), headers
                return peer
            peer = await asyncio.to_thread(connect_without_reading_body)
            for _ in range(100):
                if pid_file.is_file():
                    break
                await asyncio.sleep(.02)
            pid = int(pid_file.read_text())
            proc = pathlib.Path('/proc') / str(pid)
            fields = (proc / 'stat').read_text().split(') ', 1)[1].split()
            assert int(fields[1]) == runtime_pid, 'observer must target the fixture child'
            for _ in range(100):
                wait_channel = (proc / 'wchan').read_text().strip()
                if wait_channel.endswith('pipe_write'):
                    break
                await asyncio.sleep(.02)
            else:
                raise AssertionError('fixture producer never reached actual pipe backpressure: ' + wait_channel)
            # Keep the stalled TCP peer open. Only cancel via its owning websocket.
            stopped = await tool(owner, 'screen.mirror_stop', {'id': ticket['id']})
            assert stopped['ok'], stopped
            for _ in range(100):
                if not proc.exists():
                    break
                await asyncio.sleep(.02)
            else:
                raise AssertionError('backpressured fixture encoder was not reaped')
            for _ in range(100):
                prepared = await tool(owner, 'screen.mirror_start')
                if prepared['ok']:
                    break
                await asyncio.sleep(.02)
            assert prepared['ok'], prepared
            replacement = json.loads(prepared['result'])
            assert (await tool(owner, 'screen.mirror_stop', {'id': replacement['id']}))['ok']
            assert (await tool(owner, 'device.screenshot'))['ok'], 'capture permit leaked'
        finally:
            if peer is not None:
                peer.close()
