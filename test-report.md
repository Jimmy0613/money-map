# 실제 테스트 보고서

> 최신 종합 점검(2026-10-18): **개발·검증 진행 중, 배포 준비 완료 아님.** 복원 기본 차단·서명 응답 검증·독립 상태 제어 도구를 구현했습니다. 2026-09-25 최종 실행 근거는 Backend 단위·HTTP42/실제 PostgreSQL 통합24/제어 도구11PASS 및 bootJar 성공입니다. Frontend55/생산 Mock2는 이전 화면 보완 실행 근거입니다. 상위 추적표33PASS/38NOT_RUN 유지. [복원 실행 계약](recovery-authority-contract.md)에 구현과 남은 운영 검증기·배포 연결을 구분합니다. 아래 날짜별 기록은 당시 이력입니다.

기록일: 2026-09-19 (Asia/Seoul). 대상: `/Users/lmj/money-map`의 신규 Backend/Frontend 및 V1~V5. 승인된 원본 15개를 읽고 환경 점검 후 구현했다. DOCX 6개는 해시 재검사에서도 원본과 일치하며 Markdown 원본은 `evidence/original-documents/`에 보존했다. Git 저장소가 없어 commit ID 대신 최종 소스 SHA manifest를 남긴다.

## 판정과 범위

**CONDITIONAL PASS — 로컬 핵심 구현·실행 기준. 전체 요구사항 완료·출시 PASS는 아님.** 앱 실행 및 핵심 거래·인증·실패 복구 흐름은 실제 DB와 브라우저에서 통과했다. 71개 상위 계획은 복합 기대값을 포함하므로 자동 테스트 개수와 일대일 대응하지 않는다. 일부 하위 검증만 통과한 상위 사례는 NOT_RUN을 유지했다. 실제 Kakao/실기기/운영 복원과 남은 세부 회귀 검증을 전부 수행한 상태가 아니다. FR-027은 Could 선택 범위 미포함이다.

## 환경과 재현 명령

macOS 26.2 arm64, Corretto Java 21.0.3, Gradle Wrapper 8.14.3, Node 22.22.0, Spring Boot 3.5.16, Next 16.3.5, Docker Desktop 4.91.0, Engine 29.8.0, Compose 5.5.1, PostgreSQL 17.6. 네트워크/서버 소켓 명령은 sandbox 외 실행 승인을 거쳤다. 환경 점검 이력은 environment-check.md.

프로젝트 루트 기준:

```sh
export PATH="$PWD/.tooling/node/node_modules/node/bin:/Applications/Docker.app/Contents/Resources/bin:$PATH"
export GRADLE_USER_HOME="$PWD/.tooling/gradle"
export PLAYWRIGHT_BROWSERS_PATH="$PWD/.tooling/playwright"
backend/gradlew -p backend test integrationTest bootJar --no-daemon
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix frontend run e2e
python3 scripts/check-compose.py
python3 scripts/audit-local-evidence.py
```

실제 E2E는 `python3 scripts/run-local.py db`, `backend`, `frontend`로 별도 프로세스를 먼저 시작하고 실행한다. 테스트는 새 UUID 합성 계정을 만들며 성공 경로에서 해당 계정만 탈퇴한다. 사용자 계정을 테스트 대상으로 선택하지 않는다. 테스트 실패 시 합성 계정이 남을 수 있으며 이를 이유로 DB 전체를 정리하지 않는다.

```sh
cd frontend
npx playwright test --config playwright.real.config.ts
```

## 1차 실행 결과 (이력)

| 검증 | 실제 결과 | 증거 |
|---|---|---|
| Backend 단위/Mock HTTP | 32 PASS, 0 FAIL | `evidence/backend-verification-final.log`, `backend/build/test-results/test/TEST-*.xml` |
| 실제 PostgreSQL Testcontainers | 8 PASS, 0 FAIL | `evidence/backend-integration.log`, `backend/build/test-results/integrationTest/TEST-com.moneymap.PostgresIntegrationTest.xml` |
| Frontend Unit/RTL/MSW | 16 PASS, 0 FAIL | `evidence/frontend-tests.log` |
| Mock API Chromium E2E | 2 PASS, 0 FAIL | `evidence/frontend-e2e.log` |
| 실제 API/DB Chromium E2E | 2 PASS, 0 FAIL | `evidence/frontend-real-e2e.log`, `frontend/e2e-real/live-flow.spec.ts` |
| Frontend typecheck/lint/build | PASS | `evidence/frontend-typecheck.log`, `frontend-lint.log`, `frontend-build.log` |
| Backend bootJar | PASS | `evidence/backend-integration.log` |
| Compose 설정 | PASS, 치환 Secret 출력 없음 | `evidence/compose-validation.log` |
| DB/Backend/Frontend 실제 실행 | PASS; 재시작 시 V1~V5 재검증, Migration 재적용 불필요 | `evidence/local-backend.log`, `local-frontend.log`, `readiness.json` |
| 로컬 로그/알려진 Secret 검사 | PASS, 검사 범위 한정 | `evidence/log-audit.json` |
| 실제 Kakao/모바일 실기기/운영 복원 | NOT_RUN | 키/허용 도메인, HTTPS 실기기, 운영 호스트·백업 정책 실행 환경 미준비 |

