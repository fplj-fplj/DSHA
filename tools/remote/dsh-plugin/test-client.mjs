import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createClient } from './client.js';

async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'dsha-phone-refusal-'));
  try {
    const ipcPath = join(directory, 'ipc.json');
    await writeFile(ipcPath, JSON.stringify({ v: 1, url: 'http://127.0.0.1:41973/', token: 'a'.repeat(64) }), { mode: 0o600 });
    await run(ipcPath);
  } finally {
    assert.ok(resolve(directory).startsWith(resolve(tmpdir(), 'dsha-phone-refusal-')));
    await rm(directory, { recursive: true, force: true });
  }
}

test('native overlay refusal preserves the code and actionable guidance without inventing an unknown dispatch', () => fixture(async ipcPath => {
  let calls = 0;
  const request = createClient({ ipcPath, fetcher: async (_url, options) => {
    calls++;
    const incoming = JSON.parse(options.body);
    return Response.json({ v: 1, requestId: incoming.requestId, ok: false, error: {
      code: 'ASSISTANT_OVERLAY_PERMISSION_REQUIRED',
      guidance: 'Android Settings → Apps → Special app access → Display over other apps → DSHA. Return to this conversation and explicitly continue.',
      automaticRetry: false,
    } });
  } });
  await assert.rejects(request('task.begin', { taskSessionId: 'full-session', turnId: 'full-session:7' }), error => {
    assert.match(error.message, /ASSISTANT_OVERLAY_PERMISSION_REQUIRED/);
    assert.match(error.message, /Display over other apps/);
    assert.ok(!error.message.includes('结果未知'));
    return true;
  });
  assert.equal(calls, 1, 'a permission refusal is never retried');
}));

test('transport loss stays unknown and is never replayed', () => fixture(async ipcPath => {
  let calls = 0;
  const request = createClient({ ipcPath, fetcher: async () => { calls++; throw Error('connection lost'); } });
  await assert.rejects(request('screen.tap', { taskSessionId: 'full-session', turnId: 'full-session:7' }), /结果未知；不要自动重放/);
  assert.equal(calls, 1);
}));
