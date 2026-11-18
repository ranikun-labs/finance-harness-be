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
  const originalPayload = {
    type: 'investment',
    assetName: 'Hosted synthetic investment',
    occurredAt: '2026-11-18T09:31:00.000',
    timeZone: 'Asia/Seoul',
    action: 'interest',
    reasoning: 'Synthetic integration flow; no product or customer data.',
  };
  const createResponse = await fetch(`${base}/finance/journals`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${tokenRecord.accessToken}`,
      'content-type': 'application/json',
      'idempotency-key': idempotencyKey,
      ...spoofedContext,
    },
    body: JSON.stringify(originalPayload),
  });
  expectStatus(createResponse, 201, 'Finance investment create through Gateway');
  const created = await createResponse.json();
  assert.match(created.journalId ?? '', /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
  const journalId = created.journalId;
  assert.equal(createResponse.headers.get('location'), `/finance/journals/${journalId}`);

  const readHeaders = { authorization: `Bearer ${tokenRecord.accessToken}`, ...spoofedContext };
  stage = 'Finance investment detail read through Gateway';
  const detailResponse = await fetch(`${base}/finance/journals/${journalId}`, { headers: readHeaders });
  expectStatus(detailResponse, 200, 'Finance investment initial detail read through Gateway');
  const initialDetail = await detailResponse.json();
  assert.equal(initialDetail.journalId, journalId);
  assert.equal(initialDetail.type, 'investment');
  assert.equal(initialDetail.assetName, 'Hosted synthetic investment');
  assert.equal(initialDetail.action, 'interest');
  assert.equal(initialDetail.reasoning, 'Synthetic integration flow; no product or customer data.');
  assert.equal(initialDetail.revision, 0);
  assert.equal(initialDetail.occurredAt, '2026-11-18T09:31:00.000');
  assert.equal(initialDetail.timeZone, 'Asia/Seoul');

  stage = 'Finance investment initial list read through Gateway';
  const listResponse = await fetch(`${base}/finance/journals?limit=20`, { headers: readHeaders });
  expectStatus(listResponse, 200, 'Finance investment initial list read through Gateway');
  const initialList = await listResponse.json();
  assert.ok(Array.isArray(initialList.items) && initialList.items.some((item) => item.journalId === journalId));

  const editedFields = {
    assetName: 'Hosted synthetic investment revised',
    action: 'watching',
    reasoning: 'Synthetic integration flow; revised on the hosted runner.',
    emotion: '관망',
  };
  stage = 'Finance investment edit through Gateway';
  const updateResponse = await fetch(`${base}/finance/journals/${journalId}`, {
    method: 'PATCH',
    headers: { ...readHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ expectedRevision: 0, fields: editedFields }),
  });
  expectStatus(updateResponse, 200, 'Finance investment edit through Gateway');
  const updated = await updateResponse.json();
  assert.deepEqual(Object.keys(updated).sort(), ['journalId', 'revision']);
  assert.equal(updated.journalId, journalId);
  assert.equal(updated.revision, 1);

  stage = 'Finance investment edited detail read through Gateway';
  const editedDetailResponse = await fetch(`${base}/finance/journals/${journalId}`, { headers: readHeaders });
  expectStatus(editedDetailResponse, 200, 'Finance investment edited detail read through Gateway');
  const editedDetail = await editedDetailResponse.json();
  assert.equal(editedDetail.revision, 1);
  assert.equal(editedDetail.assetName, editedFields.assetName);
  assert.equal(editedDetail.action, editedFields.action);
  assert.equal(editedDetail.reasoning, editedFields.reasoning);
  assert.equal(editedDetail.emotion, editedFields.emotion);
  assert.equal(editedDetail.createdAt, initialDetail.createdAt);
  assert.equal(editedDetail.occurredAt, initialDetail.occurredAt);
  assert.equal(editedDetail.timeZone, initialDetail.timeZone);

  stage = 'Finance investment edited list read through Gateway';
  const editedListResponse = await fetch(`${base}/finance/journals?limit=20`, { headers: readHeaders });
  expectStatus(editedListResponse, 200, 'Finance investment edited list read through Gateway');
  const editedList = await editedListResponse.json();
  const editedItem = editedList.items.find((item) => item.journalId === journalId);
  assert.ok(editedItem);
  assert.equal(editedItem.assetName, editedFields.assetName);
  assert.equal(editedItem.action, editedFields.action);

  stage = 'Finance investment delete through Gateway';
  const deleteResponse = await fetch(`${base}/finance/journals/${journalId}/delete`, {
    method: 'POST',
    headers: { ...readHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ expectedRevision: 1 }),
  });
  expectStatus(deleteResponse, 200, 'Finance investment delete through Gateway');
  const deleted = await deleteResponse.json();
  assert.deepEqual(Object.keys(deleted).sort(), ['journalId', 'revision', 'state']);
  assert.equal(deleted.journalId, journalId);
  assert.equal(deleted.revision, 2);
  assert.equal(deleted.state, 'deleted');

  stage = 'Finance deleted detail and active list checks through Gateway';
  const deletedDetailResponse = await fetch(`${base}/finance/journals/${journalId}`, { headers: readHeaders });
  expectStatus(deletedDetailResponse, 404, 'Finance deleted detail read through Gateway');
  const activeListAfterDeleteResponse = await fetch(`${base}/finance/journals?limit=20`, { headers: readHeaders });
  expectStatus(activeListAfterDeleteResponse, 200, 'Finance active list after delete through Gateway');
  const activeListAfterDelete = await activeListAfterDeleteResponse.json();
  assert.ok(!activeListAfterDelete.items.some((item) => item.journalId === journalId));

  stage = 'Finance deleted list read through Gateway';
  const deletedListResponse = await fetch(`${base}/finance/journals/deleted`, { headers: readHeaders });
  expectStatus(deletedListResponse, 200, 'Finance deleted list read through Gateway');
  const deletedList = await deletedListResponse.json();
  assert.deepEqual(Object.keys(deletedList).sort(), ['hasMore', 'items']);
  assert.equal(deletedList.hasMore, false);
  assert.equal(deletedList.items.length, 1);
  const tombstone = deletedList.items[0];
  assert.deepEqual(Object.keys(tombstone).sort(), ['deletedAt', 'journalId', 'revision', 'type']);
  assert.equal(tombstone.journalId, journalId);
  assert.equal(tombstone.type, 'investment');
  assert.equal(tombstone.revision, 2);
  assert.equal(typeof tombstone.deletedAt, 'string');
  const tombstoneJson = JSON.stringify(deletedList);
  assert.ok(!tombstoneJson.includes(editedFields.assetName));
  assert.ok(!tombstoneJson.includes(editedFields.reasoning));

  stage = 'Finance investment restore through Gateway';
  const restoreResponse = await fetch(`${base}/finance/journals/${journalId}/restore`, {
    method: 'POST',
    headers: { ...readHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ expectedRevision: 2 }),
  });
  expectStatus(restoreResponse, 200, 'Finance investment restore through Gateway');
  const restored = await restoreResponse.json();
  assert.deepEqual(Object.keys(restored).sort(), ['journalId', 'revision', 'state']);
  assert.equal(restored.journalId, journalId);
  assert.equal(restored.revision, 3);
  assert.equal(restored.state, 'active');

  stage = 'Finance restored detail, list, and deleted list checks through Gateway';
  const restoredDetailResponse = await fetch(`${base}/finance/journals/${journalId}`, { headers: readHeaders });
  expectStatus(restoredDetailResponse, 200, 'Finance restored detail read through Gateway');
  const restoredDetail = await restoredDetailResponse.json();
  assert.equal(restoredDetail.revision, 3);
  assert.equal(restoredDetail.assetName, editedFields.assetName);
  assert.equal(restoredDetail.action, editedFields.action);
  assert.equal(restoredDetail.reasoning, editedFields.reasoning);
  assert.equal(restoredDetail.emotion, editedFields.emotion);
  assert.equal(restoredDetail.createdAt, initialDetail.createdAt);
  assert.equal(restoredDetail.occurredAt, initialDetail.occurredAt);
  assert.equal(restoredDetail.timeZone, initialDetail.timeZone);
  const restoredListResponse = await fetch(`${base}/finance/journals?limit=20`, { headers: readHeaders });
  expectStatus(restoredListResponse, 200, 'Finance restored list read through Gateway');
  const restoredList = await restoredListResponse.json();
  const restoredItem = restoredList.items.find((item) => item.journalId === journalId);
  assert.ok(restoredItem);
  assert.equal(restoredItem.assetName, editedFields.assetName);
  assert.equal(restoredItem.action, editedFields.action);
  const deletedListAfterRestoreResponse = await fetch(`${base}/finance/journals/deleted`, { headers: readHeaders });
  expectStatus(deletedListAfterRestoreResponse, 200, 'Finance deleted list after restore through Gateway');
  const deletedListAfterRestore = await deletedListAfterRestoreResponse.json();
  assert.ok(!deletedListAfterRestore.items.some((item) => item.journalId === journalId));

  stage = 'Finance original create replay after restore through Gateway';
  const replayResponse = await fetch(`${base}/finance/journals`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${tokenRecord.accessToken}`,
      'content-type': 'application/json',
      'idempotency-key': idempotencyKey,
      ...spoofedContext,
    },
    body: JSON.stringify(originalPayload),
  });
  expectStatus(replayResponse, 201, 'Finance original create replay after restore through Gateway');
  const replayed = await replayResponse.json();
  assert.deepEqual(replayed, created);
  assert.equal(replayed.journalId, journalId);
  assert.equal(replayResponse.headers.get('location'), `/finance/journals/${journalId}`);
  const replayDetailResponse = await fetch(`${base}/finance/journals/${journalId}`, { headers: readHeaders });
  expectStatus(replayDetailResponse, 200, 'Finance detail after original create replay through Gateway');
  const replayDetail = await replayDetailResponse.json();
  assert.equal(replayDetail.revision, 3);
  assert.equal(replayDetail.assetName, editedFields.assetName);
  assert.equal(replayDetail.action, editedFields.action);
  assert.equal(replayDetail.reasoning, editedFields.reasoning);
  assert.equal(replayDetail.emotion, editedFields.emotion);

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
          (SELECT count(*) FROM investment_journals WHERE journal_id = '${journalId}' AND asset_name = '${editedFields.assetName}' AND action = '${editedFields.action}' AND reasoning = '${editedFields.reasoning}' AND emotion = '${editedFields.emotion}'),
          (SELECT count(*) FROM journal_idempotency_records WHERE journal_id = '${journalId}' AND identity_user_id = '${expectedSubject}'),
          (SELECT count(*) FROM journals WHERE id = '${journalId}' AND identity_user_id <> '${expectedSubject}'),
          (SELECT count(*) FROM journals WHERE id = '${journalId}' AND revision = 3 AND deleted_at IS NULL),
          (SELECT count(*) FROM journals),
          (SELECT count(*) FROM investment_journals),
          (SELECT count(*) FROM journal_idempotency_records)
      `,
    ],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  ).trim();
  assert.equal(ownerCounts, '1,1,1,0,1,1,1,1');

  const summary = [
    'Finance composite API E2E: PASS',
    'flow=Core reference login -> Gateway -> Finance create/edit/delete/restore/detail/list',
    'source trees: Finance=d598dadb2677b2ac73488b6ab34f6e278aab009c Platform=9057914850b9e0026fa8851cbb062c692a51b4b3',
    'statuses=login:200,create:201,initial_detail:200,initial_list:200,edit:200,edited_detail:200,edited_list:200,delete:200,deleted_detail:404,active_list_after_delete:200,deleted_list:200,restore:200,restored_detail:200,restored_list:200,deleted_list_after_restore:200,create_replay:201,replay_detail:200',
    'persisted_counts=owned_journal:1,edited_investment:1,owned_idempotency:1,foreign_owner:0,active_revision:3,total_journals:1,total_investments:1,total_idempotency:1',
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
