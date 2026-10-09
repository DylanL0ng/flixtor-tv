import test from 'node:test';
import assert from 'node:assert/strict';
import handler, { validateReport, makeEmail } from './api/report.js';

const sample = { versionCode: 2, commit: 'local', operation: 'PLAYER', fingerprint: 'player.response-v1', category: 'CHANGED' };
test('accepts bounded structural reports; rejects extra/private fields', () => {
  assert.deepEqual(validateReport(sample), sample);
  for (const body of [{ ...sample, cookie: 'secret' }, { ...sample, operation: 'https://signed.url' },
    { ...sample, fingerprint: 'token=secret' }, { ...sample, commit: '<html>' }, { ...sample, versionCode: -1 }]) {
    assert.equal(validateReport(body), null);
  }
});
test('same report deduplicates within day; recipient is only server configuration', () => {
  const env = { REPORT_SENDER: 'TV <tv@example.org>', REPORT_RECIPIENT: 'owner@example.org' };
  const first = makeEmail(sample, env, 1000);
  assert.equal(first.key, makeEmail(sample, env, 2000).key);
  assert.notEqual(first.key, makeEmail(sample, env, 86401000).key);
  assert.deepEqual(first.message.to, ['owner@example.org']);
  assert.ok(!first.message.text.includes('secret'));
});
test('missing deployment configuration never claims delivery', async () => {
  const original = process.env.RESEND_API_KEY;
  delete process.env.RESEND_API_KEY;
  let status;
  const res = { setHeader() {}, status(value) { status = value; return this; }, json() {} };
  try { await handler({ method: 'POST', headers: {}, body: sample }, res); assert.equal(status, 503); }
  finally { if (original !== undefined) process.env.RESEND_API_KEY = original; }
});
test('provider rejection and timeout fail; acceptance returns 202 only afterward', async () => {
  const original = { key: process.env.RESEND_API_KEY, sender: process.env.REPORT_SENDER,
    recipient: process.env.REPORT_RECIPIENT, fetch: globalThis.fetch };
  Object.assign(process.env, { RESEND_API_KEY: 'test-only', REPORT_SENDER: 'tv@example.org', REPORT_RECIPIENT: 'owner@example.org' });
  let status;
  const res = { setHeader() {}, status(value) { status = value; return this; }, json() {} };
  try {
    globalThis.fetch = async () => ({ ok: false });
    await handler({ method: 'POST', headers: {}, body: sample }, res);
    assert.equal(status, 502);
    globalThis.fetch = async () => { throw new Error('timeout'); };
    await handler({ method: 'POST', headers: {}, body: sample }, res);
    assert.equal(status, 502);
    globalThis.fetch = async (url, request) => {
      assert.equal(url, 'https://api.resend.com/emails');
      assert.deepEqual(JSON.parse(request.body).to, ['owner@example.org']);
      assert.match(request.headers['Idempotency-Key'], /^[a-f0-9]{64}$/);
      return { ok: true };
    };
    await handler({ method: 'POST', headers: {}, body: sample }, res);
    assert.equal(status, 202);
  } finally {
    for (const [name, value] of [['RESEND_API_KEY', original.key], ['REPORT_SENDER', original.sender], ['REPORT_RECIPIENT', original.recipient]]) {
      if (value === undefined) delete process.env[name]; else process.env[name] = value;
    }
    globalThis.fetch = original.fetch;
  }
});
