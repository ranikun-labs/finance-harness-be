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

## 5. 현재 구현 기준 차이 분석 (2026-10-21)

| 코드 근거 | 확인된 사실 | F5 조치 |
| --- | --- | --- |
| [Gateway JwtVerifier](https://github.com/ranikun-labs/platform-services/blob/main/gateway-app/src/main/kotlin/com/ranikunlabs/platform/gateway/filter/JwtVerifier.kt) | JJWT의 `verifyWith(key)`·`parseSignedClaims(token)` 수행. 이 코드에는 명시적 issuer/audience 제한 없음 | 승인된 JWT `iss/aud` 검증 경계와 실제 발급 Claims 일치 여부를 별도 실험에서 확정 |
| [Gateway routes](https://github.com/ranikun-labs/platform-services/blob/main/gateway-app/src/main/resources/application.yml) | 인증 라우트 외 `/api/**`는 Carelog upstream으로 이동. Finance용 별도 명시적 route 없음 | `/finance/**`의 public/protected/auth upstream 선택 및 rewrite 필요 여부 결정. Carelog catch-all과 중첩 회귀 금지 |
| [Finance BE LocalDev adapter](https://github.com/ranikun-labs/finance-harness-be/blob/main/src/main/kotlin/labs/ranikun/finance/identity/adapter/LocalDevCurrentUserAdapter.kt) | `!production & (local | test)`에서만 빈 등록 + `finance.auth.mode=local` 조건 | 운영용 빈 신규 작성 시 Production에서 개발자 고정 UserId를 절대 fallback 하지 않음 |
| [Finance FE create HTTP port](https://github.com/ranikun-labs/finance-harness-fe/blob/master/src/features/journal-new/adapter/HttpJournalCreatePort.ts) | `/finance/journals`로 idempotency header 전송; 명시적인 Authorization Bearer header는 넣지 않음 | 브라우저 쿠키 전송 여부는 fetch 기본 same-origin 정책에 좌우됨. 승인 전 인증 transport 완성으로 표현 금지 |
| [Finance FE auth seam](https://github.com/ranikun-labs/finance-harness-fe/blob/master/src/features/auth/authPresentation.ts) | `unknown/guest/authenticated` UI 상태만 있음; 실제 인증 주체나 토큰 검증 계약 아님 | Web/Capacitor 채널별 인증 소비자 구현은 별도 승인 후 |

**계약 간 충돌 위험:** Canonical Token Contract 초안은 `iss/sub/aud/iat/exp/jti`를 필수로 서술한다. 하지만 현재 Gateway 실행 코드에서는 명시적 issuer/audience 검증이 확인되지 않았다. 구현 코드를 계약이 이미 실현된 것으로 간주하지 않는다. 새 JWT Claim을 발급하거나 현재 Token 검증 예외를 임의 도입하지 않는다.

## 6. Owner 1회 결정 패키지 (구현 자동 승인 아님)

**권고안 (선택 전):** 기존 Gateway의 인증 검증과 스푸핑 헤더 제거 구조를 재사용하여 Finance 전용 trusted principal을 주입하고, Finance BE에서 내부 호출 자격/컨텍스트를 확인한 뒤 `CurrentUserPort`로 매 요청마다 AccountId만 제공하는 방향을 우선 평가한다. Product의 Journal 소유권 검증은 Finance에서 유지. 이 선택도 Gateway↔Finance 네트워크·내부 credential·회전·실패 계약 및 검증 근거 없이 Production 승인할 수 없다.

- **D1 — 인증 경계:** Gateway 검증+신뢰 컨텍스트 전달(권고) 또는 Finance BE 자체 JWT 검증. Gateway가 서명 검증했다는 이유만으로 인증 미확인 HTTP header를 신뢰해서는 안 된다.
- **D2 — 발급/검증 토큰:** Current Identity issuer, access-token Claims, expected finance audience, signing key/key rotation, logout/revocation consistency에 대한 **현재 코드·실험 증거** 확보 후 토큰 계약 채택 여부 결정.
- **D3 — FE 세션:** Web의 쿠키+CSRF와 Capacitor 전용 secure-store/Bearer 방식은 단지 비교 후보. WEB/MOBILE의 실제 Callback/ReturnTo/Refresh 계약과 자격정보 저장 정책을 Owner가 결정.
- **D4 — 라우팅/에러:** `/finance/**` same-origin 보존, Gateway route priority/stripping, Finance 401 vs 403, foreign-owner 404 계약 승인.

Owner 승인 시에도 **F5 Backend adapter Slice**만 착수하며, Finance FE Production Activation 및 F6 성공 선언은 포함하지 않는다. 실제 연동 E2E와 독립 리뷰가 후속이다.

## 7. 최소 검증 시나리오 (구현 후)

| ID | 요청·상황 | 인수 기준 |
| --- | --- | --- |
| F5-01 | 신뢰 경계에서 사용자 A 요청 | CurrentUserPort=A, Journal A만 조회 |
| F5-02 | 다음 요청에서 사용자 B | Previous A principal 재사용 없음; 동일 Idempotency-Key여도 owner namespace 분리 |
| F5-03 | 무인증·변조·만료·잘못된 Audience | 401; DB 변경·Journal 생성 0건 |
| F5-04 | 외부 인증 관련 Header 위조·내부 서비스 Credential 누락 | Fail-closed, controller/repository 진입 없음 |
| F5-05 | 인증 성공·권한 부족 | 403; 사용자 존재 여부/타 tenant 정보 누설 없음 |
| F5-06 | 타 사용자 Journal Detail | 404 `journal_not_found` 유지 |
| F5-07 | FE Web/Mobile 쿠키·토큰 만료·갱신·로그아웃 | 채널별 승인된 세션 계약과 재시도 범위 보존 |
| F5-08 | Gateway route 경계 | Finance 요청이 Carelog로 넘어가지 않고, public Auth는 Identity로만 이동 |

**실행 결과:** NOT_RUN. 기존 #10의 31 PASS는 Journal owner isolation 검증이지, 위 8개 운영 인증 E2E의 PASS가 아니다.

## 8. Token Provider 직접 대조 — 기존 발급 JWT와 Draft 계약 차이 (2026-10-22)

현재 `platform-services/main`의 아래 **발급과 검증 소스**를 서로 대조한 결과다.

- [Identity JwtTokenProvider](https://github.com/ranikun-labs/platform-services/blob/main/platform-core/identity/src/main/java/com/ranikunlabs/platform/identity/adapter/token/JwtTokenProvider.java): `generateAccessToken()`은 `sub=accountId`, `organizationId`, `role`, `publicId`, `iat`, `exp`, 대칭키 서명을 사용한다. **`iss`, `aud`, `jti`를 발급하지 않는다.** `generateRefreshToken()`에는 `jti` 성격의 `.id(UUID)`가 있지만 Access Token과 별도다.
- [Gateway JwtVerifier](https://github.com/ranikun-labs/platform-services/blob/main/gateway-app/src/main/kotlin/com/ranikunlabs/platform/gateway/filter/JwtVerifier.kt): 대칭키 서명을 검증하고 Signed Claims를 파싱하지만, 현재 코드에 기대 issuer/audience의 명시적 검증이 없다.
- [Gateway JwtGlobalFilter](https://github.com/ranikun-labs/platform-services/blob/main/gateway-app/src/main/kotlin/com/ranikunlabs/platform/gateway/filter/JwtGlobalFilter.kt): 현행 Carelog-specific 조직·역할·publicId Claims를 Product로 전달한다. Finance Product 권한을 이 역할 Claim에 자동 위임하지 않는다.
- [Draft Shared Identity Token Contract](https://github.com/ranikun-labs/harness-foundation-docs/blob/main/docs/contracts/backend-service-foundation/identity-token-contract.md): `iss/sub/aud/iat/exp/jti` 필수 및 Product별 `aud` 검증을 목표로 하나 아직 **Draft**, Production 적용 증거가 아니다.

**검증 결론:** 현행 Access JWT가 Draft의 필수 Claims 전부를 충족한다고 주장할 수 없다. 아직 Finance용 JWT를 발급하거나 `finance-api` Audience를 검증하는 구현이 확인되지 않는다. 이 문서는 현재 발급된 JWT로 F5 Production Adapter를 신뢰해도 된다는 승인이 **아니다**.

**F5-D2 필수 결정**: (a) Issuer·Finance Audience·Access JTI/폐기정책의 채택 여부, (b) Carelog 기존 Access Token과의 단계적 공존/이행 방안, (c) Gateway 대 Finance 검증 의무의 정확한 위치, (d) 누락/다른 Audience/구 버전 토큰의 Fail-closed 판정을 함께 정한다. 특히 `iss/aud`를 갑자기 Required로 바꾸면 기존 Carelog 토큰의 호환성에 영향이 있으므로 별도 배포·롤백 Gate로 관리한다.

관련 구현은 `platform-services` 내 소유자가 명시된 별도 Slice에서 처리하며, Finance Journal DB·Idempotency 구현을 변경하지 않는다. **이번 검증은 소스 대조만 수행했고, 실제 JWT Test/Runtime E2E는 NOT_RUN.**
