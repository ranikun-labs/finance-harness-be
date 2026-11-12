# Own Journal lifecycle — synthetic reference candidate

RPL-34 owns Finance state and current-user authorization; RPL-51 owns the exact
Gateway routes. This document describes the lifecycle reference candidate based
on current main. It does not describe an operating deployment. No retention
duration or purge is selected.

* `GET /finance/journals/deleted` returns only own journalId/type/revision/deletedAt,
  latest 100 by deletedAt/id, plus `hasMore`. Further paging is not implemented;
  the UI states this limit. It never returns raw title/body/questions.
* `POST /finance/journals/{canonicalUUID}/delete` and `/restore` accept exactly
  `{expectedRevision}` (safe nonnegative integer). Success is 200 with
  `{journalId, revision, state}` and no raw body. Each new transition increments
  the existing revision under the same owned root row lock; restore never resets it.
* A lost-response retry may confirm the desired current state only when current
  revision is expectedRevision+1. This is state confirmation, not an execution
  receipt identifying which network request performed the transition.
* Stale/wrong state/exhausted revision is 409. Foreign or missing is 404.
  Active reads and edits require deleted_at IS NULL; deleted detail/edit is 404.
* Creation replay preserves original key, fingerprint and ID. Both ordinary replay
  and actual namespace-unique collision recovery lock/check root state. Same
  payload for a deleted record returns 410 `journal_deleted` with ID/revision/state;
  different payload keeps 409. Neither path creates a new record or restores it.
* V4 adds nullable journals.deleted_at only. V1/V2/V3, typed children/questions,
  original creation rows/fingerprints, owner/type/recording time/zone remain intact.
* One detail SQL captures body, revision and ordered questions as one snapshot,
  with [] for no questions. READ COMMITTED readOnly is not snapshot protection.

The UI uses explicit confirmation and the observed revision, without automatic
retry or latest-revision substitution. Confirmed deletion invalidates old detail
and pending additional-page responses in that window. Other devices are checked
by server state on their next request, not by a new real-time broadcast protocol.

Normal raw-edit code has only an existing revision comparison/route seam for AI;
no AI producer, result publication or new provider/API is implemented. Delete or
restore does not promote an old AI result to current. Actual AI adoption and
cross-device publication remain the separate AI product contract.

This is retained soft deletion for direct user restoration, not physical erasure.
Retention periods, automatic purge, backup/WAL recovery and durable lifecycle
history/audit are not implemented here. No operating activation is accepted;
without authoritative latest lifecycle state, an operating backup restore is
blocked rather than declared safe. No operating DB, real account, G4, merge or
deployment is performed by these synthetic tests.