성능 측정을 포함한 자동 테스트 총 61개 PASS (단위/통합/브라우저60 + 부하1). 자동 테스트의 성공 개수를 전체 Must 완료율로 사용하지 않는다. 초기 Docker 미설치 실패와 CHAR 매핑 실패는 버리지 않았으며 각각 `evidence/initial-docker-blocked/`, `evidence/integration-schema-failure.xml`에 보존했다. 수정/재실행은 bug-report.md 참조.

## 실제 검증한 사용자 시나리오

- DB: 가입·기본 분류16개, 로그인/세션 확인, refresh 회전과 재사용 가족 폐기, 사용자 간 거래 격리와 분류 복합 FK 거부.
- DB: 멱등 POST 동시2회/한 거래, 삭제 후 같은 키 부활 거부, 같은 버전 동시 PUT에서200/412, 사용자 분류 변경/보관과 기존 참조 유지.
- F0: 9월6건 페이지 중복 없음, 수입150000/지출29000/잔액121000, 식비16000/쇼핑10000/교통3000, P1 소비16000, 지도 영역19000/기간29000, 지역 CODE/UNKNOWN 분리, 월 차이21000, 커서 사용자·revision 검사, 위치 제거 및 수정 후 지출32000.
- 지도 DB: level5의501개 묶음에 빈 items/합계 유지, 날짜 변경선 양쪽 경도 포함·중간 제외. 실제 SDK Cluster 검증은 아니다.
- 탈퇴 DB: ledger 삽입과 사용자/cascade 삭제 commit, ledger 실패 주입 시 사용자와 거래 rollback. 운영 백업 복원은 별도 미실행.
- 실제 브라우저: 가입→로그인→위치 없는12000원 생성→15000원 수정→새로고침 확인→삭제→로그아웃→다시 로그인→합성 계정 탈퇴. 상세 화면360/390/768/1440px 가로 넘침 없음.
- 실제 장애 주입: 서버 commit은 완료시키고 HTTP 응답만 유실. POST 동일 키 재시도, PUT 재조회 일치·초안 유지, DELETE 후GET404 안내. 로그아웃 대기를 다른 탭과 새로고침에서 확인하고204 재시도로 해제.
- 위치 Mock: 키 없음 fallback, 위치 권한 거부/10초 timeout 설정, accuracy1000/1000.001 경계, 명시적 오차 확인,6자리 반올림. 실제 위치 권한이나 Kakao 성공을 주장하지 않는다.

## 보안·정밀도·운영

CSRF 누락/잘못된 Origin/미인증/타인 객체/알 수 없는 JSON/초과 본문을 검증했다. 실제 HTTPS 설정의 Secure/HttpOnly 쿠키는 MockMvc로 확인하고 실제 localhost 브라우저는 의도된 local 프로필에서 Secure=false를 사용했다. 운영 HTTPS 검증은 남아 있다.

PostgreSQL에서 최대 금액10000건의 합계 `9999999999990000`을 정확한 문자열로 확인했고 프런트 BigInt 표시도 통과했다. 윤년·월말·연말·서울 자정 계산을 검증했으나 모든 API 날짜/감사 시각 경계를 고정 Clock으로 검증한 것은 아니다.

로컬 앱 로그의 requestId/템플릿 경로/status/durationMs/code, 합성 이메일·인증 쿠키 부재 및 알려진 로컬 Secret 값의 로그/정적 JS/테스트 XML 미포함을 검사했다. 모르는 모든 Secret, 별도 프록시, 운영 수집기를 포괄하는 검사는 아니다. 토큰과 좌표를 브라우저 localStorage에 저장하지 않으며 logoutPending boolean만 사용한다.

## 남은 검증과 사용자 준비

| 항목 | 필요 시점/이유 | 이미 수행 | 사용자 작업/환경변수 | 차단 범위 |
|---|---|---|---|---|
| Kakao 키·허용 도메인 | 실제 지도/검색/주소/Cluster 검증 전 | adapter와 실패 UX/Mock, 공식 지역 자료 구성 | 앱 JavaScript 키를 `.env`의 NEXT_PUBLIC_KAKAO_MAP_APP_KEY에 설정·허용 도메인 등록. 대화에 비밀값 전송 불필요 | 실제 Kakao만 |
| HTTPS와 실기기 | 위치 허용/거부·터치·호환성 검증 전 | Chromium viewport/위치 Mock | 사용할 iOS/Android 기기와 HTTPS 접근 환경 | 실기기 검증 |
| 운영 호스트·백업 | 배포 전 | ledger 원자성 테스트, README의 복원 요구 기록 | 호스트/도메인/백업 저장소 선택·권한 | 실제 배포·최신 ledger 복원 훈련 |
| 나머지 회귀/접근성 | 전체 Must 판정 전 | 핵심 자동 검증 및 보수적 추적표 | 지금 필요한 제품 결정 없음 | 전체 완료 판정; Agent가 계속 보완할 항목 |

