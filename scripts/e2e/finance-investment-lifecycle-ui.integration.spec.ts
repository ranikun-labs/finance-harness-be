import { expect, test, type Request, type Response } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { ko } from '@/i18n/messages/ko';

const HARNESS_PATH = '/e2e/support/finance-investment-lifecycle-harness.html';
const STAGE_FILE = process.env.E2E_PRIVATE_STAGE_FILE;

function pathIs(request: Request, method: string, pathname: string) {
  const url = new URL(request.url());
  return request.method() === method && url.pathname === pathname;
}

function responseIs(response: Response, method: string, pathname: string) {
  return pathIs(response.request(), method, pathname);
}

async function hasBearer(request: Request) {
  return /^Bearer \S+$/.test((await request.headerValue('authorization')) ?? '');
}

function recordStage(stage: string) {
  if (!STAGE_FILE) return;
  writeFileSync(STAGE_FILE, `${stage}\n`, { mode: 0o600 });
}

test('synthetic reference session runs Investment create, edit, delete, and restore in the browser', async ({
  page,
}) => {
  let stage = 'browser-start';
  try {
    const userId = process.env.FINANCE_REFERENCE_E2E_USER;
    const password = process.env.FINANCE_REFERENCE_E2E_PASSWORD;
    if (!userId || !password) throw new Error('fixture missing');

    stage = 'auth-login';
    recordStage(stage);
    const loginRequestPromise = page.waitForRequest((request) =>
      pathIs(request, 'POST', '/api/v1/auth/finance-reference/login'),
    );
    const loginResponsePromise = page.waitForResponse((response) =>
      responseIs(response, 'POST', '/api/v1/auth/finance-reference/login'),
    );
    await page.goto(HARNESS_PATH);
    await expect(page.getByTestId('auth-entry')).toBeVisible();
    await page.getByLabel(ko.auth.entry.referenceUserId).fill(userId);
    await page.getByLabel(ko.auth.entry.referencePassword).fill(password);
    await page.getByTestId('finance-reference-login').click();
    const loginRequest = await loginRequestPromise;
    const loginResponse = await loginResponsePromise;
    expect(loginRequest.method()).toBe('POST');
    expect(loginResponse.status()).toBe(200);
    const loginBody = await loginResponse.json();
    expect(
      loginBody?.data?.tokenType === 'Bearer' &&
        typeof loginBody?.data?.accessToken === 'string' &&
        typeof loginBody?.data?.expiresAt === 'string',
    ).toBe(true);
    await expect(page.getByTestId('finance-reference-session')).toBeVisible();
    await expect(page.getByRole('heading', { name: ko.app.journalNew.investment })).toBeVisible();

    stage = 'investment-create';
    recordStage(stage);
    const originalAsset = 'Hosted UI investment original';
    const editedAsset = 'Hosted UI investment revised';
    const editedReasoning = 'Synthetic UI integration flow; edited through the browser.';
    const createRequestPromise = page.waitForRequest((request) =>
      pathIs(request, 'POST', '/finance/journals'),
    );
    const createResponsePromise = page.waitForResponse((response) =>
      responseIs(response, 'POST', '/finance/journals'),
    );
    await page.locator('#assetName').fill(originalAsset);
    await page.locator('#reasoning').fill('Synthetic UI integration flow; created in the browser.');
    await page
      .getByRole('radio', { name: ko.recordTags.action.interest })
      .locator('xpath=..')
      .click();
    await page.getByRole('button', { name: ko.app.journalNew.form.submitTest }).click();
    const createRequest = await createRequestPromise;
    const createResponse = await createResponsePromise;
    expect(await hasBearer(createRequest)).toBe(true);
    expect(
      /^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$/.test(
        (await createRequest.headerValue('idempotency-key')) ?? '',
      ),
    ).toBe(true);
    expect(createResponse.status()).toBe(201);
    const created = await createResponse.json();
    const journalId = created?.journalId;
    expect(
      typeof journalId === 'string' &&
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(journalId),
    ).toBe(true);
    await expect(
      page.locator('.journal-workspace-detail-pane').getByRole('heading', {
        level: 2,
        name: originalAsset,
      }),
    ).toBeVisible();

    stage = 'investment-edit';
    recordStage(stage);
    const journalPath = `/finance/journals/${journalId}`;
    const patchRequestPromise = page.waitForRequest((request) =>
      pathIs(request, 'PATCH', journalPath),
    );
    const patchResponsePromise = page.waitForResponse((response) =>
      responseIs(response, 'PATCH', journalPath),
    );
    await page.getByTestId('journal-edit-open').click();
    await page.locator('[data-edit-field="assetName"]').fill(editedAsset);
    await page.locator('[data-edit-field="action"]').selectOption('watching');
    await page.locator('[data-edit-field="reasoning"]').fill(editedReasoning);
    await page.locator('[data-edit-field="emotion"]').selectOption('관망');
    await page.getByRole('button', { name: ko.journalEdit.save }).click();
    const patchRequest = await patchRequestPromise;
    const patchResponse = await patchResponsePromise;
    expect(await hasBearer(patchRequest)).toBe(true);
    expect(patchResponse.status()).toBe(200);
    const patchRequestBody = patchRequest.postDataJSON();
    const patchResponseBody = await patchResponse.json();
    expect(patchRequestBody?.expectedRevision === 0).toBe(true);
    expect(patchResponseBody?.revision === 1).toBe(true);
    await expect(
      page.locator('.journal-workspace-detail-pane').getByRole('heading', {
        level: 2,
        name: editedAsset,
      }),
    ).toBeVisible();
    await expect(page.locator('.journal-workspace-detail-pane').getByText(editedReasoning)).toBeVisible();

    stage = 'investment-delete';
    recordStage(stage);
    const deletePath = `${journalPath}/delete`;
    const deleteRequestPromise = page.waitForRequest((request) =>
      pathIs(request, 'POST', deletePath),
    );
    const deleteResponsePromise = page.waitForResponse((response) =>
      responseIs(response, 'POST', deletePath),
    );
    await page.getByRole('button', { name: ko.journalLifecycle.remove }).click();
    await page.getByRole('button', { name: ko.journalLifecycle.confirm }).click();
    const deleteRequest = await deleteRequestPromise;
    const deleteResponse = await deleteResponsePromise;
    expect(await hasBearer(deleteRequest)).toBe(true);
    expect(deleteResponse.status()).toBe(200);
    const deleteRequestBody = deleteRequest.postDataJSON();
    const deleteResponseBody = await deleteResponse.json();
    expect(deleteRequestBody?.expectedRevision === 1).toBe(true);
    expect(deleteResponseBody?.revision === 2 && deleteResponseBody?.state === 'deleted').toBe(true);
    await expect(page.getByText(ko.app.journalDetail.notFound.description)).toBeVisible();

    stage = 'deleted-list';
    recordStage(stage);
    const deletedListRequestPromise = page.waitForRequest((request) =>
      pathIs(request, 'GET', '/finance/journals/deleted'),
    );
    const deletedListResponsePromise = page.waitForResponse((response) =>
      responseIs(response, 'GET', '/finance/journals/deleted'),
    );
    await page.getByRole('button', { name: ko.journalLifecycle.deletedList }).click();
    const deletedListRequest = await deletedListRequestPromise;
    const deletedListResponse = await deletedListResponsePromise;
    expect(await hasBearer(deletedListRequest)).toBe(true);
    expect(deletedListResponse.status()).toBe(200);
    await expect(page.getByRole('button', { name: ko.journalLifecycle.restore })).toHaveCount(1);

    stage = 'investment-restore';
    recordStage(stage);
    const restorePath = `${journalPath}/restore`;
    const restoreRequestPromise = page.waitForRequest((request) =>
      pathIs(request, 'POST', restorePath),
    );
    const restoreResponsePromise = page.waitForResponse((response) =>
      responseIs(response, 'POST', restorePath),
    );
    await page.getByRole('button', { name: ko.journalLifecycle.restore }).click();
    const restoreRequest = await restoreRequestPromise;
    const restoreResponse = await restoreResponsePromise;
    expect(await hasBearer(restoreRequest)).toBe(true);
    expect(restoreResponse.status()).toBe(200);
    const restoreRequestBody = restoreRequest.postDataJSON();
    const restoreResponseBody = await restoreResponse.json();
    expect(restoreRequestBody?.expectedRevision === 2).toBe(true);
    expect(restoreResponseBody?.revision === 3 && restoreResponseBody?.state === 'active').toBe(true);
    await expect(page.getByRole('button', { name: ko.journalLifecycle.restore })).toHaveCount(0);

    stage = 'restored-detail';
    recordStage(stage);
    await expect(
      page.locator('.journal-workspace-detail-pane').getByRole('heading', {
        level: 2,
        name: editedAsset,
      }),
    ).toBeVisible();
    await expect(page.locator('.journal-workspace-detail-pane').getByText(editedReasoning)).toBeVisible();
    recordStage('passed');
  } catch {
    recordStage(`failed:${stage}`);
    throw new Error(`Synthetic Finance browser lifecycle failed at ${stage}.`);
  }
});
