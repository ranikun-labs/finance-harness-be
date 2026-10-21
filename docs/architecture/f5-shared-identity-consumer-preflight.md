# Finance F5 — Shared Identity Consumer 사전 점검 (계약 결정 전)

> 상태: **NON-NORMATIVE PREFLIGHT / NOT APPROVED**. 운영용 인증 어댑터·토큰 정책을 여기서 확정하지 않는다.
> Jira: [RPL-34](https://ranikunlabs.atlassian.net/browse/RPL-34), [RPL-10](https://ranikunlabs.atlassian.net/browse/RPL-10) (완료), [RPL-11](https://ranikunlabs.atlassian.net/browse/RPL-11) (기존 미완료 조사 재활용), [RPL-55](https://ranikunlabs.atlassian.net/browse/RPL-55) (Carelog G4 선행 영향).
> 조사 HEAD: `finance-harness-be/main@13d686794cc3409f15bdf9ae02d5aa79a06710f2`, `finance-harness-fe/master@991ec0f3e9fe0979353dc55e4b6d1d9453a56d6f`, `platform-services/main@9f04f44e5dfc88272fcf380f96524f5bfdc92b75`.
> 참고: Finance 테스트 PR `#10@36bde055...`은 당시 미병합 Draft. 31 PASS·Sol PASS는 이전 세션의 테스트 보고이며 이 문서 작성에서는 재실행하지 않음.

## 1. 이미 있는 것과 없는 것

| 영역 | 현재 확인 | F5 결손 |
| --- | --- | --- |
| Finance BE | `CurrentUserPort.currentUserId()`와 owner-scoped Journal 서비스; `LocalDevCurrentUserAdapter`는 `!production & (local | test)` + `finance.auth.mode=local`에서만 동작 | 운영 Principal Source, 서명/신뢰 경계, 사용자별 Request Scope |
| Finance FE | 같은 출처 `/finance/journals` HTTP Read/Create Adapter와 `AuthPresentationConsumer`(화면 상태 전용) | 실제 Auth/session consumer, 인증 만료·로그아웃·복구, Web·Capacitor transport |
| Shared Gateway | 현재 `JwtGlobalFilter`가 JWT 서명·기본 유효기간 검증, trusted headers 제거/재삽입, `/api/**` Carelog routing | **Finance 전용 라우트와 업스트림 분리**, `iss/aud` 등 최종 검증 정책 및 Identity/Finance trusted-wire 합의 |
| 계약 | `harness-foundation-docs/docs/contracts/backend-service-foundation/identity-token-contract.md`는 `iss/sub/aud/iat/exp/jti`를 요구하는 **Draft** | 현재 Gateway/Identity 실제 JWT와의 Claim Gap, 승인 가능한 버전/마이그레이션 순서 |

`IdentityUserId`는 비어 있지 않은 opaque 문자열을 받는다. Finance UserId는 CRM CustomerId 또는 임의 클라이언트 전송 ID가 아니다.

## 2. Owner가 결정해야 하는 최소 계약

| 결정 | 후보/제약 | Gate |
| --- | --- | --- |
| **F5-D1 인증 검증 위치** | A. Gateway에서 JWT를 검증하고 spoof-resistant trusted principal을 Finance BE로 전달; B. Finance BE가 검증된 JWT를 직접 처리. 제품별 직접 Identity DB 조회/매 요청 Identity HTTP 조회 금지 | **Owner 선택 전 구현 금지** |
| **F5-D2 Token/Claims** | AccountId(`sub`) 정본, `iss`·제품별 `aud`, 알고리즘/key-rotation/TTL/JTI·revocation 정책 및 Gateway+BE 각각 검증 의무. 현재 공통 문서는 Draft | **현재 발급·검증 코드와 정합성 검토 후 승인** |
| **F5-D3 FE Web/Mobile Session** | Web의 Same-origin 쿠키/CSRF와 Capacitor secure storage 또는 다른 승인된 안전한 채널별 흐름은 분리 검토. `HttpJournalCreatePort`가 명시적 Bearer header를 넣지 않는다는 점 ≠ 브라우저의 쿠키를 절대 전송하지 않음 | **Web·iOS·Android 호환 판정 전 Production ON 금지** |
| **F5-D4 Route & Error** | `/finance/**` FE 표면, 실제 Gateway path/rewrite, 401 unauthenticated vs 403 forbidden, foreign owner 404 `journal_not_found` 유지 | **Router/Adapter/BE Contract E2E 필요** |

**추천(결정 아님):** 먼저 현행 Gateway가 Carelog에 전달하는 signed-JWT → validated context 모델을 재사용할 수 있는지 RPL-55 결과로 확인한다. Finance-specific authorization은 Finance가 수행해야 한다. 새 인증 프레임워크/범용 Gateway Header 플랫폼 도입은 보류.

## 3. 구현 순서와 인수 조건

1. **F5 Contract Owner Gate**: F5-D1~D4 중 미정 사항 확정, 실제 Gateway/Identity 발급 토큰과 Claim·Header·Route 대조. 기술적으로 거부해야 할 토큰 규칙을 테스트로 명시.
2. **Finance Backend 한정 Slice**: 합의한 입력에서 **요청마다** `CurrentUserPort`가 AccountId를 해석하는 production-only adapter + 출처 검증·fail-closed 보안 체인. LocalDev adapter는 Production에서 계속 제외; Owner/Journal API·Flyway·데이터 모델 변경 금지.
3. **FE Consumer Slice**: 기존 HTTP Port에 승인된 인증 transport 적용; Web과 Capacitor 분리; 최초 인증·401/403·세션 만료·logout·이전 화면 복귀 처리.
4. **F6 비운영 E2E**: Gateway→Finance create/detail/list, 두 사용자 동일 idempotency key 독립성, foreign-owner detail 404, spoofed/expired/wrong-audience token 거부, 인증 실패 시 durable Journal 신규 생성 0건. Cloud DB/운영 Cutover는 별도.

## 4. 재사용할 테스트와 금지 사항

- 이미 존재: BE `IdentityAndLocalAdapterTests`의 local/test-only + production fail-closed, Journal idempotency/owner/detail/list 회귀, FE HTTP Adapter 단위 테스트. Finance `#10`의 31 PASS는 결과 기록이지 이 문서의 실행 증거 아님.
- 금지: `production,local` 혼합 프로필로 우회, client-supplied ownerId 신뢰, Shared Identity DB 직접 접근, 제품별 Auth 복제, 새 Journal DB 마이그레이션, 근거 없는 `iss/aud` 수용 예외.
- 이번 산출물: **계약 Gap 및 구현 인수 조건만 확정**. Token 보안·세션 정책의 승인/운영 구현/테스트 실행은 아직 수행하지 않음.
