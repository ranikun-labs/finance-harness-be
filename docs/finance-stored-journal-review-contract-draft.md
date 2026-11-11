# Stored Journal Review — Contract Draft

**Status:** Draft for owner review. This is a second-slice product contract only. It does not authorize an endpoint, schema, model provider, migration, or change to the existing Journal fields.

## Purpose

Create an optional, Finance-owned analysis of one stored Journal that helps the user distinguish what they recorded from what they may be assuming or should verify. The Review is generated only after an explicit user action and remains separate from the user's Journal text and any user-authored Review.

## Inputs and evidence

- Read one Journal through the existing owner-scoped Journal detail boundary.
- For an Investment Journal, available user-authored fields are asset name, action, reasoning, emotion, and occurrence time/time zone.
- For a Study Journal, available user-authored fields are title, key content, open questions, and occurrence time/time zone.
- Treat the selected Journal as the only evidence unless a future approved contract supplies cited external sources. The Journal's owner, storage IDs, and internal metadata are not prompt content.
- If a field is absent, or the Journal does not support a conclusion, state that evidence is insufficient. Do not fill the gap from general knowledge and present it as a fact.

## Proposed Review sections

1. **Recorded facts:** Short statements directly supported by named Journal fields.
2. **User reasoning:** Quote or faithfully summarize the user's own reasoning, clearly labeled as user-authored text.
3. **Assumptions:** Possible premises implied by the reasoning, labeled as interpretations rather than facts.
4. **Missed checks:** Relevant questions or evidence not present in the Journal. Phrase them as checks to consider, not as claims that the user failed to act.
5. **Next questions:** Neutral questions the user may answer or investigate.
6. **Evidence and insufficiency:** For each factual statement, identify the Journal field that supports it. State when the Journal is the only source or when the available evidence cannot support an answer.

## Output and safety rules

- Do not provide trade advice, buy/sell/hold recommendations, position sizing, price targets, forecasts, or predictions.
- Do not introduce unsupported facts about an asset, company, market, or user.
- Keep the generated Review in a distinct result with its own author and timestamp. Never overwrite or merge it into the Journal's reasoning, key content, or a user-authored Review.
- Validate the complete candidate before showing it. If validation fails, expose no candidate text and mark the Review failed.
- Resolve the Journal using the current user from `CurrentUserPort`; return the same not-found behavior for absent and foreign-owned Journals.
- Creating a Review must not create, edit, or link a Journal automatically. Any Journal reference or persistence mechanism requires a separately approved API and storage contract.

## Open decisions

- Whether Review results are transient or stored, and their retention/deletion behavior.
- The explicit user action, endpoint, and read-back contract.
- Whether only the Journal can be evidence or a separately approved, cited source lookup may be offered.
- The final structured output shape, length limits, and validation/refusal wording.
- Whether a Review can be regenerated and how prior versions are displayed.

## Current source boundary

The current BE exposes owner-scoped Journal detail through `JournalReadApplicationService` and `JournalReadStore`. Its detail domain types provide the fields listed above. The current schema persists only the Investment and Study Journal records and has no Review entity or Journal-to-Review relation. This draft intentionally leaves those storage and API decisions open.

The accompanying first-slice General Analysis runtime is restricted to `!production & (local | test)` and uses process-local storage with a deterministic adapter. Repeated requests execute again, and a fresh process has no prior Question, Checklist, or Analysis records; these behaviors do not define a persistence or idempotency contract. This draft does not propose production activation or persisted Review results.
