"""Print test-worker stacks during long CI waits, without reading environments or heap data."""
import subprocess
import time


def snapshot():
    listing = subprocess.run(
        ["jps", "-l"], capture_output=True, text=True, timeout=10, check=True
    )
    for line in listing.stdout.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[1].endswith("GradleWorkerMain"):
            print(f"CI diagnostic: unit-test worker {fields[0]} thread snapshot", flush=True)
            subprocess.run(["jcmd", fields[0], "Thread.print"], timeout=20, check=False)


if __name__ == "__main__":
    while True:
        time.sleep(300)
        try:
            snapshot()
        except (OSError, subprocess.SubprocessError) as failure:
            print(f"CI diagnostic unavailable: {type(failure).__name__}", flush=True)
