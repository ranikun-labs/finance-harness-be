import { execFileSync } from 'node:child_process';
import { strict as assert } from 'node:assert';
import { randomBytes, randomUUID } from 'node:crypto';
import { appendFile } from 'node:fs/promises';

// Synthetic-only verifier. No seed rows: every account is created through the public HTTP surface.
// Only statuses, booleans, counts and the journal Location header are printed; tokens, passwords, emails
// and account ids are never logged.

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ISSUER_CARELOG_REFERENCE = 'urn:ranikunlabs:identity:finance:reference';

let stage = 'initialization';
const observed = {};

function jwtClaims(token) {
  const parts = token.split('.');
  assert.equal(parts.length, 3);
  return JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
}

async function send(method, url, { headers = {}, body } = {}) {
  const init = { method, headers: { accept: 'application/json', ...headers } };
  if (body !== undefined) {
    init.headers['content-type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  const response = await fetch(url, init);
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  return { status: response.status, headers: response.headers, json };
}

function expectStatus(result, expected, label) {
  if (result.status !== expected) {
    stage = `${label} HTTP ${result.status}`;
    assert.fail(`${label}: expected ${expected}, got ${result.status}`);
  }
  stage = label;
}

function sqlCount(database, sql) {
  return execFileSync(
    'psql',
    [
      '-h', process.env.PGHOST ?? '127.0.0.1',
      '-p', process.env.PGPORT ?? '5432',
      '-U', process.env.PGUSER ?? 'postgres',
      '-d', database,
      '-At', '-F', ',',
      '-v', 'ON_ERROR_STOP=1',
      '-c', sql,
    ],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  ).trim();
}

const ENROLLMENT_DISCLOSURE = 'CARELOG_ENROLLMENT_TARGET=stub (real carelog-be enrollment NOT verified)';

async function main() {
  const base = process.env.E2E_GATEWAY_URL;
  const stubBase = process.env.E2E_CARELOG_STUB_URL;
  assert.ok(base && stubBase, 'gateway and Carelog stub URLs are required');
  assert.equal(process.env.CARELOG_ENROLLMENT_TARGET, 'stub', 'CARELOG_ENROLLMENT_TARGET must be stub');
  console.log(ENROLLMENT_DISCLOSURE);

  const runId = randomBytes(6).toString('hex');
  const spoofedContext = {
    'X-Finance-Context-Version': 'spoofed-version',
    'X-Finance-Service-Id': 'browser-attacker',
    'X-Finance-Service-Secret': 'browser-attacker-proof',
    'X-Finance-Audience': 'browser-attacker-audience',
    'X-Finance-Subject': 'browser-attacker-subject',
  };

  // 1. Carelog: user creation without seed SQL, then login regression through Gateway.
  const carelog = {
    userId: `carelog-e2e-${runId}`,
    email: `carelog-e2e-${runId}@example.test`,
    password: 'CarelogE2E-pass1',
    name: 'Synthetic Carelog Manager',
  };

  stage = 'Carelog registration through Gateway';
  const registration = await send('POST', `${base}/api/v1/auth/carelog/registration`, {
    headers: { 'idempotency-key': randomUUID() },
    body: carelog,
  });
  expectStatus(registration, 201, 'Carelog registration through Gateway');
  observed.carelogRegistration = registration.status;

  stage = 'Carelog enrollment stub counters';
  const counters = await fetch(`${stubBase}/__e2e/counters`).then((r) => r.json());
  assert.equal(counters.enrollments, 1);
  observed.carelogEnrollmentsReceived = counters.enrollments;

  stage = 'Carelog login through Gateway';
  const carelogLogin = await send('POST', `${base}/api/v1/auth/login`, {
    body: { userId: carelog.userId, password: carelog.password },
  });
  expectStatus(carelogLogin, 200, 'Carelog login through Gateway');
  assert.equal(typeof carelogLogin.json?.data?.accessToken, 'string');
  assert.equal(typeof carelogLogin.json?.data?.refreshToken, 'string');
  // Carelog tokens must not be minted by the Finance reference issuer.
  assert.notEqual(jwtClaims(carelogLogin.json.data.accessToken).iss, ISSUER_CARELOG_REFERENCE);
  observed.carelogLogin = carelogLogin.status;

  stage = 'Carelog wrong-password login through Gateway';
  const carelogWrong = await send('POST', `${base}/api/v1/auth/login`, {
    body: { userId: carelog.userId, password: 'WrongPassword-9' },
  });
  expectStatus(carelogWrong, 401, 'Carelog wrong-password login through Gateway');
  observed.carelogWrongPassword = carelogWrong.status;

  // 2. Finance reference: signup A and B, login each, A journals, B isolation.
  const accounts = {
    A: { email: `finance-e2e-a-${runId}@example.test`, password: 'FinanceE2E-pass1' },
    B: { email: `finance-e2e-b-${runId}@example.test`, password: 'FinanceE2E-pass2' },
  };

  for (const [label, account] of Object.entries(accounts)) {
    stage = `Finance signup ${label} through Gateway`;
    const signup = await send('POST', `${base}/api/v1/auth/finance-reference/signup`, {
      body: { email: account.email, password: account.password },
    });
    expectStatus(signup, 201, `Finance signup ${label} through Gateway`);
    assert.match(signup.json?.data?.accountId ?? '', UUID_RE);
    account.accountId = signup.json.data.accountId;
    observed[`signup${label}`] = signup.status;
  }
  assert.notEqual(accounts.A.accountId, accounts.B.accountId);

  for (const [label, account] of Object.entries(accounts)) {
    stage = `Finance login ${label} through Gateway`;
    const login = await send('POST', `${base}/api/v1/auth/finance-reference/login`, {
      body: { userId: account.email, password: account.password },
    });
    expectStatus(login, 200, `Finance login ${label} through Gateway`);
    assert.deepEqual(Object.keys(login.json.data).sort(), ['accessToken', 'expiresAt', 'tokenType']);
    assert.equal(login.json.data.tokenType, 'Bearer');
    const claims = jwtClaims(login.json.data.accessToken);
    assert.equal(claims.sub, account.accountId);
    assert.equal(claims.iss, ISSUER_CARELOG_REFERENCE);
    account.token = login.json.data.accessToken;
    observed[`login${label}`] = login.status;
  }

  const authA = { authorization: `Bearer ${accounts.A.token}`, ...spoofedContext };
  const authB = { authorization: `Bearer ${accounts.B.token}`, ...spoofedContext };

  const journal = {
    type: 'investment',
    assetName: 'Carelog-Finance dual-auth synthetic investment',
    occurredAt: '2026-11-18T09:31:00.000',
    timeZone: 'Asia/Seoul',
    action: 'interest',
    reasoning: 'Synthetic dual-auth E2E; no product or customer data.',
  };

  stage = 'Finance journal create by A through Gateway';
  const created = await send('POST', `${base}/api/finance/journals`, {
    headers: { ...authA, 'idempotency-key': `carelog-finance-e2e-${randomUUID()}` },
    body: journal,
  });
  expectStatus(created, 201, 'Finance journal create by A through Gateway');
  assert.match(created.json?.journalId ?? '', UUID_RE);
  const journalId = created.json.journalId;
  // Location is logged as the actual header value. Upstream Finance answers /finance/journals/<id>.
  const location = created.headers.get('location') ?? '';
  assert.ok(location.endsWith(`/finance/journals/${journalId}`), 'Location must end with /finance/journals/<id>');
  observed.journalLocation = location;
  observed.journalCreate = created.status;

  stage = 'Finance journal detail read by A through Gateway';
  const detailA = await send('GET', `${base}/api/finance/journals/${journalId}`, { headers: authA });
  expectStatus(detailA, 200, 'Finance journal detail read by A through Gateway');
  assert.equal(detailA.json.journalId, journalId);
  assert.equal(detailA.json.type, journal.type);
  assert.equal(detailA.json.assetName, journal.assetName);
  assert.equal(detailA.json.action, journal.action);
  assert.equal(detailA.json.reasoning, journal.reasoning);
  assert.equal(detailA.json.occurredAt, journal.occurredAt);
  assert.equal(detailA.json.timeZone, journal.timeZone);
  assert.equal(detailA.json.revision, 0);
  observed.journalDetailA = detailA.status;

  stage = 'Finance journal list read by A through Gateway';
  const listA = await send('GET', `${base}/api/finance/journals?limit=20`, { headers: authA });
  expectStatus(listA, 200, 'Finance journal list read by A through Gateway');
  const itemA = listA.json.items.find((item) => item.journalId === journalId);
  assert.ok(itemA, 'A list must contain the journal A created');
  assert.equal(itemA.assetName, journal.assetName);
  assert.equal(itemA.action, journal.action);
  observed.journalListA = listA.status;

  stage = 'Finance journal detail read by B through Gateway';
  const detailB = await send('GET', `${base}/api/finance/journals/${journalId}`, { headers: authB });
  assert.ok([403, 404].includes(detailB.status), `B detail read expected 403 or 404, got ${detailB.status}`);
  observed.journalDetailB = detailB.status;
  // Record the body shape without the content: whether the response leaks the journal.
  observed.journalDetailBLeaksContent = JSON.stringify(detailB.json ?? {}).includes(journal.assetName);
  assert.equal(observed.journalDetailBLeaksContent, false);

  stage = 'Finance journal list read by B through Gateway';
  const listB = await send('GET', `${base}/api/finance/journals?limit=20`, { headers: authB });
  expectStatus(listB, 200, 'Finance journal list read by B through Gateway');
  assert.ok(!listB.json.items.some((item) => item.journalId === journalId), 'B list must not contain A journal');
  observed.journalListB = listB.status;
  observed.journalListBCount = listB.json.items.length;

  stage = 'Finance ownership database check';
  const ownership = sqlCount(
    'finance_db',
    `SELECT
       (SELECT count(*) FROM journals WHERE id = '${journalId}' AND identity_user_id = '${accounts.A.accountId}'),
       (SELECT count(*) FROM journals WHERE id = '${journalId}' AND identity_user_id <> '${accounts.A.accountId}')`,
  );
  assert.equal(ownership, '1,0');
  observed.dbOwnerMatchesA = ownership;

  // 3. Cross-check: record behaviour only, no assertion on the outcome.
  stage = 'Cross-check Finance-signup credentials on Carelog login';
  const crossCarelog = await send('POST', `${base}/api/v1/auth/login`, {
    body: { userId: accounts.A.email, password: accounts.A.password },
  });
  observed.crossFinanceAccountOnCarelogLogin = crossCarelog.status;
  observed.crossFinanceAccountOnCarelogLoginHasToken =
    typeof crossCarelog.json?.data?.accessToken === 'string';

  stage = 'Cross-check Carelog credentials on Finance reference login';
  const crossFinance = await send('POST', `${base}/api/v1/auth/finance-reference/login`, {
    body: { userId: carelog.userId, password: carelog.password },
  });
  observed.crossCarelogAccountOnFinanceLogin = crossFinance.status;

  const summary = [
    'Carelog x Finance dual-auth E2E: PASS (no seed fixture; both auth surfaces enabled in one Core)',
    ENROLLMENT_DISCLOSURE,
    'carelog: registration:201 enrollment_stub_received:1 login:200 wrong_password:401',
    `finance: signup_A:${observed.signupA} signup_B:${observed.signupB} login_A:${observed.loginA} login_B:${observed.loginB}`,
    `finance: create:201 location=${observed.journalLocation} detail_A:${observed.journalDetailA} list_A:${observed.journalListA}`,
    `finance: detail_B:${observed.journalDetailB} (expected 403 or 404) list_B:${observed.journalListB} list_B_items:${observed.journalListBCount}`,
    `finance: db_owner_matches_A(owner_rows,foreign_rows)=${observed.dbOwnerMatchesA}`,
    `cross(record-only): finance_account_on_carelog_login=${observed.crossFinanceAccountOnCarelogLogin} carelog_account_on_finance_login=${observed.crossCarelogAccountOnFinanceLogin}`,
  ].join('\n');
  console.log(summary);
  if (process.env.GITHUB_STEP_SUMMARY) {
    await appendFile(process.env.GITHUB_STEP_SUMMARY, `${summary}\n`);
  }
}

main().catch((error) => {
  console.error(`Carelog x Finance dual-auth E2E failed at stage=${stage}`);
  if (process.env.E2E_DEBUG_ASSERTIONS === '1') {
    console.error(error?.message ?? error);
  }
  process.exitCode = 1;
});
