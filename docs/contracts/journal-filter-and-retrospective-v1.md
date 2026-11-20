# Journal type filtering and user-authored retrospectives

## Journal list

`GET /finance/journals` accepts an optional `type=investment` or `type=study` in addition to `limit` and `cursor`. Omit `type` for all active journals. The server applies the current user's scope and the type predicate before paging, using the existing order of occurrence time and journal ID. The response shape is unchanged. Unfiltered v1 cursors remain valid. A filtered cursor is valid only for its original type. Unknown or repeated types and cursor/type mismatches return 400.

## Manual retrospective

These operations are registered when `finance.journal.retrospectives.enabled=true` (`FINANCE_JOURNAL_RETROSPECTIVES_ENABLED=true`). They use the existing Finance current-user boundary.

| Operation | Contract |
| --- | --- |
| `POST /finance/journals/{journalId}/retrospectives` | Requires `Idempotency-Key` and JSON with integer `sourceRevision` and nonblank `body` (up to 4000 UTF-16 code units). Optional `outcomeObservation`, `decisionQuality`, and `nextCheck` accept strings up to 2000 UTF-16 code units each. Trimmed empty optional values are normalized to null. Returns 201 and a Location for the saved resource. |
| `GET /finance/journals/{journalId}/retrospectives` | Returns `{items, hasMore}`, ordered newest first by server timestamp and ID. The first release exposes the latest 50 and indicates whether older records exist. |
| `GET /finance/journals/{journalId}/retrospectives/{retrospectiveId}` | Returns the individual record. |

Items contain `retrospectiveId`, `journalId`, `sourceRevision`, UTC `createdAt`, `body`, and the three nullable optional fields. The server supplies IDs and timestamps. The caller cannot set an owner or copy data from another journal.

For a new append, the server checks the active parent and locks it while comparing the revision and inserting the new row. A stale revision returns 409 with `journal_revision_conflict`. The same key and normalized content for one parent replay the original append. Reusing that key with different content returns 409 with `idempotency_key_reused`. A replay still requires an active parent owned by the caller. Foreign, missing, and soft-deleted parents return the existing journal 404. A physical parent deletion cascades. Original journal fields and revision are unchanged. No automated AI output or source body copy is stored here.

## DEV activation

Apply the additive V5 migration through the Finance migration lane, verify the runtime PostgreSQL role has SELECT and INSERT on the new table, then enable the backend and corresponding frontend feature flags in DEV. Verify authenticated write/readback and foreign-owner denial via the deployed identity boundary before extending availability. Application rollback can leave the additive table in place. Existing privacy and retention gates apply to any live user content.
