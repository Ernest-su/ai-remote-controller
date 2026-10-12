import copy
import unittest
from emulator_shutdown_observation import container_matches, shutdown_observed, LABEL


class ShutdownObservationTest(unittest.TestCase):
    def fixture(self):
        return {'Config': {'Labels': {LABEL: 'true'}, 'Cmd': ['-port','5580','-show-kernel'],
                'Entrypoint': ['/sdk/emulator/emulator']}, 'HostConfig': {'NetworkMode':'host','AutoRemove':False}}

    def test_labelled_matching_emulator_is_accepted(self):
        self.assertTrue(container_matches(self.fixture(),'emulator-5580'))

    def test_unowned_or_mismatched_containers_are_rejected(self):
        original=self.fixture()
        for kind in ('label','null-label','port','logging','autoremove'):
            value=copy.deepcopy(original)
            if kind=='label':value['Config']['Labels']={}
            if kind=='null-label':value['Config']['Labels']=None
            if kind=='port':value['Config']['Cmd'][1]='5582'
            if kind=='logging':value['Config']['Cmd'].remove('-show-kernel')
            if kind=='autoremove':value['HostConfig']['AutoRemove']=True
            self.assertFalse(container_matches(value,'emulator-5580'),kind)

    def test_exit_without_kernel_powerdown_is_not_success(self):
        self.assertFalse(shutdown_observed({'Running':False,'Status':'exited','ExitCode':0},'transport disconnected'))

    def test_kernel_line_without_normal_exit_is_not_success(self):
        for state in ({'Running':True,'Status':'running','ExitCode':0}, {'Running':False,'Status':'exited','ExitCode':137}, {}):
            self.assertFalse(shutdown_observed(state,'reboot: Power down'))

    def test_normal_exit_and_kernel_powerdown_are_success(self):
        self.assertTrue(shutdown_observed({'Running':False,'Status':'exited','ExitCode':0},'[42.0] reboot: Power down'))


if __name__=='__main__':
    unittest.main()
