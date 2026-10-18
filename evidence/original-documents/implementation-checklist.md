# 구현 체크리스트

## 범위와 사용 방법

이 문서는 승인된 요구사항과 설계를 실제 구현 순서로 연결하는 작업 계획이다. 모든 체크박스는 미완료다. Windows에서는 구현·도구 설치·DB·빌드·테스트 실행을 하지 않는다. requirements.md, architecture.md, erd.md, api-spec.md 및 change-log.md가 우선 기준이고, 이 문서는 새로운 제품 요구를 추가하지 않는다.

예정 코드는 backend/, frontend/, docker-compose.yml이다. 문서의 '예정 파일'은 생성 완료를 의미하지 않는다. 단계 완료는 코드 존재뿐 아니라 해당 테스트의 실제 결과가 있을 때 판정한다. 각 FR 테스트는 requirement-test-matrix.md의 동일 ID로 찾는다.

## 단계별 작업과 완료 조건

### I-00 맥북 환경 확인

- [ ] 사용자가 연 실제 macOS 프로젝트 루트와 기존 변경을 확인한다.
- [ ] 06 사전 샌드박스 프롬프트를 적용하고 environment-check.md를 기록한다.
- [ ] 네 설계 기준 문서와 CHG-001~004, 이 체크리스트, 테스트 추적표를 읽는다.
- [ ] FAIL이면 구현하지 않고 원인과 필요한 조치를 보고한다. CONDITIONAL PASS이면 영향받지 않는 작업만 진행한다.

선행: macOS 환경. 완료 조건: 실제 확인한 경로와 권한·도구·Docker 판정이 기록됨. 현재 상태: SKIPPED - Windows 문서 작업 모드.

### I-01 프로젝트와 공통 설정

- [ ] Java 21, Gradle Wrapper, Spring Boot 안정 버전과 npm lockfile을 호환성 확인 후 고정한다.
- [ ] frontend/backend 기본 구조, TypeScript strict, 포맷·정적 검사 설정을 작성한다.
- [ ] .gitignore와 placeholder만 담은 .env.example을 작성한다.
- [ ] Compose db와 app 프로필, localhost 바인딩, pgdata, 시작 순서·healthcheck를 작성한다.
- [ ] 실제 Secret 누락은 값 노출 없이 오류로 처리하고 Kakao 공개키 누락은 기능별 대체 상태로 처리한다.

선행: I-00. 예정 파일: backend/build.gradle, Gradle Wrapper, frontend/package.json 및 lockfile, docker-compose.yml, .env.example, .gitignore. 완료 조건: 비밀값 유출 없이 설정 해석·기본 빌드가 가능하고 도구 버전이 README에 명시됨. 관련: NFR-007, NFR-012.

### I-02 데이터 모델과 Migration

- [ ] V1 사용자·카테고리, V2 지역 사전·별칭·장소·거래·위치, V3 인증·멱등성·privacy ledger, V4 인덱스 순서로 작성한다.
- [ ] 사용자 복합 FK, 카테고리 유형 FK, 좌표·금액 CHECK, active refresh partial UNIQUE를 반영한다.
- [ ] 지역 사전의 출처·기준일·자료 버전 및 특수 행정구역 검증 결과를 기록한다.
- [ ] 합성 fixture와 성능 seed를 test 전용으로 분리한다.
- [ ] Testcontainers 빈 DB에서 Migration 및 JPA validate, 다른 사용자 FK 거부, 탈퇴 cascade를 확인한다.

선행: I-01. 예정 파일: backend/src/main/resources/db/migration/V1~V4 SQL 및 도메인/Repository. 완료 조건: erd.md 모든 PK/FK/UNIQUE/CHECK가 실제 DB에서 검증됨. 지역 사전 검증이 미완료면 실제 지역 통계 완료로 보고하지 않는다. 관련: FR-004~FR-017, FR-025, FR-028, NFR-014/015.

### I-03 API 기반과 인증

- [ ] 공통 DTO envelope, 예외 코드, requestId, no-store, 개인정보 없는 로그를 구현한다.
- [ ] 가입·기본 카테고리 생성, 비밀번호 해시, 로그인·refresh 회전·세션 확인을 구현한다.
- [ ] Origin·CSRF·쿠키·Rate Limit과 사용자 소유권 필터를 구현한다.
- [ ] 로그아웃과 탈퇴, ledger와 사용자 삭제의 원자성을 구현한다.
- [ ] refresh 재사용·동시 호출·로그아웃 후 기존 토큰 거부·교차 사용자 공격을 검증한다.

선행: I-02. 관련: FR-001~004, FR-028, NFR-005~009/013. 완료 조건: 인증 정상·실패·격리 테스트 통과 및 실제 로그의 민감값 부재 확인.

