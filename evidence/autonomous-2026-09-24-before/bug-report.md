# 결함 보고서

> 최신 종합 점검(2026-09-24): **개발·검증 진행 중, 배포 준비 완료 아님.** 자체 점검 보완 후 Frontend38/Backend37·DB11/생산 Mock2 및 격리 복원 훈련 PASS. 휴대폰 HTTPS 수정본 반영·운영 최신 ledger/WAL·기기/전수 검증은 남아 있습니다. 상위 추적표71사례26PASS/45NOT_RUN 유지. 최신 구분은 [self-review.md](self-review.md), 실행 근거는 [test-report.md](test-report.md)를 따릅니다. 아래 날짜별 기록은 당시 이력입니다.

2026-09-19, macOS/Java 21/PostgreSQL 17.6/Chromium. 실제 실행 결과는 test-report.md와 연결한다. 아래 수정은 요구사항 완화 없이 승인 범위에서 수행했다. 미검증 항목은 결함 없음의 근거로 사용하지 않는다.

| Bug ID | Severity | 관련 요구사항/사례 | 기대와 재현/실제 결과 | 원인·수정 파일 | 재검증/상태 |
|---|---|---|---|---|---|
| BUG-001 | Major | I-02, NFR-012/014, 실제 PostgreSQL 통합 | 빈 PostgreSQL에 V1~V5 후 JPA validate 정상 기동 기대. 실제 currency 열에서 CHAR/VARCHAR 차이로 컨텍스트 기동 실패, 8개 테스트 실패 | `LedgerTransaction.java`: columnDefinition만으로 JDBC String 타입은 VARCHAR가 됨. `@JdbcTypeCode(Types.CHAR)` 명시, DB 계약 유지 | `evidence/integration-schema-failure.xml` 원인 보존. 수정 후 단위32·통합8 및 실제 앱 기동 PASS. FIXED |
| BUG-002 | Major | FR-002, NFR-006, refresh 뒤 변경 요청 | access 만료→refresh 이후 변경 재시도 성공 기대. refresh가 CSRF 쿠키를 지운 뒤 기존 토큰을 재사용할 위험 | `frontend/src/shared/api.ts`: refresh 전후 CSRF를 다시 획득하도록 수정 | `api.test.ts`의 새 CSRF 헤더 assertion PASS. FIXED |
| BUG-003 | Major | NFR-007, 로그 Secret | 사용자 정의 인증에서 Spring 기본 임시 비밀번호가 생성·로그되지 않아야 함. 초기 테스트 로그에 자동 생성 테스트 비밀번호 발견 | `MoneyMapApplication.java`, HTTP 테스트 설정: UserDetailsServiceAutoConfiguration 제외. 과거 생성 결과의 해당 값은 비출력 처리 | 최종 실제 로컬 로그·테스트/빌드 파일의 알려진 Secret 검사 PASS (`evidence/log-audit.json`). 운영 전체 로그 검증은 별도. FIXED |
| BUG-004 | Minor | NFR-002, 인증/거래 E2E | 비밀번호/카테고리의 명확한 접근성 이름 기대. 보조 안내를 포함한 label로 exact locator 실패 | `auth-form.tsx`, `transaction-form.tsx`: 명시적 aria-label 추가 | Mock E2E2 및 실제 E2E 정상 흐름 PASS. 전체 스크린리더 검증을 뜻하지 않음. FIXED |
| BUG-005 | Minor | FR-008, NFR-010, 응답 유실 | 재조회와 제출 초안의 의미가 같으면 동일하다고 판정. JSON 키 순서/빈 값/주소 없는 지역 메타 차이로 불일치 가능 | `shared/types.ts`: 고정 필드 순서와 서버와 같은 정규화로 비교 | 금액/location 테스트 및 실제 PUT commit 후 응답 유실 E2E PASS. FIXED |
| BUG-006 | Minor | NFR-006/013, Origin 오류 계약 | 잘못된 Origin도 envelope/requestId/no-store 유지. CORS 필터가 먼저 반환하면 공통 형식 누락 | `SecurityConfig.java`, `RequestFilter.java`: 공통 필터를 CorsFilter 전에 두고 Origin 검증 | `HttpContractTest.wrongOriginRejected` 포함 단위/Mock32 PASS. FIXED |
| TEST-001 | Minor | 실제 E2E 재시도 계측 | 두 번째 POST가 실제 도착한 후 키 동일성 비교 기대. 버튼 click 직후 동기 검사가 요청 도착보다 먼저 실행되어 count=1 | `e2e-real/live-flow.spec.ts`: expect.poll로 요청 도착 대기, count=2/동일 키 기대 유지 | 실제 E2E 정상 및 응답 유실 두 사례 PASS. 테스트 계측 결함 FIXED |

외부 Kakao·실기기·CSP·운영 복원은 미검증이며 결함 없음으로 판정하지 않았다. 과거 지역 별칭의 정확한 유효 기간은 원본 자료에 없으므로 추정하지 않고 UNKNOWN으로 처리한다. 부하 측정에서 발견된 결함은 아래 실행 결과에 추가한다.

성능 측정은 HTTP 오류0, 모든 측정 경로 p95 기준 충족으로 PASS. 운영 분포 전체나 실행 계획을 검증한 것은 아니다.

서버 필드별 오류 매핑의 기존 보완 항목은 아래 BUG-007에서 수정·재검증했다.


## 추가 발견·수정 (2026-09-19)

