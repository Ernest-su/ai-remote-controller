"""Strict acceptance of AndroidJUnitRunner output, including per-test outcomes."""
import re


def require_passed(output, expected_count):
    statuses = [int(code) for code in re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", output, re.MULTILINE)]
    terminals = [code for code in statuses if code != 1]
    summaries = re.findall(r"^OK \((\d+) tests?\)\s*$", output, re.MULTILINE)
    finish = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", output, re.MULTILINE)
    if (summaries != [str(expected_count)] or finish != ["-1"]
            or statuses.count(1) != expected_count
            or terminals != [0] * expected_count
            or "FAILURES!!!" in output or "INSTRUMENTATION_FAILED" in output):
        raise RuntimeError("Acceptance requires every expected test to finish successfully; skipped, failed or incomplete runs are rejected")