71개 추적표의 NOT_RUN 상세에는 검색 역순 응답, 지도 필터 왕복, 모든 level cap, 범위·소유권 조합, 스크린리더/포커스, 부하 실행 계획, 실기기, 운영 복원 등이 포함된다. 실제 지역 코드512개·현존 이름269개는 공식 다운로드에 근거하지만 과거 이름별칭의 유효 기간은 추정하지 않았다.

## API 성능

`performanceTest`는 별도 Testcontainers DB에 사용자10명·각10000건을 넣고, 실제 HTTP를10개 worker로1분 워밍업 후5분 측정한다. 응답 본문·쿠키는 기록하지 않고 route별 count/p95와 HTTP 오류 수만 남긴다. 실행 명령: `backend/gradlew -p backend performanceTest --no-daemon`. 완료된 측정 결과를 아래에 기록한다.


실제 결과: **PASS**, 사용자10명×10000건, 워밍업60초+측정300초, HTTP 오류0. macOS에서 로컬 E2E와 개발 서버도 실행 중이었던 조건이다.

| 경로 | 측정 요청 수 | p95(ms) | 기준(ms) |
|---|---:|---:|---:|
| /transactions | 1375 | 207 | 500 |
| /statistics/summary | 1374 | 435 | 1000 |
| /statistics/categories | 1378 | 476 | 1000 |
| /statistics/places | 1375 | 440 | 1000 |
| /statistics/regions | 1373 | 509 | 1000 |
| /statistics/monthly-comparison | 1373 | 566 | 1000 |
| /map/markers | 1376 | 689 | 1000 |


증거: `evidence/backend-performance.log`, `evidence/performance-summary.json`, `backend/build/test-results/performanceTest/TEST-com.moneymap.PerformanceTest.xml`. 기본분류 하나·동일 월 지출·수동 위치의 고정 합성 분포이며 다양한 장소/기간의 모든 최악 분포를 대표하지 않는다. 상세 EXPLAIN 계획 및 실서비스 네트워크/TLS/인프라 측정은 미실행이다. 통계는 현재 기간의 행을 Java에서 집계하므로 데이터 분포 확대 시 재측정이 필요하다.

## 이전 실행 집계 (추가 보완 전)

원본71개 상위 사례: PASS11 / FAIL0 / NOT_RUN60 / BLOCKED0. NOT_RUN 중2개는 FR-027 선택 범위 제외이며, 나머지에는 부분 통과와 미실행 하위 기대가 함께 들어 있다. 자동61개 통과와 상위11개 완전 확인은 서로 다른 집계다. 남은 회귀·접근성/외부 연동/운영 확인 때문에 전체 Must 완료를 선언하지 않는다. 코드와 환경이 실제 실행 가능하고 핵심 흐름은 통과했으므로 로컬 진행은 CONDITIONAL PASS로 판단한다.


## 추가 보완 검증 (2026-09-19)

사용자의 계속 진행 요청에 따라 서버 필드 오류, 입력란 접근성, 위치 비동기 경합, 미실행 DB 경계와 실행 계획을 보완했다. 아래가 최신 회귀 결과다. 이전61개 및 상위11개 표기는 이전 실행 이력이다.

| 검증 | 최신 결과 | 증거 |
|---|---|---|
| Backend 단위/Mock HTTP | 34 PASS | evidence/followup-backend.log, backend/build/test-results/test/ |
| PostgreSQL 통합 | 11 PASS | evidence/followup-backend.log, backend/build/test-results/integrationTest/ |
| Frontend 단위/Component | 24 PASS | evidence/followup-frontend-tests.log |
| Mock 브라우저/키보드 | 2 PASS | evidence/followup-e2e.log |
| 실제 API/DB 브라우저 | 2 PASS | evidence/followup-real-e2e.log |
| typecheck/lint/양쪽 build | PASS | evidence/followup-typecheck.log, followup-lint.log, followup-build.log, followup-backend.log |

**이번 회귀73개 PASS, FAIL0.** 기존 부하 테스트1개 PASS를 더하면 기록된 자동 사례는74개다. 조회/집계 코드는 이번에 변경하지 않아6분 부하는 반복하지 않았다. 기존 성능 수치는 이전 실행 환경의 증거이며 새 측정값으로 제시하지 않는다.

