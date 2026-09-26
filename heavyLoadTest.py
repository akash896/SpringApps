#!/usr/bin/env python3
"""High-concurrency REST traffic to trigger Resilience4j protection metrics.

Run ./start.sh first. Defaults intentionally exceed the configured rate limits
and thread-pool bulkhead capacity. It runs until Ctrl-C; override workers with:
  python3 heavyLoadTest.py --workers 120
"""
from __future__ import annotations

import argparse
import json
import random
import threading
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

ORDER_URL = "http://localhost:8081/api/orders"
INVENTORY_URL = "http://localhost:8082/api/inventory/{}"
PRICE_URL = "http://localhost:8083/api/prices/{}"
SKUS = ("BOOK-1", "BOOK-2", "LAMP-3", "DESK-4", "PEN-5", "CHAIR-6", "MUG-7")


def request(method: str, url: str, payload: dict | None = None) -> int:
    data = json.dumps(payload).encode("utf-8") if payload else None
    headers = {"Content-Type": "application/json"} if data else {}
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            response.read()
            return response.status
    except urllib.error.HTTPError as error:
        error.read()
        return error.code
    except (urllib.error.URLError, TimeoutError):
        return 0


def random_call() -> int:
    sku = random.choice(SKUS)
    operation = random.randrange(3)
    if operation == 0:
        return request("GET", PRICE_URL.format(sku))
    if operation == 1:
        return request("GET", INVENTORY_URL.format(sku))
    return request("POST", ORDER_URL, {"sku": sku, "quantity": random.randint(1, 12)})


def worker(stop: threading.Event, results: Counter[int], lock: threading.Lock) -> None:
    while not stop.is_set():
        status = random_call()
        with lock:
            results[status] += 1


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate high load for the resilience demo stack")
    parser.add_argument("--workers", type=int, default=100, help="parallel request workers (default: 100)")
    args = parser.parse_args()
    if args.workers < 1:
        parser.error("--workers must be positive")

    started = time.monotonic()
    results: Counter[int] = Counter()
    lock = threading.Lock()
    stop = threading.Event()
    print(f"Heavy load is running with {args.workers} workers. Press Ctrl-C to stop.")
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        for _ in range(args.workers):
            executor.submit(worker, stop, results, lock)
        try:
            previous_total = 0
            while True:
                time.sleep(10)
                with lock:
                    snapshot = dict(sorted(results.items()))
                    total = sum(results.values())
                interval_count = total - previous_total
                elapsed = time.monotonic() - started
                print(
                    f"{elapsed:.0f}s elapsed; {interval_count} requests in last 10s; "
                    f"HTTP status counts: {snapshot}"
                )
                previous_total = total
        except KeyboardInterrupt:
            stop.set()
    print("Heavy load stopped.")
    print("Expected under heavy load: rate-limit and bulkhead rejections; inspect the JMX 'metrics' domain.")


if __name__ == "__main__":
    main()
