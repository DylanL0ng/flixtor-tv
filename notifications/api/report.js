import { createHash } from 'node:crypto';

const operations = new Set(['HOME', 'MOVIES', 'SHOWS', 'SEARCH', 'LIST', 'ACCOUNT', 'DETAIL', 'PLAYER']);
const keys = ['category', 'commit', 'fingerprint', 'operation', 'versionCode'];

export function validateReport(body) {
  if (!body || Array.isArray(body) || typeof body !== 'object') return null;
  if (Object.keys(body).sort().join(',') !== keys.join(',')) return null;
  if (!Number.isSafeInteger(body.versionCode) || body.versionCode < 1) return null;
  if (typeof body.commit !== 'string' || !/^(local|[a-f0-9]{7,40})$/.test(body.commit)) return null;
  if (!operations.has(body.operation) || body.category !== 'CHANGED') return null;
  if (body.fingerprint !== `${body.operation.toLowerCase()}.response-v1`) return null;
  return { versionCode: body.versionCode, commit: body.commit, operation: body.operation,
    fingerprint: body.fingerprint, category: body.category };
}

export function makeEmail(body, env, now = Date.now()) {
  const day = Math.floor(now / 86400000);
  const serialized = JSON.stringify(body);
  return {
    key: createHash('sha256').update(`${day}:${serialized}`).digest('hex'),
    message: { from: env.REPORT_SENDER, to: [env.REPORT_RECIPIENT],
      subject: `FlixTor TV: service structure changed (${body.operation})`,
      text: `A user pressed Notify Dylan after an unexpected service response.\n\nVersion code: ${body.versionCode}\nCommit: ${body.commit}\nOperation: ${body.operation}\nFingerprint: ${body.fingerprint}\nCategory: ${body.category}\n\nNo account information, viewing history, raw responses, or stream URLs are included.` }
  };
}

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'POST') { res.setHeader('Allow', 'POST'); return res.status(405).json({ error: 'method' }); }
  if (!process.env.RESEND_API_KEY || !process.env.REPORT_SENDER || !process.env.REPORT_RECIPIENT) {
    return res.status(503).json({ error: 'unconfigured' });
  }
  const length = Number(req.headers['content-length']);
  if (length > 2048 || JSON.stringify(req.body ?? '').length > 2048) return res.status(413).json({ error: 'size' });
  const report = validateReport(req.body);
  if (!report) return res.status(400).json({ error: 'invalid' });
  const email = makeEmail(report, process.env);
  try {
    const response = await fetch('https://api.resend.com/emails', {
      method: 'POST', headers: { Authorization: `Bearer ${process.env.RESEND_API_KEY}`,
        'Content-Type': 'application/json', 'Idempotency-Key': email.key },
      body: JSON.stringify(email.message), signal: AbortSignal.timeout(10000)
    });
    if (!response.ok) return res.status(502).json({ error: 'delivery' });
    return res.status(202).json({ accepted: true });
  } catch { return res.status(502).json({ error: 'delivery' }); }
}