- 오류 응답 `fields`에 field/code/message를 담고 Bean Validation의 rejectedValue나 원문 메시지는 반환하지 않는다. 가입과 거래 입력에서 알려진 필드를 입력란 오류로 연결하고 aria-describedby로 읽을 수 있게 했다. 예: amount OUT_OF_RANGE, passwordConfirmation MISMATCH. 파싱 불가 JSON/일반 예외는 전체 오류로 유지한다.
- 위치 창을 닫은 후 도착한 이전 Geolocation 응답을 무시한다. 검색어 삭제 시 진행 표시를 정리하고, 역변환 완료가 사용자의 낮은 정확도 동의를 되돌리지 않도록 했다.
- 검색2글자/300ms, 역순 응답 폐기, 빈 검색/오류/좌표만 확정, 렌더·창 열기 때 수집0회, 요청 버튼 후1회/watchPosition0회 PASS. 실제 Kakao 성공 검증은 아니다.
- Chromium에서 Enter로 위치 창 열기, Escape 닫기, 원래 버튼 포커스 복원, 등록·상세4개 폭, 잘못된 금액의 접근성 설명과 제출 차단 PASS. 전체 스크린리더/실기기 접근성 전수는 아니다.
- 실제 DB에서 가입 기본11/5, 정규화 중복409, 잘못된 인증 동일성, 다른 POST 본문409, 유형 FK/타인 CRUD, 기본/중복 분류 제한, F0 비율과 삭제 합계/위치행, 모든 지도 cap 전후를 확인했다.
- 격리된10000행 목록 EXPLAIN ANALYZE:21행 반환, transaction_filter Bitmap Index Scan, 정렬 포함14.411ms. SQL 본문/파라미터를 출력하지 않고 노드 종류·인덱스명·시간만 evidence/query-plan-summary.txt에 남겼다. 지도/통계 SQL 전체 계획 검증은 남아 있다.

최신 상위71개 상태: PASS25, FAIL0, NOT_RUN46, BLOCKED0. 미실행은 실제 지도/실기기/운영 복원뿐 아니라 일부 세부 회귀도 포함한다. FR-027 두 사례는 선택 범위 미포함이다. 최종 제품 판정은 계속 **CONDITIONAL PASS**이며 전체 Must/출시 완료로 승격하지 않는다.


## Kakao 실제 연결 확인 (2026-09-21)

사용자가 카카오맵 ON, localhost 도메인 등록, .env 키 설정을 완료했다고 알린 뒤 키 값 출력 없이 SET 여부만 확인했다. 이 작업의 프런트엔드를 재시작하고 실제 Kakao SDK를 호출했다.

`frontend/node_modules/.bin/playwright test --config frontend/playwright.kakao.config.ts`: **1 PASS** (`evidence/kakao-live-e2e.log`). Mock을 사용하지 않았다. 지도 직접 선택 미리보기, 공개 장소 서울시청 검색, 주소/법정동 지역코드 변환, SEARCH 위치를 포함한 합성1000원 거래 저장, 실제 지도 Marker 이미지 클릭과 상세 조회, 확대 버튼을 확인했다. SDK 로드는 해당 SPA 흐름에서1회. 지도 창의 HTML 이미지맵 area는 Playwright 가시성 판정 대상이 아니어서 연결된 실제 Marker 이미지를 클릭하도록 계측을 수정한 뒤 통과했다.

키·토큰·전체 요청 URL은 출력하지 않았고 trace/video/screenshot 저장을 껐다. 현재 위치를 수집하지 않았다. 성공 회차의 새 합성 계정은 앱 탈퇴 기능으로 삭제했다. 중간 실패 회차는 합성 데이터가 남을 수 있으며 원본 DB를 초기화하지 않았다.

기존74개 자동 사례에 실제 Kakao1개가 추가되어 기록된 사례는75개다. 기존 회귀/부하를 오늘 다시 실행한 것으로 간주하지 않는다. 실제 SDK Cluster 대량 표시/실기기 위치/모바일 터치/운영 복원까지 통과한 것은 아니다. 사용자 준비1단계(Kakao 기본 연결)는 완료했다.


## 격리 HTTPS 검증 (2026-09-21)

실제 Chromium HTTPS E2E2개 PASS(17.5초): 가입/로그인, 위치 없는 거래 CRUD, 정상 로그아웃, commit 후 POST/PUT/DELETE 응답 유실 복구, 로그아웃 유실·새로고침·다중 탭 재시도, 합성 계정 탈퇴. evidence/mobile-https-verified-e2e.log 참조. 기존 사례 재실행이므로 고유 테스트 수에 중복 합산하지 않는다.

초기1PASS/1FAIL은 테스트가 logoutPending 화면만 보고 실제 응답 유실 전에 새로고침할 수 있던 순서 문제였다.204 commit/route.abort 완료와 오류 표시 후 새로고침하도록 테스트를 보완해 최종2PASS. 실패/진단 로그 보존. 실패한 합성 계정은 별도 테스트 DB에 남을 수 있으며 초기화하지 않았다. 앱 인증 코드는 변경하지 않았다.

