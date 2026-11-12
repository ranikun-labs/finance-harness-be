# Finance trusted context v1 reference

This document records the non-production Finance receiver reference for RPL-34. It is based on D1 candidate SHA-256 `3b4138cd0cc57fa56f8607be3fafc4255e5dc70f7c311beb424fa739545e6326` and does not accept a production Gateway-to-Finance authentication relationship. The Gateway-owned counterpart is `docs/contracts/finance-trusted-context-v1.md` in the RPL-51 repository; each service owns its own implementation and evidence.

## Wire reference

The fixture applies only to `GET /finance/journals`, `POST /finance/journals`, `GET /finance/journals/{lowercase-canonical-UUID}`, and `PATCH /finance/journals/{lowercase-canonical-UUID}`. The canonical detail PATCH is limited to own raw Journal correction under `docs/contracts/journal-edit-v1.md`; it remains part of this non-production reference. The producer emits exactly one value for each header:

| Header | Reference value |
| --- | --- |
| `X-Finance-Context-Version` | `1` |
| `X-Finance-Service-Id` | `gateway-app` |
| `X-Finance-Service-Secret` | Public dummy caller value from test resources only |
| `X-Finance-Audience` | `finance-journal` |
| `X-Finance-Subject` | Verified stable Identity subject UTF-8 bytes, canonical Base64URL without padding |

The subject is opaque text. The receiver accepts only canonical Base64URL whose decoded value is valid UTF-8, 1–256 bytes, not whitespace-only, and contains no CR, LF, or NUL. It preserves the decoded string without trimming, case folding, UUID parsing, or Unicode normalization. Duplicate values, comma-joined values, unknown `X-Finance-*` headers, invalid caller proof, and an aggregate header value size over 2,048 UTF-8 bytes fail closed.

The Gateway fixture uses the `sub` returned by its existing `JwtVerifier` after a successful signature and blacklist check; its test stubs the blacklist as not revoked and uses a public dummy JWT key. It removes caller-supplied Finance and legacy identity headers before constructing the five values. Finance verifies the caller proof before decoding the subject, attaches the resulting immutable `IdentityUserId` only to the current request, and exposes it to the existing `CurrentUserPort`. The request attribute is removed in `finally`; Finance does not read raw identity headers from the port or substitute a local fixed user.

## Activation and ownership

The receiver is registered only with the explicit `finance-trusted-context-reference` profile and `finance.auth.mode=trusted-context`. That profile requires an explicit caller value and rejects production profiles or `PLATFORM_ENVIRONMENT`, `platform.metrics.environment`, `FINANCE_METRICS_ENVIRONMENT`, or the resolved `management.metrics.tags.environment` values `prod` or `production`, including case or surrounding whitespace differences; it cannot be combined with the `local` profile. The public dummy exists only in `src/test/resources/application-finance-trusted-context-reference.properties`. No production caller value is present, no default receiver activation is provided, and the Gateway feature remains disabled by default with no Finance upstream route installed.

Ownership remains separated: Identity owns the meaning and stability of the issued subject; Gateway owns user-token verification, blacklist checks, removal of untrusted incoming headers, and construction of the Finance context; Finance owns caller-proof verification, route/method scope, request identity lifetime, and the existing Journal owner and idempotency checks. Security and deployment owners must separately decide the production trust relationship, TLS termination and peer verification, credential provisioning and rotation, and residual-risk acceptance.

## Risks and release gates

The fixture credential is a static bearer proof. It is not cryptographically bound to the subject, route, method, body, or freshness, so exposure of the credential or context after TLS termination permits subject substitution or replay. Finance trusts the Gateway's per-request subject and does not independently revalidate Identity revocation. This reference does not implement or verify production TLS, issue or inject a production credential, establish production key rotation or incident response, or accept those risks.

Production activation remains blocked on the separate security and deployment decisions above. A fixture result does not establish a real user login, live Identity issuer or revoke behavior, browser cookie or mobile flow, two-service HTTP deployment, TLS, or G4 readiness. The producer and receiver source owners must keep their changes and fixed-HEAD evidence in their respective repositories; this document does not transfer ownership or expand the accepted production scope.
