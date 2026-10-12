"""Independent boot observations; a disconnected transport is never reboot proof."""
import asyncio
import subprocess
import time
import uuid


def reboot_observed(before, current, completed):
    try:
        return uuid.UUID(before) != uuid.UUID(current) and completed == '1'
    except (ValueError, TypeError, AttributeError):
        return False


async def wait_reboot(adb, before, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            current = (await asyncio.to_thread(adb, 'shell', 'cat /proc/sys/kernel/random/boot_id')).strip()
            completed = (await asyncio.to_thread(adb, 'shell', 'getprop sys.boot_completed')).strip()
            if reboot_observed(before, current, completed):
                return
        except (subprocess.SubprocessError, OSError):
            pass
        await asyncio.sleep(.5)
    raise AssertionError('No independent changed boot ID and completed boot observed')