추가 HTTP 점검: 로그인200, CSRF200/no-store/Secure/SameSite=Lax, 미인증401, foreign-Origin403 PASS(evidence/mobile-https-security.json). 새 도메인 Kakao 등록 및 실제 Galaxy S25/Android16 위치 허용·거부·터치/iOS 검증은 대기다. 상위26PASS/45NOT_RUN 유지.


2026-09-21 HTTPS Kakao 도메인 등록 후 검증: 사용자 등록 완료 보고 뒤 실제 HTTPS Chromium에서 지도/장소 검색/주소·지역 코드/위치 저장/Marker 상세/합성 계정 탈퇴 E2E1개 PASS(11.7초). evidence/mobile-https-kakao-e2e.log. 실제 Galaxy S25의 GPS·위치 거부·터치 검증은 아직 대기이며 상위 추적표 상태는 유지한다.


2026-09-21 Galaxy S25 / Android16 Chrome 수동 관찰(사용자 보고): HTTPS 로그인 완료, 현재 위치 미리보기 표시, 오차 약98.400m, 주소 없음. GPS 좌표 수신은 확인됐으나 주소 조회 완료/실패 여부와 실제 저장은 아직 미확인. 실제 주소·좌표는 수집하지 않았다. 코드상 역주소 조회 전에 좌표 미리보기가 먼저 표시되며 지번 주소만 있는 경우 미리보기의 표시 후보에 포함되지 않는 점을 확인했다. 원인은 아직 확정하지 않음.


2026-09-21 Galaxy S25/Android16 Chrome 사용자 후속 보고: 새로고침 후 현재 위치 주소 표시 성공. 최초 미표시의 원인은 확정하지 않았다. GPS 미리보기(오차 약98.4m)와 주소 표시는 수동 확인, 실제 저장·거부 시나리오는 아직 대기. 실제 좌표/주소는 수집하지 않았다.

별도 코드 점검으로 지번 주소만 있는 경우 미리보기/확정 결과의 표시 후보에서 누락되는 결함 보완. 주소 결과가 없으면 좌표 저장/장소 검색 대안을 안내한다. 합성 주소 회귀2개 추가 후 프런트26PASS 및 typecheck PASS(evidence/address-fallback-tests.log). 현재 실행 중인 HTTPS 생산 빌드에는 이 보완을 아직 반영하지 않았으며, 사용자 성공은 기존 빌드의 새로고침 후 관찰이다.


2026-09-21 Galaxy S25 / Android16 Chrome 수동 검증(사용자 보고): 현재 위치를 확정한1,000원 거래의 금액·주소 저장 확인, 지도 Marker를 눌러1,000원 거래 표시 확인. 실제 좌표·주소는 수집하지 않았다. 실제 기기의 위치 허용→주소 표시→저장→지도 Marker 상세 경로를 확인했으며 위치 권한 거부·위치 없는 저장은 다음 단계로 남는다.


2026-09-23 Galaxy S25 / Android16 Chrome 수동 검증(사용자 보고): 사이트 위치 권한 차단 후 현재 위치 요청 시 “위치 권한이 거부되었습니다. 브라우저 권한 설정을 확인하거나 위치 없이 계속하세요.” 표시 확인. 권한 거부 안내는 확인됐으며, 거부 상태에서 위치 없이 거래 저장은 아직 대기.


2026-09-23 Galaxy S25 / Android16 Chrome 사용자 확인: 위치 권한 차단 상태에서 위치 없이 계속하기 후2,000원 거래 저장, 상세의2,000원·위치 없음 표시 확인. Android 실제 기기에서 위치 허용→주소→저장→Marker 상세와 거부→위치 없는 저장을 수동 확인했다. Chrome 정확한 버전/최신2버전 범위, iOS Safari,10초 timeout·입력 보존 전수 등은 미확인. 상위 복합 기준을 임의 PASS로 변경하지 않는다.


2026-09-23 사용자 준비 확인: 테스트 가능한 iPhone/iPad 없음. iOS Safari 실기기 검증은 미실행으로 유지하며 Android 또는 데스크톱 에뮬레이션으로 대체 통과시키지 않는다. Android Chrome 정확한 버전 확인을 다음 준비 단계로 안내한다.


2026-09-23 테스트 기기 버전 확정(사용자 보고): Galaxy S25 / Android16 / Chrome153.0.8010.52. 위치 허용·주소 포함1,000원 저장·Marker 상세 및 권한 거부 후2,000원 위치 없는 저장 수동 확인. Chrome 최신2개 주요 버전 모두의 검증을 의미하지 않으며 iOS 기기 없음으로 Safari 미실행.

## 자체 점검 후 보완 검증 (2026-09-24)

