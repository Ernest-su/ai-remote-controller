"""Real runtime protocol/lifecycle/mDNS tests with a deterministic model API.

Build first with cargo build. Requires websockets and zeroconf.
The API fixture returns real tool calls and verifies their real output.
"""
import asyncio
import http.server
import json
import pathlib
import stat
import subprocess
import tempfile
import threading
import time
import sys
import urllib.request

import websockets
import model_file_fixture
from zeroconf import ServiceBrowser, ServiceListener, Zeroconf

BINARY = pathlib.Path(__file__).parent.resolve() / "target/debug/airc-runtime"


class Api(http.server.BaseHTTPRequestHandler):
    calls = 0
    failures = []
    slow_started = threading.Event()

    def log_message(self, *_):
        pass

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        try:
            assert self.headers["Authorization"] == "Bearer fixture-key"
            assert self.path == "/v1/chat/completions"
            request = json.loads(body)
            assert request["model"] == "fixture-model"
            messages = request["messages"]
            file_message = model_file_fixture.reply(messages, request["tools"])
            if file_message is not None:
                message = file_message
            elif messages[-1]["role"] == "tool":
                result = json.loads(messages[-1]["content"])
                assert result == {"ok": True, "output": "agent-ok"}, result
                message = {"role": "assistant", "content": "fixture completed"}
            else:
                assert messages[-1]["role"] == "user"
                if messages[-1]["content"] == "slow fixture shell":
                    Api.slow_started.set()
                    time.sleep(5)
                message = {"role": "assistant", "content": None, "tool_calls": [
                    {"id": "fixture-call", "type": "function", "function": {"name": "device_shell", "arguments": json.dumps({"command": "printf agent-ok"})}}]}
            Api.calls += 1
            response = {"choices": [{"message": message}]}
            encoded = json.dumps(response).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
        except Exception as error:
            Api.failures.append(str(error))
            self.send_error(500)


class Discovery(ServiceListener):
    def __init__(self):
        self.found = {}

    def add_service(self, zeroconf, type_, name):
        info = zeroconf.get_service_info(type_, name)
        if info:
            self.found[info.properties.get(b"id", b"").decode()] = info

    update_service = add_service

    def remove_service(self, zeroconf, type_, name):
        pass


def control(directory, operation):
    output = subprocess.check_output([str(BINARY), "service", operation, "--state-dir", directory, "--port", "0"], text=True)
    return json.loads(output)


