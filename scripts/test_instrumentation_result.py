import unittest
from instrumentation_result import require_passed


def result(code=0, finish=True):
    return f"INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: {code}\nOK (1 test)\n" + ("INSTRUMENTATION_CODE: -1\n" if finish else "")


class InstrumentationResultTest(unittest.TestCase):
    def test_success(self):
        require_passed(result(), 1)

    def test_skipped_or_failed_test_cannot_pass_using_ok_summary(self):
        for code in [-1, -2, -3, -4]:
            with self.subTest(code=code), self.assertRaises(RuntimeError):
                require_passed(result(code), 1)

    def test_incomplete_or_wrong_count(self):
        for output, count in [(result(finish=False), 1), (result(), 2), ("OK (1 test)\nINSTRUMENTATION_CODE: -1\n", 1)]:
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                require_passed(output, count)


if __name__ == "__main__":
    unittest.main()