최종 소스에 대해 다음을 실제 실행했다. 서로 다른 범주의 결과이며 과거 성능/실제 Kakao/휴대폰 기록을 이번 실행에 합산하지 않는다.

| 검사 | 실제 결과 | 증거 |
|---|---|---|
| Frontend 단위·컴포넌트 | 7파일38PASS, 실패0 | evidence/review-frontend-tests.log |
| TypeScript / ESLint | 각각 종료0 | evidence/review-typecheck.log, evidence/review-lint.log |
| Backend 단위·HTTP | 37PASS, 실패0/오류0/skipped0 | evidence/review-backend-tests.log, backend/build/test-results/test |
| 실제 PostgreSQL 통합 | 11PASS, 실패0/오류0/skipped0 | 동일 로그, backend/build/test-results/integrationTest |
| Backend bootJar | BUILD SUCCESSFUL | evidence/review-backend-tests.log |
| 독립 Frontend 생산 빌드 | 성공 | evidence/review-production-build.log |
| 생산 standalone Chromium Mock E2E | 2PASS,2.7초 | evidence/review-production-e2e.log |
| 새 격리 DB 복원 훈련 | 8개 확인 항목 PASS | evidence/review-restore-test.log |

재현 근거: R-02/03/04 신규3개 테스트는 수정 전 모두 실패(evidence/review-regressions-before.log)했고 초기 수정 후3PASS(evidence/review-regressions-after.log)였다. 이후 삭제 Marker·미리보기·통계 범위·412·보관 유실 검사를 추가해 최종 회귀 파일은9PASS다. 지도 오류 장애 주입3개는 SDK 실패 후 재시도, 검색 실패/timeout, 역주소 오류와 결과 없음 구분을 검증한다. Backend 추가3개는 코드별 집계·제한, JSON 허용 목록/미인증 거부, 복원 모드 차단을 검증한다.

복원 훈련의 CSV 줄바꿈 및 macOS Python ISO 소수 초 호환 문제를 수정한 뒤 최종 통과했다. 마지막 실패 기록은 evidence/review-restore-before.log에 보존했다. 기존 DB는 연결/복원/초기화하지 않았으며 네트워크 차단된 신규 합성 컨테이너만 생성·정리했다. 최신성 증명이 없는 후보는 항상 공개 불허다. 운영용 최신 ledger/WAL 검증을 통과했다는 뜻은 아니다.

재현 명령: `GRADLE_USER_HOME="$PWD/.tooling/gradle" backend/gradlew -p backend test integrationTest bootJar --no-daemon`, Node22 PATH에서 `frontend/node_modules/.bin/vitest run --root frontend`, frontend에서 `npm run typecheck`, `npm run lint`, `python3 scripts/test-restore.py`, `python3 scripts/check-frontend-release.py`. 마지막 스크립트는 .tooling 아래 별도 복사본과3002 포트만 사용하고 기존 .next/HTTPS 서버를 보존한다. Mock 빌드는 합성 지도 키만 사용하며 .env를 읽지 않는다.

남은 조건: 수정본의 실제 Kakao·휴대폰 HTTPS 재확인, iOS/브라우저 범위, 전체 접근성/변경 API 부하 등 기존 복합 기준, 운영 환경의 독립 암호화 백업·WAL 최신성·30일 보존 및 배포 설정 강제. 71개 상위 사례26PASS/45NOT_RUN은 이번 하위 회귀만으로 변경하지 않았다. 전체 완료/배포 완료 판정은 하지 않는다.

## 추가 자율 검증 (2026-09-24~25)

사용자 개입 없이 가능한 추가 검사를 수행했다. 제품 수정은 지도 재조회 실패 시 이전 결과 보존이며, 그 밖에는 검증을 보강했다. 기존 DB/.env/휴대폰 서버는 변경하지 않았다.

- Frontend43PASS: 지도 재진입 기간·중심 복원, 응답 역전 무시, 조회 오류 시 이전 결과 유지, 위치 거부/timeout 후 금액·날짜·메모 보존 및 위치 없는 제출을 추가했다. 증거: evidence/autonomous-frontend-tests.log. 지도 실패 재현은 evidence/autonomous-map-state-before.log(1FAIL/1PASS)에 보존했다.
- Backend 단위·HTTP38PASS: 합성 본문·이메일·query·좌표·쿠키·경로값이 RequestFilter 로그에 포함되지 않는지 추가 확인했다. 운영 프록시 로그 검증을 대신하지 않는다. 증거: evidence/autonomous-backend-unit.log.
- 실제 PostgreSQL 통합13PASS: F0 지도 건수/장소 필터 비율/지역 합계/월별 원금액 대조, 타인 Place 복합 FK 거부·기존 연결 보존, 두 달 분류·지역 합집합과 이전0 비교를 검증했다. 증거: evidence/autonomous-db-checks.log.
- 2026-09-24 변경 API 부하1PASS: 10명×기존 거래10000건,60초 준비+300초 측정, closed-loop. 독립 PostgreSQL에서 측정했으며 그동안 다른 빌드/테스트 부하를 실행하지 않았다. 루프백 HTTP 전체 왕복시간이므로 순수 서버 처리시간과 구별한다. 모두500ms 기준 이하, HTTP 오류0이다.

