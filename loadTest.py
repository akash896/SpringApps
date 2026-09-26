#!/usr/bin/env python3
"""Normal-load REST traffic for the three local resilience demo services.

Run ./start.sh first. Successful and fallback calls update Resilience4j meters,
which the active JmxMeterRegistry publishes as JMX MBeans.
"""
from __future__ import annotations

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
SKUS = ("BOOK-1", "BOOK-2", "LAMP-3", "DESK-4", "PEN-5")


def request(method: str, url: str, payload: dict | None = None) -> int:
    data = json.dumps(payload).encode("utf-8") if payload else None
    headers = {"Content-Type": "application/json"} if data else {}
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            response.read()
            return response.status
    except urllib.error.HTTPError as error:
        error.read()
        return error.code
    except (urllib.error.URLError, TimeoutError):
        return 0


def random_call() -> int:
    # Pace the normal test so it stays below the configured service limits.
    time.sleep(random.uniform(0.08, 0.16))
    sku = random.choice(SKUS)
    operation = random.choice(("price", "inventory", "order"))
    if operation == "price":
        return request("GET", PRICE_URL.format(sku))
    if operation == "inventory":
        return request("GET", INVENTORY_URL.format(sku))
    return request("POST", ORDER_URL, {"sku": sku, "quantity": random.randint(1, 4)})


def worker(stop: threading.Event, results: Counter[int], lock: threading.Lock) -> None:
    while not stop.is_set():
        status = random_call()
        with lock:
            results[status] += 1


def main() -> None:
    workers = 6
    started = time.monotonic()
    results: Counter[int] = Counter()
    lock = threading.Lock()
    stop = threading.Event()
    print("Normal load is running. Press Ctrl-C to stop.")
    with ThreadPoolExecutor(max_workers=workers) as executor:
        for _ in range(workers):
            executor.submit(worker, stop, results, lock)
        try:
            while True:
                time.sleep(10)
                with lock:
                    snapshot = dict(sorted(results.items()))
                elapsed = time.monotonic() - started
                print(f"{elapsed:.0f}s elapsed; HTTP status counts: {snapshot}")
        except KeyboardInterrupt:
            stop.set()
    print("Normal load stopped.")
    print("Inspect JMX domain 'metrics' or /actuator/metrics for Resilience4j meters.")


if __name__ == "__main__":
    main()