### I-04 거래와 분류 API

- [ ] 금액 문자열, 날짜, 위치 DTO와 정규화·검증을 구현한다.
- [ ] POST Idempotency-Key와 operation_keys, 사용자 잠금 및 revision 갱신을 구현한다.
- [ ] 거래 CRUD, If-Match, 서명 커서와 CURSOR_STALE을 구현한다.
- [ ] 기본 분류 제한, 사용자 분류 생성·변경·보관 및 기존 거래 참조를 구현한다.
- [ ] Location null/교체와 고아 Place 정리, 검색 ID 소유자별 upsert를 구현한다.

선행: I-03. 관련: FR-005~017, NFR-010/014/015. 완료 조건: F0 fixture CRUD 결과, 금액·좌표·날짜 경계, 중복 제출·동시 수정 사례가 일치.

### I-05 지도와 통계 API

- [ ] BBox·기간·카테고리 필터와 level별 cap, 초과 안내를 구현한다.
- [ ] Marker와 상세에 동일 BBox·revision을 적용한다.
- [ ] summary/categories/places/regions/monthly-comparison을 구현한다.
- [ ] 지역 원본 스냅샷과 canonical_region_key를 분리하고 별칭의 유효 기간을 적용한다.
- [ ] 수입 포함 일반 합계와 지출 전용 지도·소비 통계·상세를 구분한다.

선행: I-04 및 검증된 사전. 관련: FR-018~026. 완료 조건: F0 예상 합계와 모든 통계/상세가 일치하고 지역 중복 분산·미분류 누락이 없음.

### I-06 Frontend 인증과 입력

- [ ] API Client, Query cache, 폼/서버/지도 상태 경계를 구현한다.
- [ ] 가입·로그인·내역·상세·등록·수정·삭제·분류 화면을 구현한다.
- [ ] logoutPending 저장·부트스트랩 우선 검사·탭 간 전파·늦은 응답 폐기를 구현한다.
- [ ] PUT/PATCH/DELETE 응답 유실 시 현재값 재조회 및 초안 보존을 구현한다.
- [ ] Loading/Error/Empty, 필드 오류와 중복 제출 방지를 구현한다.

선행: I-03/I-04 API 계약. 컴포넌트 개발은 MSW로 독립 가능하지만 실제 통합 완료로 보고하지 않는다. 완료 조건: TC-FR-001~012 및 TC-FR-028 핵심 화면 흐름 통과.

### I-07 Frontend 위치와 소비 분석

- [ ] MapAdapter, SDK 1회 로드, 키 미설정·실패·검색 없음 상태를 구현한다.
- [ ] 현재 위치·검색·지도 직접 선택·제거와 1000m 경고 기준을 구현한다.
- [ ] MarkerClusterer, 접근 가능한 결과 목록·Bottom Sheet·필터 복원을 구현한다.
- [ ] 통계 화면과 type=EXPENSE를 보존하는 장소/지역 상세 이동을 구현한다.
- [ ] 360px 반응형, 키보드, 포커스, 44px 터치 영역을 확인한다.

선행: I-05/I-06. 완료 조건: TC-FR-013~026 및 NFR-001/002가 Mock 환경에서 통과. 실제 Kakao/모바일 테스트 상태는 별도 기록.

### I-08 통합 검증과 인계

- [ ] Backend 단위·통합·Repository·Controller 및 Frontend 단위·컴포넌트·E2E를 실행한다.
- [ ] 고정 fixture로 성능·금액·날짜·권한·실패 복구를 측정한다.
- [ ] README에 최초 실행·검증·안전한 종료·재시작과 포트 충돌 대응을 기록한다.
- [ ] test-report.md, bug-report.md와 추적표에 실제 명령·결과·미검증 사유를 연결한다.
- [ ] 운영 배포 시점에는 격리 환경의 ledger 복원·최신성 실패 차단 및 개인정보 로그 검토를 완료한다.

선행: I-01~07. 완료 조건: 모든 Must 검증 근거 확보, 승인된 MVP Should 기능 포함, 실패/Mock/미검증 공개. 외부 키 미준비는 실제 지도 검증만 제한하며 다른 완료 작업을 되돌리지 않는다. FR-027 설정 변경은 Could로 남기고 별도 범위 결정 없이 필수로 승격하지 않는다.

## 실행 전 인계 확인

사용자에게 원본 코드나 SQL 작성을 요구하지 않는다. 계정 로그인, 키 발급, 필요한 OS 권한 및 운영 호스트 선택만 필요한 시점에 요청한다. 환경 테스트를 이유로 Windows에서 임시 파일 검증·도구 실행을 하지 않는다. 맥북에서도 기존 파일 삭제·덮어쓰기·DB 초기화는 기본 실행 절차에 넣지 않는다.
