"""Execute real hook code with a synthetic bridge. No device or business requests."""
import ast
import asyncio
import json
from pathlib import Path
import threading
import time
import types
import unittest


def hooks(bridge):
    tree = ast.parse((Path(__file__).resolve().parents[1] / 'src/main/assets/embedded/engine.py').read_text())
    cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == 'Capture')
    names = {'response', 'request'}
    nodes = [n for n in tree.body if isinstance(n, ast.AsyncFunctionDef) and n.name == 'bridge_async']
    nodes += [n for n in cls.body if isinstance(n, ast.AsyncFunctionDef) and n.name in names]
    ns = dict(asyncio=asyncio, bridge=bridge, BRIDGE_SLOTS=None, record=lambda flow: {}, encode=lambda _: '',
              flow_record=lambda flow,decode=False: {}, report_drop=lambda error:None, CONFIG={'isolated': False}, final_request=lambda _: True, json=json,
              mitm_http=types.SimpleNamespace(Response=types.SimpleNamespace(make=lambda code, body, *a: (code, body))))
    exec(compile(ast.fix_missing_locations(ast.Module(body=nodes, type_ignores=[])), 'engine-hooks', 'exec'), ns)
    return ns


class AsyncEngineTest(unittest.IsolatedAsyncioTestCase):
    async def test_slow_bridge_does_not_block_event_loop(self):
        entered, release = threading.Event(), threading.Event()
        def slow(path, data):
            entered.set(); release.wait(3); return {'changed': False}
        ns = hooks(slow)
        flow = types.SimpleNamespace(metadata={}, response=types.SimpleNamespace(get_content=lambda strict: b''))
        task = asyncio.create_task(ns['response'](None, flow))
        try:
            for _ in range(100):
                if entered.is_set(): break
                await asyncio.sleep(.005)
            self.assertTrue(entered.is_set())
            self.assertFalse(task.done(), 'Response must still wait for persisted evidence')
            await asyncio.wait_for(asyncio.sleep(.02), .3)
        finally:
            release.set(); await task

    async def test_failed_final_save_still_blocks_forwarding(self):
        def failed(path, data): raise OSError('synthetic disk failure')
        ns = hooks(failed)
        flow = types.SimpleNamespace(metadata={})
        await ns['request'](None, flow)
        self.assertTrue(flow.metadata['final_blocked'])
        self.assertEqual(409, flow.response[0])
        self.assertFalse(json.loads(flow.response[1])['finalRequestForwarded'])

    async def test_concurrency_is_bounded(self):
        lock = threading.Lock()
        active = peak = 0
        def slow(path, data):
            nonlocal active, peak
            with lock: active += 1; peak = max(peak, active)
            time.sleep(.03)
            with lock: active -= 1
            return {}
        ns = hooks(slow)
        results=await asyncio.gather(*(ns['bridge_async']('/event', {}) for _ in range(8)),return_exceptions=True)
        self.assertEqual(2, peak)
        self.assertEqual(6,sum(isinstance(result,RuntimeError) for result in results))

    async def test_saturation_rejects_before_materializing_body(self):
        release = threading.Event()
        ns = hooks(lambda path,data: (release.wait(3), {})[1])
        tasks=[asyncio.create_task(ns['bridge_async']('/response', lambda: {})) for _ in range(2)]
        try:
            await asyncio.sleep(.03)
            expanded=[]
            with self.assertRaises(RuntimeError):
                await ns['bridge_async']('/response', lambda: expanded.append(True))
            self.assertEqual([],expanded)
            tasks[0].cancel()
            with self.assertRaises(asyncio.CancelledError): await tasks[0]
            with self.assertRaises(RuntimeError):
                await ns['bridge_async']('/response', lambda: expanded.append(True))
        finally:
            release.set(); await asyncio.gather(*tasks,return_exceptions=True); await asyncio.sleep(.03)
        await ns['bridge_async']('/response',lambda:{})

    async def test_final_request_already_blocked_when_cancelled(self):
        entered, release = threading.Event(), threading.Event()
        def slow(path, data): entered.set(); release.wait(3); return {}
        ns = hooks(slow)
        flow = types.SimpleNamespace(metadata={})
        task = asyncio.create_task(ns['request'](None, flow))
        try:
            for _ in range(100):
                if entered.is_set(): break
                await asyncio.sleep(.005)
            self.assertEqual(409, flow.response[0])
            task.cancel()
            with self.assertRaises(asyncio.CancelledError): await task
            self.assertTrue(flow.metadata['final_blocked'])
        finally:
            release.set()
            await asyncio.sleep(.05)