| Bug ID | Severity | 관련 요구사항/재현 | 기대/실제 | 원인·수정 | 검증·상태 |
|---|---|---|---|---|---|
| BUG-007 | Major | NFR-006/NFR-002, 잘못된 가입·거래 필드 제출 | 필드별 오류와 입력란 설명 연결 기대. 기존 fields=[] 및 일반 오류만 표시 | ApiException.FieldError와 안전한 Bean Validation 매핑, Rules/Writes/AuthService의 필드 검증, ApiError 필드 전달, 가입·거래 폼 RHF/aria-describedby 연결. 거부 값·원문 검증 메시지를 직렬화하지 않음 | HttpContractTest 안전한 필드 응답2개, auth-fields Component, Chromium 잘못된 금액 설명/제출 차단 PASS. FIXED |
| BUG-008 | Major | FR-013/016, 위치 요청 후 창을 닫고 다른 선택을 여는 상황 | 닫힌 요청 폐기 기대. 이전 현재 위치가 늦게 도착하면 새 선택 창에 미리보기 생성 가능 | location-picker.tsx의 요청 시작/닫기/다음 선택마다 sequence 검증. 이전 응답은 주소 조회·화면 갱신 전에 폐기 | location-races Component의 지연 현재 위치/다음 검색 창 시험 PASS. FIXED |
| BUG-009 | Minor | FR-014, 진행 중 검색어를2글자 미만으로 지움 | 진행 표시 종료 기대. 취소된 요청의 finally를 무시하면서 busy가 유지될 수 있음 | 최소 검색어 분기에서 busy=false 처리 | 지연 응답·검색어 삭제 후 결과 및 진행 표시 검사 PASS. FIXED |
| BUG-010 | Minor | NFR-002, 입력 오류 표시 후 접근성 이름 조회 | 입력 이름은 유지하고 오류는 설명으로 읽혀야 함. label 안 오류 문구가 이름에 붙음 | auth/transaction 입력의 명시적 이름과 고유 설명 ID 연결. 비밀번호 확인 설명 ID 중복도 제거 | Component 접근성 설명 및 실제 가입·거래 E2E PASS. FIXED |

현재 단위34/DB11/프런트24/Mock E2E2/실제 E2E2 총73개 회귀 통과. 새 오류 응답과 위치 경합 수정은 기존 승인된 계약을 충족하기 위한 보완이며 제품 범위 변경은 없다.


## BUG-011 HTTPS 테스트 로그아웃 유실 동기화 (2026-09-21)

테스트 결함 FIXED. 차단 화면이 요청 완료 전에 표시되므로 화면만 보고 새로고침하면 의도한 commit 후 유실 조건이 성립하지 않을 수 있었다. 실제204 commit/route.abort 및 오류 표시를 기다리도록 수정. 앱 인증 코드 변경 없이 HTTPS2PASS. 초기 evidence/mobile-https-e2e.log, 최종 evidence/mobile-https-verified-e2e.log.


## BUG-012 지번 주소 표시 누락 (2026-09-21)

코드 확인 결함: LocationPicker가 placeName/roadAddress만 표시해 parcelAddress만 존재할 때 주소 없음으로 표시했다. 미리보기·확정 결과에 지번 fallback 추가, 주소 결과가 없을 때 대안 안내. 합성 현재 위치 테스트2개 추가, 프런트26PASS/typecheck PASS. 소스 수정 완료, 실행 중인 HTTPS 빌드 반영 대기. 사용자의 새로고침 전 증상 원인으로 단정하지 않는다.


## 2026-09-23 정적 점검의 미해결 항목

- R-02: 카테고리 PATCH 성공 후 version 상태 미갱신. 코드상 결함, 실행 회귀 추가 필요.
- R-03/04: 지도 비동기 준비와 marker 표시,409 상세 복구 순서. 경합 위험/계약 차이, 새 실행 재현 전.
- R-01/05/06/07: 복원 gate,선택 위치 지도 미리보기,통계 UI 일부,SDK 오류 코드 집계 구현 누락.
- R-08/BUG-012: 소스 수정은 검증됐으나 HTTPS 빌드 반영 대기.

정확한 근거·보완 순서는 self-review.md. 과거 테스트 실패0은 현재 코드에 미해결 항목이 없다는 의미가 아니다.

## 자체 점검 보완 상태 (2026-09-24)

- R-02 FIXED(소스/회귀): PATCH version 갱신과 재조회 반영. 연속 변경·보관,412 초안 보존, 보관 응답 유실 통과.
- R-03/R-04 FIXED(소스/회귀): 초기 데이터가 지도보다 먼저 도착하는 경우,409 재조회 순서, 삭제 Marker 종료 통과. 수정 전3FAIL→수정 후PASS 근거 보존.
- R-05/R-06/R-07 IMPLEMENTED/LOCAL VERIFIED: 위치 지도 미리보기, 통계 범위·건수, SDK 고정 코드 관측 및 timeout 보완. Frontend38·Backend37/DB11·생산 Mock E2E2PASS. 실제 외부/실기기 검증 범위는 별도다.
- R-01 PARTIAL: 격리 복원·재삭제·세션 폐기 및 복원 모드503 차단 구현/훈련 PASS. 운영 최신성 증명·암호화/WAL/설정 강제 미완료.
- R-08/BUG-012 SOURCE BUILD VERIFIED, HTTPS UPDATE PENDING: 독립 생산 빌드/Mock 회귀 성공. 기존 사용자 연결을 유지해 휴대폰 HTTPS 실행본은 교체하지 않았다.

복원 도구의 합성 훈련 중 CSV 줄바꿈/ISO 소수 초 호환 오류는 수정 후 통과. 운영 DB 장애나 데이터 손상이 발생한 사건은 아니다.