| 경로 | 측정 요청 수 | p95 |
|---|---:|---:|
| POST 거래 | 103622 | 10ms |
| GET 거래 상세 | 103624 | 3ms |
| PUT 거래 | 103622 | 10ms |
| DELETE 거래 | 103620 | 7ms |
| PATCH 분류 | 103622 | 8ms |

성능 근거: evidence/autonomous-mutation-metrics.txt 및 autonomous-mutation-performance.log. 기존 조회7개 경로 부하는 재실행하지 않았다. 인증·그 외 모든 경로 또는 운영 호스트 성능 전체를 통과했다고 주장하지 않는다. 최초 테스트 요청에서 필수 nullable 필드가 빠져400이 발생한 준비 오류는 DTO 직렬화로 수정했다(autonomous-mutation-before.txt). 테스트 도구의 잘못된 Testing Library 옵션에 따른 타입 오류도 수정했고 실패 기록을 보존했다.

추적표의 기존71개 ID와 기대값은 유지하고, 기대를 직접 검증한 TC-FR-004-02/018-01/021-02/023-02/025-01/026-01/026-02만 PASS로 변경했다. 현재33PASS/38NOT_RUN. 지도 상세 왕복의 실제 브라우저·기기 경로, 운영 백업/WAL 최신성, 전체 접근성·브라우저 범위 등 미검증 조건은 남아 있다.

2026-09-25 최종 확인: TypeScript/ESLint 종료0, 독립 생산 빌드 및 standalone Mock Chromium E2E2PASS. 증거: evidence/autonomous-typecheck.log, autonomous-lint.log, autonomous-production-build.log, autonomous-production-e2e.log. `python3 scripts/check-frontend-release.py --evidence-prefix autonomous-production`으로 이전 검증 로그와 분리했다.


## 요구사항 세부 수락 검사 (2026-09-25)

REQ-R02~04 보완 후 Frontend55PASS/실패0, TypeScript와ESLint 종료0, 독립 생산 빌드 성공, standalone Mock Chromium E2E2PASS. 증거: evidence/acceptance-frontend.log, acceptance-typecheck.log, acceptance-lint.log, acceptance-production-build.log, acceptance-production-e2e.log. Backend/DB/부하/실제 Kakao·휴대폰·운영 복원은 이번 변경에서 재실행하지 않았다.

기존43개에서 위치 출처3종/저장 대상 좌표 미리보기·해제3개, 미리보기 실패 후 제거1개, 날짜 삭제3개 및 실제 날짜·순서5개를 추가했다. 기존 통계 검사는 최근 거래일·제외 건수 표시와 다음 페이지에서 정렬 변경 시 cursor 초기화/기간 보존까지 확장했다. 기본 시나리오 수는55개이며 세부 assertion 수와 구별한다.

수락 항목별 설계·구현·검증 상태는 acceptance-criteria.md에 기록했다. 복원 제어 계층은 recovery-design.md 상세 설계만 완료했으며 REC-01~08 전체 실행 PASS가 아니다. 위치 지도의 상시 노출을 새 필수 요구사항으로 추가하지 않고 명시적 확인 동작으로 기존 위치를 확인하도록 했다. 원본 상위71개 ID·기대값 및33PASS/38NOT_RUN 유지. 기존 DB·Secret·휴대폰 실행본은 보존했고 이 보완은 휴대폰에 아직 반영하지 않았다.


## 복원 제어 계층 구현·검증 (2026-10-18 재개 확인)

2026-09-25 중단 직전 최종 실행 로그와 JUnit XML을 확인했다. 이번 재개에서는 문서/증거 연결을 마무리했으며 아래 검사를 새로 실행했다고 주장하지 않는다.

| 검사 | 결과 | 근거 |
|---|---|---|
| Backend 단위·Mock HTTP | 42 PASS, 실패0 | evidence/recovery-backend-checks.log, test XML |
| 실제 PostgreSQL | 기존 비즈니스13 + 복원 gate11 = 24 PASS, 실패0 | 같은 로그, integrationTest XML |
| 독립 상태 제어기 | 11 PASS, 실패0 | evidence/recovery-authority-tests.log |
| bootJar | BUILD SUCCESSFUL | evidence/recovery-backend-checks.log |

