#!/usr/bin/env python3
"""Crash the selected development phone's assistant and require microphone recovery."""
import argparse
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
args = parser.parse_args()
package = "com.openodoubao.assistant"


def shell(*command):
    return subprocess.run(["adb", "-s", args.serial, "shell", *command],
                          capture_output=True, text=True, check=True, timeout=10).stdout


def recording():
    audio = shell("dumpsys", "audio")
    active = audio.split("## RecordActivityMonitor", 1)[1].split("### Recording Activity", 1)[0]
    return any(f"pack:{package}" in line and "silenced:false" in line
               for line in active.splitlines())


before = shell("pidof", package).strip()
assert before and recording(), "Enable wake listening and wait for active microphone capture first"
shell("am", "crash", package)
started = time.monotonic()
while time.monotonic() - started < 30:
    time.sleep(0.5)
    pid = shell("sh", "-c", f"'pidof {package} || true'").strip()
    if pid and pid != before and recording():
        print(f"PASS: PID {before} -> {pid}; microphone recovered in {time.monotonic() - started:.1f}s")
        break
else:
    raise AssertionError("Assistant microphone did not recover within 30 seconds")
