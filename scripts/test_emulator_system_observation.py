import unittest
from emulator_system_observation import reboot_observed


class RebootObservationTest(unittest.TestCase):
    before='11111111-1111-4111-8111-111111111111'
    after='22222222-2222-4222-8222-222222222222'

    def test_old_boot_with_completed_flag_is_not_success(self):
        self.assertFalse(reboot_observed(self.before,self.before,'1'))

    def test_changed_boot_without_completed_flag_is_not_success(self):
        self.assertFalse(reboot_observed(self.before,self.after,''))

    def test_missing_or_invalid_boot_id_is_not_success(self):
        for value in (None,'','offline','invalid'):
            self.assertFalse(reboot_observed(self.before,value,'1'))

    def test_changed_valid_boot_and_completed_flag_are_success(self):
        self.assertTrue(reboot_observed(self.before,self.after,'1'))


if __name__=='__main__':
    unittest.main()
