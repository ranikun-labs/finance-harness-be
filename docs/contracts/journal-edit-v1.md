# Own raw Journal correction v1 — nonproduction reference

Owner explicitly accepted own Study/Investment raw-content correction on 2026-11-09.
RPL-34 owns Finance FE/BE; exact Gateway detail PATCH is the separate RPL-51 writer's change.

`PATCH /finance/journals/{canonicalUUID}` accepts exactly `{expectedRevision, fields}`.
`expectedRevision` is a nonnegative JSON integer at most 9007199254740991.
Study replaces `title`, `keyContent`, `openQuestions`; Investment replaces `assetName`,
`action`, `reasoning`, optional/nullable `emotion`. Existing Create text/enum limits apply.
No owner/id/type/createdAt/occurredAt/timeZone/DecisionContext/Retrospective fields are accepted.
The backend uses stored type and authenticated CurrentUserPort owner, not caller body identity.

GET detail adds `revision` (existing rows start at 0). A transaction locks the owned
row, compares the expected revision and changes only allowed raw fields, updatedAt
and revision+1. A stale or exhausted revision returns 409 `journal_revision_conflict`;
foreign/missing returns 404. Success is 200 `{journalId, revision}`. No automatic retry.
The maximum safe revision cannot overflow. Validation failures leave all rows unchanged.

Original POST creation fingerprints/idempotency records remain unchanged. Replaying
an identical original create request returns its original ID; it does not revert edits.
Different create payload reuse retains the existing conflict behavior. Deletion is separate.

Review producers must capture `{journalId, revision}` before execution and may publish
as current only when both still match the latest record. FE exports this comparison seam
and checks the live revision on the existing revision-bound Review route. Edited content
requires a new review. No AI endpoint/provider/automatic call, result rewrite, context
mutation or Retrospective mutation is introduced. Existing mock/absent AI production wiring
is not upgraded to actual AI stale-response runtime proof by the pure comparison test.

Reference access-only/selected synthetic account/default-disabled/production guards remain.
No operating database migration, real account/G4, remote publication, merge or deployment.
