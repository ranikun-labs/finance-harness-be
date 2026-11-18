import { execFileSync } from 'node:child_process';
import { strict as assert } from 'node:assert';
import { randomUUID } from 'node:crypto';
import { appendFile } from 'node:fs/promises';

let stage = 'initialization';

function expectStatus(response, expected, label) {
  if (response.status !== expected) {
    stage = `${label} HTTP ${response.status}`;
    assert.equal(response.status, expected);
  }
  stage = label;
}

async function main() {
  const base = process.env.E2E_GATEWAY_URL;
  const expectedSubject = process.env.E2E_EXPECTED_SUBJECT;
  const userId = process.env.FINANCE_REFERENCE_E2E_USER;
  const password = process.env.FINANCE_REFERENCE_E2E_PASSWORD;
  assert.ok(base && expectedSubject && userId && password);

  const spoofedContext = {
    'X-Finance-Context-Version': 'spoofed-version',
    'X-Finance-Service-Id': 'browser-attacker',
    'X-Finance-Service-Secret': 'browser-attacker-proof',
    'X-Finance-Audience': 'browser-attacker-audience',
    'X-Finance-Subject': 'browser-attacker-subject',
  };

  stage = 'Core reference login through Gateway';
  const loginResponse = await fetch(`${base}/api/v1/auth/finance-reference/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify({ userId, password }),
  });
  expectStatus(loginResponse, 200, 'Core reference login through Gateway');
  assert.match(loginResponse.headers.get('cache-control') ?? '', /no-store/i);
  assert.match(loginResponse.headers.get('pragma') ?? '', /no-cache/i);
  assert.equal(loginResponse.headers.get('set-cookie'), null);
  const envelope = await loginResponse.json();
  assert.ok(envelope && typeof envelope.data === 'object');
  const tokenRecord = envelope.data;
  assert.deepEqual(Object.keys(tokenRecord).sort(), ['accessToken', 'expiresAt', 'tokenType']);
  assert.equal(tokenRecord.tokenType, 'Bearer');
  assert.equal(typeof tokenRecord.accessToken, 'string');
  assert.equal(typeof tokenRecord.expiresAt, 'string');
  assert.ok(tokenRecord.expiresAt.endsWith('Z'));

  const tokenParts = tokenRecord.accessToken.split('.');
  assert.equal(tokenParts.length, 3);
  const claims = JSON.parse(Buffer.from(tokenParts[1], 'base64url').toString('utf8'));
  assert.equal(claims.sub, expectedSubject);
  assert.equal(claims.iss, 'urn:ranikunlabs:identity:finance:reference');
  assert.equal(claims.token_use, 'access');
  assert.ok(
    claims.aud === 'finance-journal-reference' ||
      (Array.isArray(claims.aud) && claims.aud.includes('finance-journal-reference')),
  );
  assert.ok(Date.parse(tokenRecord.expiresAt) > Date.now());

  stage = 'Finance investment create through Gateway';
  const idempotencyKey = `hosted-finance-e2e-${randomUUID()}`;
  const createResponse = await fetch(`${base}/finance/journals`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${tokenRecord.accessToken}`,
      'content-type': 'application/json',
      'idempotency-key': idempotencyKey,
      ...spoofedContext,
    },
    body: JSON.stringify({
      type: 'investment',
      assetName: 'Hosted synthetic investment',
      occurredAt: '2026-11-18T09:31:00.000',
      timeZone: 'Asia/Seoul',
      action: 'interest',
      reasoning: 'Synthetic integration flow; no product or customer data.',
    }),
  });
  expectStatus(createResponse, 201, 'Finance investment create through Gateway');
  const created = await createResponse.json();
  assert.match(created.journalId ?? '', /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
  const journalId = created.journalId;
  assert.equal(createResponse.headers.get('location'), `/finance/journals/${journalId}`);

  const readHeaders = { authorization: `Bearer ${tokenRecord.accessToken}`, ...spoofedContext };
  stage = 'Finance investment detail read through Gateway';
  const detailResponse = await fetch(`${base}/finance/journals/${journalId}`, { headers: readHeaders });
  expectStatus(detailResponse, 200, 'Finance investment detail read through Gateway');
  const detail = await detailResponse.json();
  assert.equal(detail.journalId, journalId);
  assert.equal(detail.type, 'investment');
  assert.equal(detail.assetName, 'Hosted synthetic investment');
  assert.equal(detail.action, 'interest');
  assert.equal(detail.reasoning, 'Synthetic integration flow; no product or customer data.');
  assert.equal(detail.occurredAt, '2026-11-18T09:31:00.000');
  assert.equal(detail.timeZone, 'Asia/Seoul');

  stage = 'Finance investment list read through Gateway';
  const listResponse = await fetch(`${base}/finance/journals?limit=20`, { headers: readHeaders });
  expectStatus(listResponse, 200, 'Finance investment list read through Gateway');
  const list = await listResponse.json();
  assert.ok(Array.isArray(list.items) && list.items.some((item) => item.journalId === journalId));

  stage = 'Finance database ownership and persistence check';
  const ownerCounts = execFileSync(
    'psql',
    [
      '-h',
      process.env.PGHOST ?? '127.0.0.1',
      '-p',
      process.env.PGPORT ?? '5432',
      '-U',
      process.env.PGUSER ?? 'postgres',
      '-d',
      'finance_db',
      '-At',
      '-F',
      ',',
      '-v',
      'ON_ERROR_STOP=1',
      '-c',
      `
        SELECT
          (SELECT count(*) FROM journals WHERE id = '${journalId}' AND identity_user_id = '${expectedSubject}' AND type = 'investment'),
          (SELECT count(*) FROM investment_journals WHERE journal_id = '${journalId}'),
          (SELECT count(*) FROM journal_idempotency_records WHERE journal_id = '${journalId}' AND identity_user_id = '${expectedSubject}'),
          (SELECT count(*) FROM journals WHERE id = '${journalId}' AND identity_user_id <> '${expectedSubject}')
      `,
    ],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  ).trim();
  assert.equal(ownerCounts, '1,1,1,0');

  const summary = [
    'Finance composite API E2E: PASS',
    'flow=Core reference login -> Gateway -> Finance create/detail/list',
    'source trees: Finance=d598dadb2677b2ac73488b6ab34f6e278aab009c Platform=9057914850b9e0026fa8851cbb062c692a51b4b3',
    'statuses=login:200,create:201,detail:200,list:200',
    'persisted_counts=journal:1,investment:1,idempotency:1,foreign_owner:0',
    'spoofed_client_context_headers=5; finance_owner=verified_Core_subject',
  ].join('\n');
  console.log(summary);
  if (process.env.GITHUB_STEP_SUMMARY) {
    await appendFile(process.env.GITHUB_STEP_SUMMARY, `${summary}\n`);
  }
}

main().catch(() => {
  console.error(`Finance composite API E2E failed at stage=${stage}`);
  process.exitCode = 1;
});
