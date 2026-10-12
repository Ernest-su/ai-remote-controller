"""Host protocol smoke test; does not claim Android shell tool coverage.

Run after cargo build: python3 runtime/smoke_test.py
Requires the Python websockets package.
"""
import asyncio
import json
import os
import pathlib
import subprocess
import urllib.request
import urllib.error
import tempfile
import struct
import zlib
import shlex

import websockets
from mirror_backpressure_smoke import verify_backpressure


async def verify(port):
    for _ in range(100):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/status", timeout=1) as response:
                status = json.load(response)
            break
        except OSError:
            await asyncio.sleep(0.05)
    else:
        raise AssertionError("runtime did not become ready")
    assert status["protocol"] == 1
    assert status["uid"] == os.geteuid(), status
    assert "remote.key" in status["tools"]
    assert "device.info" in status["tools"]
    assert "device.system_capabilities" in status["tools"]
    assert "media.status" in status["tools"]
    assert "media.custom_action" in status["tools"]
    assert "device.screenshot" in status["tools"]
    assert "screen.preview_start" in status["tools"]
    assert "screen.mirror_start" in status["tools"]
    assert "gamepad.pulse" in status["tools"] and "gamepad.reset" in status["tools"]
    assert all("gamepad.virtual_" + name in status["tools"] for name in ("capabilities", "open", "update", "close"))
    assert "apps.clear_cache" in status["tools"] and "apps.cache_capabilities" in status["tools"]
    assert "device.system_state" in status["tools"]
    async with websockets.connect(f"ws://127.0.0.1:{port}/ws") as socket:
        assert "files.mkdir" in status["tools"]
        for request_id, path, accepted in (("mkdir-new", "owned-directory", True), ("mkdir-duplicate", "owned-directory", False), ("mkdir-escape", "../outside", False)):
            await socket.send(json.dumps({"id":request_id,"protocol":1,"type":"tool","tool":"files.mkdir","arguments":{"root":"inbox","path":path}}))
            created=json.loads(await socket.recv())
            assert created["id"]==request_id and created["ok"]==accepted,created
            if accepted:
                assert json.loads(created["result"])=={"root":"inbox","path":path,"created":True},created
        await socket.send(json.dumps({"id":"system-state","protocol":1,"type":"tool","tool":"device.system_state","arguments":{}}))
        state_reply=json.loads(await socket.recv())
        assert state_reply["ok"],state_reply
        observed=json.loads(state_reply["result"])
        assert observed["readOnly"] and not observed["available"],observed
        assert all(observed[name] is None for name in ("interactive","keyguardLocked","deviceLocked","deviceSecure")),observed
        await socket.send(json.dumps({"id":"system-capabilities","protocol":1,"type":"tool","tool":"device.system_capabilities","arguments":{}}))
        system_reply=json.loads(await socket.recv())
        assert system_reply["ok"],system_reply
        system_info=json.loads(system_reply["result"])
        assert system_info["readOnly"] and not system_info["supported"] and system_info["executionImplemented"],system_info
        await socket.send(json.dumps({"id":"virtual-capabilities","protocol":1,"type":"tool","tool":"gamepad.virtual_capabilities","arguments":{}}))
        capabilities=json.loads(await socket.recv())
        assert capabilities["ok"],capabilities
        capability=json.loads(capabilities["result"])
        assert capability["mode"]=="uinput" and isinstance(capability["supported"],bool),capability
        await socket.send(json.dumps({"id":"virtual-owner","protocol":1,"type":"tool","tool":"gamepad.virtual_close","arguments":{"id":"nonexistent"}}))
        missing=json.loads(await socket.recv())
        assert not missing["ok"] and "owned by another connection" in missing["error"],missing
        await socket.send(json.dumps({"id":"invalid-mirror","protocol":1,"type":"tool","tool":"screen.mirror_start","arguments":{"width":641,"height":360}}))
        rejected_mirror=json.loads(await socket.recv())
        assert rejected_mirror["id"]=="invalid-mirror" and not rejected_mirror["ok"],rejected_mirror
        await socket.send(json.dumps({"id":"invalid-cache","protocol":1,"type":"tool","tool":"apps.clear_cache","arguments":{"package":"com.test.app","data":True}}))
        rejected_cache=json.loads(await socket.recv())
        assert rejected_cache["id"]=="invalid-cache" and not rejected_cache["ok"] and "only package accepted" in rejected_cache["error"],rejected_cache
        await socket.send(json.dumps({"id":"invalid-gamepad","protocol":1,"type":"tool","tool":"gamepad.pulse","arguments":{"buttons":["power"]}}))
        rejected_gamepad=json.loads(await socket.recv())
        assert rejected_gamepad["id"]=="invalid-gamepad" and not rejected_gamepad["ok"] and "unsupported gamepad button" in rejected_gamepad["error"],rejected_gamepad
        await socket.send(json.dumps({"id": "invalid-custom", "protocol": 1, "type": "tool",
                                     "tool": "media.custom_action", "arguments": {"package": "com.test.player", "action": "test", "extras": {"episode": 1}}}))
        rejected = json.loads(await socket.recv())
        assert rejected["id"] == "invalid-custom" and not rejected["ok"] and "extras" in rejected["error"], rejected
        await socket.send(json.dumps({"id": "smoke", "protocol": 1, "type": "tool",
                                     "tool": "device.shell", "arguments": {"command": "printf airc-ok"}}))
        result = json.loads(await socket.recv())
        assert result["id"] == "smoke" and result["ok"] and result["result"] == "airc-ok", result
        await socket.send(json.dumps({"id": "info", "protocol": 1, "type": "tool",
                                     "tool": "device.info", "arguments": {}}))
        result = json.loads(await socket.recv())
        assert result["id"] == "info" and result["ok"], result
        info = json.loads(result["result"])
        assert info == {"manufacturer": "Fixture", "model": "Fixture TV",
                        "androidRelease": "14", "sdk": 34,
                        "abis": ["arm64-v8a", "armeabi-v7a"],
                        "screen": {"width": 720, "height": 1280, "source": "active_viewport"},
                        "storage": {"path": "/data", "mountPoint": "/data", "totalBytes": 1024000,
                                    "usedBytes": 409600, "availableBytes": 614400}}, info
        await socket.send(json.dumps({"id": "apps", "protocol": 1, "type": "tool",
                                     "tool": "apps.list", "arguments": {}}))
        result = json.loads(await socket.recv())
        assert result["id"] == "apps" and result["ok"], result
        assert json.loads(result["result"]) == {"apps": [
            {"package": "com.android.settings", "label": None, "system": True},
            {"package": "com.example.tv", "label": None, "system": False}], "source": "pm", "labelsAvailable": False}, result
        await socket.send(json.dumps({"id": "media", "protocol": 1, "type": "tool",
                                     "tool": "media.status", "arguments": {}}))
        result = json.loads(await socket.recv())
        assert result["id"] == "media" and result["ok"], result
        session = json.loads(result["result"])["sessions"][0]
        assert session["packageName"] == "com.example.player" and session["positionMs"] == 1234, session
        assert session["seekSupported"] and session["durationMs"] is None, session
        await socket.send(json.dumps({"id":"screenshot", "protocol":1, "type":"tool", "tool":"device.screenshot", "arguments":{}}))
        result = json.loads(await socket.recv())
        assert result["ok"], result
        artifact = json.loads(result["result"])
        assert artifact["mimeType"] == "image/png" and artifact["width"] == 1 and artifact["height"] == 1, artifact
        with urllib.request.urlopen(f"http://127.0.0.1:{port}" + artifact["path"], timeout=1) as response:
            png = response.read()
            assert response.headers["Content-Type"] == "image/png"
            assert response.headers["Cache-Control"] == "no-store"
            assert len(png) == artifact["sizeBytes"] and png.startswith(b"\x89PNG\r\n\x1a\n")
        await socket.send(json.dumps({"id":"preview", "protocol":1, "type":"tool", "tool":"screen.preview_start", "arguments":{"intervalMs":500,"durationSeconds":10}}))
        preview_reply = json.loads(await socket.recv()); assert preview_reply["ok"], preview_reply
        preview = json.loads(preview_reply["result"])
        with urllib.request.urlopen(f"http://127.0.0.1:{port}" + preview["path"], timeout=3) as response:
            assert response.headers["Content-Type"] == "application/x-airc-preview"
            assert response.read(8) == b"AIRCPRV1"
            for sequence in [1,2]:
                count,width,height,size,elapsed = struct.unpack(">IIIIQ",response.read(24))
                assert count == sequence and width == height == 1 and size <= 16*1024*1024
                assert response.read(size) == png
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{port}" + preview["path"], timeout=3).close()
                raise AssertionError("preview ticket accepted a second reader")
            except urllib.error.HTTPError as error:
                assert error.code == 409, error
            await socket.send(json.dumps({"id":"preview-stop", "protocol":1, "type":"tool", "tool":"screen.preview_stop", "arguments":{"id":preview["id"]}}))
            assert json.loads(await socket.recv())["ok"]
            for _ in range(10):
                count,width,height,size,elapsed = struct.unpack(">IIIIQ",response.read(24))
                if width == height == size == 0: break
                assert width == height == 1; response.read(size)
            else: raise AssertionError("preview did not end after stop")
        gestures = [
            ("remote.tap", {"x": 1, "y": 0}, "tap 719 0 "),
            ("remote.tap", {"x": 1, "y": 0, "count": 2}, "tap 719 0 tap 719 0 "),
            ("remote.swipe", {"startX": 0, "startY": 0, "endX": 1, "endY": 1}, "swipe 0 0 719 1279 300 "),
            ("remote.long_press", {"x": 0, "y": 1}, "swipe 0 1279 0 1279 800 "),
        ]
        for tool, arguments, expected in gestures:
            await socket.send(json.dumps({"id": tool, "protocol": 1, "type": "tool",
                                         "tool": tool, "arguments": arguments}))
            result = json.loads(await socket.recv())
            assert result["id"] == tool and result["ok"], result
            assert json.loads(result["result"]) == {"width": 720, "height": 1280, "output": expected}, result
        await socket.send(json.dumps({"id": "bad", "protocol": 99, "type": "tool"}))
        result = json.loads(await socket.recv())
        assert result["id"] == "bad" and not result["ok"]
        await socket.send("invalid-json")
        assert not json.loads(await socket.recv())["ok"]

    async def mirror_tool(socket, tool, args):
        await socket.send(json.dumps(dict(id="owned-mirror",protocol=1,type="tool",tool=tool,arguments=args)))
        return json.loads(await socket.recv())
    async with websockets.connect(f"ws://127.0.0.1:{port}/ws") as owner:
        prepared=await mirror_tool(owner,"screen.mirror_start",{})
        assert prepared["ok"],prepared
        ticket=json.loads(prepared["result"])
        async with websockets.connect(f"ws://127.0.0.1:{port}/ws") as other:
            denied=await mirror_tool(other,"screen.mirror_stop",{"id":ticket["id"]})
            assert not denied["ok"] and "another connection" in denied["error"],denied
    async with websockets.connect(f"ws://127.0.0.1:{port}/ws") as replacement:
        for _ in range(20):
            resumed=await mirror_tool(replacement,"screen.mirror_start",{})
            if resumed["ok"]:break
            await asyncio.sleep(.05)
        assert resumed["ok"],resumed
        new_ticket=json.loads(resumed["result"])
        assert new_ticket["id"] != ticket["id"]
        assert (await mirror_tool(replacement,"screen.mirror_stop",{"id":new_ticket["id"]}))["ok"]


