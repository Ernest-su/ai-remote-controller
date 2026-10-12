import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('emulator_system', Path(__file__).with_name('test-emulator-system.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class EmulatorSystemGuardTest(unittest.TestCase):
    def test_shutdown_requires_explicit_container_before_adb(self):
        with patch('sys.argv',['test','--emulator','emulator-5580','--action','shutdown']), \
                patch.object(module.subprocess,'check_output') as adb, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):module.main()
            adb.assert_not_called()
    def test_physical_serials_are_rejected_before_any_adb_call(self):
        for serial in ('192.168.68.59:5555', '40ac3b40', 'emulator-5580;reboot'):
            with self.subTest(serial=serial), patch('sys.argv', ['test', '--emulator', serial]), \
                    patch.object(module.subprocess, 'check_output') as adb, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:
                    module.main()
                self.assertEqual(2, error.exception.code)
                adb.assert_not_called()

    def test_non_qemu_device_stops_before_privilege_or_setup(self):
        with patch('sys.argv', ['test', '--emulator', 'emulator-5580']), \
                patch.object(module.subprocess, 'check_output', return_value='0\n') as adb:
            with self.assertRaisesRegex(RuntimeError, 'not an emulator'):
                module.main()
            self.assertEqual(1, adb.call_count)
            self.assertEqual(['adb', '-s', 'emulator-5580', 'shell', 'getprop ro.kernel.qemu'], adb.call_args.args[0])

    def test_unprivileged_emulator_stops_before_deployment(self):
        with patch('sys.argv', ['test', '--emulator', 'emulator-5580']), \
                patch.object(module.subprocess, 'check_output', side_effect=['1\n', '2000\n']) as adb:
            with self.assertRaisesRegex(RuntimeError, 'root adbd'):
                module.main()
            self.assertEqual(2, adb.call_count)


if __name__ == '__main__':
    unittest.main()