async def protocol(port, api_port):
    async with websockets.connect(f"ws://127.0.0.1:{port}/ws", ping_interval=1, ping_timeout=2) as socket:
        async def request(type_, expected_tools=None, **fields):
            await socket.send(json.dumps({"id": type_, "protocol": 1, "type": type_, **fields}))
            events = []
            while True:
                result = json.loads(await socket.recv())
                assert result["id"] == type_, result
                if result.get("type") != "assistant.state":
                    break
                events.append(result)
            if fields.get("progress"):
                expected_tools = expected_tools or ["device.shell"]
                expected = [phase for _ in expected_tools for phase in ("thinking", "tool")] + ["thinking"]
                assert [event["phase"] for event in events] == expected, events
                tool_events=[event for event in events if event["phase"]=="tool"]
                assert [event.get("tool") for event in tool_events]==expected_tools,tool_events
                assert all(event.get("text","")=="" and "arguments" not in event and "apiKey" not in event for event in tool_events),tool_events
            else:
                assert not events, "legacy requests must not receive progress"
            assert result["id"] == type_, result
            assert result["ok"], result
            return result.get("result")

        # Direct commands work before model configuration.
        assert await request("tool", tool="device.shell", arguments={"command": "printf direct-ok"}) == "direct-ok"
        await request("configure", arguments={"baseUrl": f"http://127.0.0.1:{api_port}/v1", "apiKey": "fixture-key", "model": "fixture-model"})
        assert await request("text", text="execute fixture shell", progress=True) == "fixture completed"
        assert await request("text", text=model_file_fixture.PROMPT, progress=True,
                             expected_tools=model_file_fixture.TOOLS) == "directory created; deletion awaits user approval"
        directory_args = {"root": "inbox", "path": model_file_fixture.PATH}
        # Model preparation never authorizes deletion. Verify through independent direct tools.
        assert json.loads(await request("tool", tool="files.stat", arguments=directory_args))["kind"] == "directory"
        pending = json.loads(await request("tool", tool="files.pending", arguments={}))
        proposal = next(item for item in pending if item["path"] == model_file_fixture.PATH)
        assert proposal["approvalRequired"] is True
        assert json.loads(await request("approve", arguments={"id": proposal["id"], "accepted": False}))["rejected"] is True
        assert json.loads(await request("tool", tool="files.stat", arguments=directory_args))["kind"] == "directory"
        Api.slow_started.clear()
        await socket.send(json.dumps({"id": "slow", "protocol": 1, "type": "text", "text": "slow fixture shell"}))
        assert await asyncio.to_thread(Api.slow_started.wait, 2), "model request never started"
        for index in range(3):
            await socket.send(json.dumps({"id": f"during-model-{index}", "protocol": 1, "type": "tool", "tool": "device.shell", "arguments": {"command": f"printf controls-inflight-{index}"}}))
        for index in range(3):
            immediate = json.loads(await asyncio.wait_for(socket.recv(), 2))
            assert immediate["id"] == f"during-model-{index}" and immediate["result"] == f"controls-inflight-{index}", immediate
        completed = json.loads(await socket.recv())
        assert completed["id"] == "slow" and completed["result"] == "fixture completed", completed
        # Removed input types are explicitly rejected, without model API calls.
        await socket.send(json.dumps({"id":"removed-input","protocol":1,"type":"audio.start"}))
        rejected = json.loads(await socket.recv())
        assert rejected["id"] == "removed-input" and not rejected["ok"], rejected


def main():
    api = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Api)
    thread = threading.Thread(target=api.serve_forever, daemon=True)
    thread.start()
    zeroconf = Zeroconf()
    discovery = Discovery()
    browser = ServiceBrowser(zeroconf, "_airc._tcp.local.", discovery)
    with tempfile.TemporaryDirectory(prefix="airc-e2e-") as directory:
        try:
            started = control(directory, "start")
            assert started["running"] and started["ready"]
            current = control(directory, "status")
            device_id = current["deviceId"]
            reused = control(directory, "start")
            assert reused["pid"] == current["pid"], "idempotent start spawned a second process"
            deadline = time.monotonic() + 15
            while device_id not in discovery.found and time.monotonic() < deadline:
                time.sleep(0.1)
            assert device_id in discovery.found, "independent runtime mDNS advertisement not resolved"
            info = discovery.found[device_id]
            assert info.port == current["port"] and info.properties[b"protocol"] == b"1"
            asyncio.run(protocol(current["port"], api.server_port))
            assert Api.calls == 8 and not Api.failures, (Api.calls, Api.failures)
            config = pathlib.Path(directory) / "model.json"
            assert stat.S_IMODE(config.stat().st_mode) == 0o600
            assert control(directory, "stop") == {"running": False}
            assert control(directory, "status") == {"running": False}
            restarted = control(directory, "start")
            again = control(directory, "status")
            assert again["deviceId"] == device_id
            with urllib.request.urlopen(f"http://127.0.0.1:{restarted['port']}/status") as response:
                assert json.load(response)["modelConfigured"] is True
            print("runtime lifecycle, mDNS, direct tools, LLM decisions and execution progress passed")
        finally:
            control(directory, "stop")
    browser.cancel()
    zeroconf.close()
    api.shutdown()


if __name__ == "__main__":
    if "--serve-api" in sys.argv:
        api = http.server.ThreadingHTTPServer(("0.0.0.0", 19877), Api)
        print("fixture model API listening on 19877", flush=True)
        api.serve_forever()
    else:
        main()
