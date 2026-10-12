"""Observe shutdown and recover only a labelled, explicitly selected emulator container."""
import asyncio
import json
import subprocess
import time

LABEL = 'ernest.airc.system-test'


def container_matches(info, serial):
    config = info.get('Config') or {}
    host = info.get('HostConfig') or {}
    command = config.get('Cmd') or []
    entrypoint = config.get('Entrypoint') or []
    try:
        port = command[command.index('-port') + 1]
    except (ValueError, IndexError):
        return False
    return ((config.get('Labels') or {}).get(LABEL) == 'true'
            and serial == f'emulator-{port}' and '-show-kernel' in command
            and any(value.endswith('/emulator/emulator') for value in entrypoint)
            and host.get('NetworkMode') == 'host'
            and host.get('AutoRemove') is False)


def shutdown_observed(state, logs):
    return (state.get('Running') is False and state.get('Status') == 'exited'
            and state.get('ExitCode') == 0 and 'reboot: Power down' in logs)


class EmulatorContainer:
    def __init__(self, name, serial):
        info = self.inspect(name)
        if not container_matches(info, serial) or info['State']['Running'] is not True:
            raise RuntimeError('Refusing shutdown: need a running labelled emulator with matching port and kernel logging')
        self.identifier = info['Id']
        self.started_at = info['State']['StartedAt']

    @staticmethod
    def inspect(identifier):
        return json.loads(subprocess.check_output(['docker', 'inspect', identifier], text=True, timeout=15))[0]

    async def wait_shutdown(self, timeout=120):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            info = await asyncio.to_thread(self.inspect, self.identifier)
            logs = await asyncio.to_thread(subprocess.check_output, ['docker', 'logs', '--since', self.started_at, self.identifier], text=True, stderr=subprocess.STDOUT, timeout=15)
            if shutdown_observed(info['State'], logs):
                return
            if info['State']['Running'] is False:
                raise AssertionError('Emulator exited without independent normal kernel power-down evidence')
            await asyncio.sleep(.5)
        raise AssertionError('No emulator exit with kernel power-down observed')

    def recover(self, adb):
        info = self.inspect(self.identifier)
        if info['State']['Running'] is False:
            subprocess.run(['docker', 'start', self.identifier], check=True, stdout=subprocess.DEVNULL, timeout=30)
        adb('wait-for-device')
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if adb('shell', 'getprop sys.boot_completed').strip() == '1':
                return
            time.sleep(.5)
        raise RuntimeError('Owned emulator did not finish booting for cleanup')