if __name__ == "__main__":
    binary = pathlib.Path(__file__).parent / "target/debug/airc-runtime"
    # Let the OS select a free port, read the actual listener address.
    directory = tempfile.TemporaryDirectory(prefix="airc-smoke-")
    # Android command fixtures are scoped to this child process, never the host PATH.
    fixture_bin = pathlib.Path(directory.name) / "bin"
    fixture_bin.mkdir()
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    png_file = fixture_bin / "fixture.png"
    png_file.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
                         + chunk(b"IDAT", zlib.compress(b"\x00\x00\x00\x00")) + chunk(b"IEND", b""))
    encoder_pid_file = fixture_bin / "encoder.pid"
    fixtures = {
        "screenrecord": "echo $$ > " + shlex.quote(str(encoder_pid_file)) + "\nexec head -c 67108864 /dev/zero\n",
        "screencap": "cat " + shlex.quote(str(png_file)) + "\n",
        "dumpsys": "if [ \"$1\" = media_session ]; then printf 'package=com.example.player\\nactive=true\\nstate=PlaybackState {state=PLAYING(3), position=1234, updated=12, actions=256}\\n'; exit; fi\n echo 'Viewport INTERNAL: displayId=0, orientation=1, logicalFrame=[0, 0, 720, 1280], isActive=[1]'\n",
        "input": "printf '%s ' \"$@\"\n",
        "pm": "if [ \"$3\" = \"-s\" ]; then echo package:com.android.settings; else printf 'package:com.example.tv\\npackage:com.android.settings\\n'; fi\n",
        "getprop": "case \"$1\" in\nro.product.manufacturer) echo Fixture;;\nro.product.model) echo 'Fixture TV';;\nro.build.version.release) echo 14;;\nro.build.version.sdk) echo 34;;\nro.product.cpu.abilist) echo arm64-v8a,armeabi-v7a;;\n*) exit 1;;\nesac\n",
        "wm": "printf 'Physical size: 1920x1080\\nOverride size: 1280x720\\n'\n",
        "df": "printf 'Filesystem 1K-blocks Used Available Use%% Mounted on\\n/dev/block/dm-0 1000 400 600 40%% /data\\n'\n",
    }
    for name, script in fixtures.items():
        executable = fixture_bin / name
        executable.write_text("#!/bin/sh\n" + script)
        executable.chmod(0o700)
    environment = dict(os.environ, PATH=str(fixture_bin) + os.pathsep + os.environ["PATH"])
    process = subprocess.Popen([str(binary), "--port", "0", "--state-dir", directory.name, "--no-mdns"], stdout=subprocess.PIPE, text=True, env=environment)
    try:
        line = process.stdout.readline()
        port = int(line.strip().rsplit(":", 1)[1])
        asyncio.run(verify(port))
        asyncio.run(verify_backpressure(port, encoder_pid_file, process.pid))
        print("runtime HTTP/WebSocket smoke test passed")
    finally:
        process.terminate()
        process.wait(timeout=5)
        directory.cleanup()
