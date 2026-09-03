import asyncio
import threading
import time

from osm_ride_linux.util.async_bridge import AsyncBridge


def test_submit_runs_on_a_background_thread_and_marshals_result():
    bridge = AsyncBridge()
    main_thread = threading.current_thread()
    results = []

    async def work():
        await asyncio.sleep(0.02)
        return 42, threading.current_thread()

    bridge.submit(work(), on_done=lambda r: results.append(r))
    time.sleep(0.2)

    assert len(results) == 1
    value, worker_thread = results[0]
    assert value == 42
    assert worker_thread is not main_thread
    bridge.stop()


def test_submit_reports_exceptions_via_on_error():
    bridge = AsyncBridge()
    errors = []

    async def failing():
        raise ValueError("boom")

    bridge.submit(failing(), on_error=lambda e: errors.append(e))
    time.sleep(0.2)

    assert len(errors) == 1
    assert isinstance(errors[0], ValueError)
    bridge.stop()


def test_marshal_callback_is_used_for_both_done_and_error():
    bridge = AsyncBridge()
    dispatched = []

    def fake_marshal(fn):
        dispatched.append("marshaled")
        fn()

    async def work():
        return "ok"

    bridge.submit(work(), on_done=lambda r: None, marshal=fake_marshal)
    time.sleep(0.2)

    assert dispatched == ["marshaled"]
    bridge.stop()


def test_cancelling_the_returned_future_interrupts_a_running_coroutine():
    bridge = AsyncBridge()
    events = []
    on_done_calls = []
    on_error_calls = []

    async def long_running():
        try:
            await asyncio.sleep(5.0)
            events.append("completed normally")
        except asyncio.CancelledError:
            events.append("cancelled mid-sleep")
            raise

    future = bridge.submit(
        long_running(), on_done=lambda r: on_done_calls.append(r), on_error=lambda e: on_error_calls.append(e)
    )
    time.sleep(0.1)  # let it actually reach the sleep
    future.cancel()
    time.sleep(0.2)  # let cancellation propagate

    assert events == ["cancelled mid-sleep"]
    assert on_done_calls == []
    assert on_error_calls == []  # an intentional cancel isn't a failure
    bridge.stop()