새 검사는 운영 기본 차단/false 우회 거부/local 제한, Spring 보안 필터 연결, 서명 위조·nonce 재사용·만료·잘못된 대상/환경/배포·장애·응답 크기 제한, DB 교체/재시작/스키마 변경을 포함한다. 제어기는 실제 루프백 HTTP 서명 응답, 초기 미등록, 단계 생략, 세대 CAS·lease, pin되지 않은 검증기, 오래된 세대, 실패한 재삭제 결과, 프로세스 재개, WAL 공백/다른 목표를 나타내는 합성 receipt의 거부를 검증했다.

DB 재시작 테스트의 첫 실행은 Docker의 동적 공개 포트 변경을 반영하지 않아 실패했다. 새 포트를 다시 읽도록 테스트 fixture를 수정한 후 최종 회귀 전체가 통과했다. 실패 로그는 evidence/recovery-backend-restart-fixture-failure.log에 보존했다. 테스트는 전용 Testcontainers DB만 생성·재시작·정리했다.

실제 WAL·최신 삭제 commit 검증기, bootstrap/갱신 실행기, ingress 연결, 운영 암호화/보존, 실기기 새 빌드와 새 gate를 포함한 운영 부하는 미검증/미구현 범위로 유지한다. 기존 부하 수치를 새 권위 서버 왕복이 포함된 성능으로 재사용하지 않는다. Frontend55 및 생산 Mock2는 이전 검증 근거이며 이번 Backend 작업에서 재실행하지 않았다. 상위71개33PASS/38NOT_RUN 유지.


## Neon 실제 연결 검증 (2026-10-18)

공식 Neon 스킬9개/CLI8.8.0/프로젝트 한정 OAuth MCP를 설정했다. 지정 production 브랜치의 Postgres 정책 plan/deploy는 변경 없이 성공했다. 기존 .env 값은 유지하고 Neon 변수3개만 추가했다. 기존 JDBC/Flyway를 유지하는 명시적 neon 프로필을 구현했다. Backend 단위·HTTP46PASS와 bootJar 성공, npm run build 성공, 별도 schema-only Neon 브랜치에서 실제 브라우저 가입·로그인·거래 저장/재조회·통계·탈퇴/세션 거부 PASS다. 상세 변경/실패/증거는 [neon-setup.md](neon-setup.md)를 따른다. 운영 HTTPS 배포와 RecoveryGate의 관리형DB/최초 등록 경로는 미완료다. 원본 상위71개33PASS/38NOT_RUN 판정은 유지한다.


## 개인 계정 접근 제한 검증 (2026-10-18)

명시적 APP_ACCESS_RESTRICTED/APP_ALLOWED_EMAILS 설정으로 가입·로그인·refresh·access 인증을 제한했다. 활성화 상태의 빈 목록은 전부 거부한다. 개인정보는 비공개 배포 환경 파일에만 기록했다. 단위·HTTP51PASS, 실제 PostgreSQL 통합24PASS 및 bootJar 성공이다(evidence/personal-access-checks.log, evidence/personal-access-integration.log). 최초 통합 실행은 Docker 연결 실패였고 Docker Desktop 시작 및 소켓 지정 뒤 재실행 성공했다. 신규 DB bootstrap/운영 개방은 미완료이며 운영 배포 전이다. 강제 차단 유지.


## 신규 DB bootstrap 실제 검증 (2026-10-18)

Backend 단위/HTTP53PASS, 실제 PostgreSQL 통합25PASS와 bootJar 성공. Worker 정책5PASS/공식 Workers Vitest 런타임4PASS, 실제 Cloudflare 테스트 Worker+Neon의 최초 일회 등록/서명/잘못된 대상 거부 PASS. local 우회 없는 prod,neon Java 서버의 실제 서명 수락과 미허용 가입 거부, 허용 합성 계정 signup/login/access/refresh 및 탈퇴/세션 거부 PASS. 별도 프런트 복사본의 npm run build 성공. 증거 목록/실패 수정 경과는 bootstrap-deployment.md 참조. 실제 운영 Render 연결·개방은 이 로컬/격리 검증과 구분한다. 상위71개33PASS/38NOT_RUN 및 전체 복원 미완료 판정 유지.

운영 최초 등록 추가 증거: `evidence/bootstrap-production-registration.log`의 4개 PASS는 실제 운영 Neon 빈 상태/실제 ingress 차단/외부 일회 등록/운영 서명 검증이다. Render 환경 적용 뒤의 공개 서비스 정상 응답이나 개인 계정 로그인 완료 증거와 구분한다.

Render 환경 적용 뒤 실제 liveness/readiness와 Vercel csrf 200 및 허용 이메일의 공개 signup 403 REGISTRATION_CLOSED를 추가 확인했다(`evidence/bootstrap-live-deployment.json`, `evidence/bootstrap-public-signup-closed.json`). 동의/계정을 생성하지 않는 거부 검사다. 로컬 본인 등록 화면은 loopback에서 200으로 열었으나 실제 본인 가입/배포 로그인/실사용 저장은 사용자 입력 이후 확인 대상이다.
