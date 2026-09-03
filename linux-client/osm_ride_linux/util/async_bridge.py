"""Runs an asyncio event loop on a background thread, so bleak (and BRouter's HTTP calls) can be
used from a GTK app without GTK's own GLib main loop needing to understand asyncio.

GTK isn't async-aware and bleak is async-only, so something has to bridge the two. Running
asyncio on a dedicated thread (rather than trying to pump it inside GTK's own main loop) keeps
the two loops fully independent - the only handoff point is submit(), which schedules a coroutine
on the background loop and marshals its result back to the GTK thread via GLib.idle_add, which is
the one thing in this module GTK-specific enough that it can't be unit-tested without GTK itself.
"""

from __future__ import annotations

import asyncio
import concurrent.futures
import threading
from collections.abc import Awaitable, Callable
from typing import TypeVar

T = TypeVar("T")


class AsyncBridge:
    def __init__(self) -> None:
        self._loop = asyncio.new_event_loop()
        self._ready = threading.Event()
        self._thread = threading.Thread(target=self._run_loop, name="asyncio-bridge", daemon=True)
        self._thread.start()
        self._ready.wait()

    def _run_loop(self) -> None:
        asyncio.set_event_loop(self._loop)
        self._ready.set()
        self._loop.run_forever()

    def submit(
        self,
        coro: Awaitable[T],
        on_done: Callable[[T], None] | None = None,
        on_error: Callable[[BaseException], None] | None = None,
        marshal: Callable[[Callable[[], None]], None] | None = None,
    ) -> concurrent.futures.Future:
        """Schedules coro on the background loop. on_done/on_error (if given) run via `marshal`
        (defaults to calling directly on the background thread - GTK callers should pass
        GLib.idle_add so callbacks land on the main thread instead).

        Returns the underlying future so callers can cancel a still-running coroutine (e.g. "Stop
        Scan") via future.cancel() - run_coroutine_threadsafe's future is specifically wired so
        cancelling it propagates a real asyncio.CancelledError into the coroutine, not just a
        local no-op, even if the coroutine is already mid-await."""
        dispatch = marshal or (lambda fn: fn())

        def _on_future_done(future: concurrent.futures.Future) -> None:
            if future.cancelled():
                return  # an intentional Stop Scan, not a failure - nothing to report
            try:
                result = future.result()
            except Exception as e:  # noqa: BLE001 - deliberately broad, handed to on_error
                if on_error:
                    dispatch(lambda: on_error(e))
                return
            if on_done:
                dispatch(lambda: on_done(result))

        future = asyncio.run_coroutine_threadsafe(coro, self._loop)
        future.add_done_callback(_on_future_done)
        return future

    def stop(self) -> None:
        self._loop.call_soon_threadsafe(self._loop.stop)
        self._thread.join(timeout=5.0)
