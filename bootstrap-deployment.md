# 신규 Neon DB 운영 등록

2026-10-18. 정상 신규 빈 DB의 최초 운영 등록 경로다. 과거 데이터의 복원 승인이나 WAL 최신성 검증을 대신하지 않는다. 복원·reset·다른 계보·스키마를 이 경로로 다시 개방할 수 없다.

## 구성과 경계

- 사용자 DB: Neon production. 최초 집계에서 users/deletion ledger/sessions/transactions가 모두 0건임을 확인했다. 기존 V1~V5와 데이터를 보존하고 정상 Flyway V6만 추가했다.
- 독립 권위 저장소: Cloudflare Worker `money-map-recovery-authority`의 SQLite Durable Object. 사용자 DB 백업과 함께 복원하거나 삭제하지 않는다. 단일 운영 환경의 현재 등록 상태/세대와 상태 변경 감사를 트랜잭션으로 보존한다.
- 검증 전용 DB 로그인: 테이블 SELECT/DML 권한 없이 `public.recovery_observation(boolean)`만 실행한다. SECURITY DEFINER 함수의 search_path는 pg_catalog로 고정하고 PUBLIC 실행 권한은 회수했다. 반환값에는 이메일/비밀번호/위치/거래 내용이 없다.
- Worker에 보관하는 Neon API 키는 프로젝트 하나에 한정된다. 제공되는 키 범위가 읽기 전용은 아니므로 애플리케이션에는 전달하지 않고 제어 서버 Secret으로만 보관한다. 코드에서는 브랜치/endpoint 조회만 사용한다.
- 운영/테스트의 DB 로그인·관리 token·판단 token·Ed25519 서명키는 분리한다. 인증 값은 소스/문서/명령 인자에 넣지 않는다. 공개키만 Backend에 전달한다.

## 최초 등록 및 일반 요청

운영 API를 강제 차단한 상태에서 관리 token으로 `/register`를 한 번만 호출한다. Worker는 등록 대상의 프로젝트/브랜치/endpoint 결합과 생성 시각, 부모/복원/reset/import 부재를 Neon control plane으로 읽고, 별도 SQL 연결로 빈 DB·성공한 migration·논리 계보와 현재 물리 지문을 확인한다. SQL 관찰 전후 브랜치 메타데이터를 대조한다. 이후 원자적으로 최초 세대와 결합값을 외부 저장소에 기록한다. 동시 등록은 하나만 성공하며, 기존 등록 또는 BLOCKED 기록은 덮어쓰지 않는다.

각 API 요청에서 Backend는 기존 물리 지문과 새 UUID nonce를 보낸다. Worker는 매번 실제 Neon 메타데이터와 SQL을 다시 읽고, 등록된 tenant/timeline/DB OID/migration 및 provider 결합이 같은지 검사한다. 성공한 경우에만 동일 요청 nonce/대상/환경/배포와 최대 5초 유효기간을 Ed25519로 서명한다. 허용 캐시는 없다. 컴퓨트 sleep/wake의 새 PostgreSQL 시작 시각은 독립 검증기가 현재 DB를 재관찰한 뒤에만 인정한다. 다른 지문을 자동으로 신뢰하는 기능은 없다.

복원/reset/논리 계보·스키마·endpoint 변경은 외부 세대를 증가시키고 BLOCKED로 보존한다. 일시 네트워크 장애/compute 기동 중 상태는 해당 요청만 차단하고 증거를 발급하지 않는다. `/block`은 관리 token으로만 호출하며 등록을 삭제하거나 자동 해제하는 API는 없다. 서명 중 상태가 바뀌면 반환 직전 세대를 다시 검사해 오래된 결과를 거부한다.

Backend는 Render의 실제 `RENDER_GIT_COMMIT`과 등록된 `RECOVERY_DEPLOYMENT` 일치도 검사한다. 기존 권위 서버 경로의 기본 타임아웃 750ms/응답 8KiB/nonce·서명·환경·대상 검사는 유지한다. Neon 실관찰을 사용하는 배포만 명시적으로 최대 5000ms를 설정한다. 새 코드 배포/스키마 변경 뒤 등록 정보가 맞지 않으면 자동 개방되지 않는다. 이후 업그레이드 등록 갱신과 실제 복원 승인 경로는 별도 구현·검증 대상이다.

## 접근 범위와 계정 등록

공개 서버는 `APP_ACCESS_RESTRICTED=true`, 사용자 지정 이메일 한 개의 `APP_ALLOWED_EMAILS`, `APP_SIGNUP_ENABLED=false`로 실행한다. 개인 이메일을 Git에 넣지 않는다. 로그인/refresh/기존 access 인증도 허용 목록을 검사한다. 빈 허용 목록이면 전부 거부한다.

본인 비밀번호 입력·이용 안내 동의는 사용자만 수행한다. `scripts/personal-registration.py --build-only`로 검증한 별도 프런트 복사본을 준비했다. 서비스 연결 후 이 도구를 실행하면 기존 local 정책에 맞는 loopback 전용 임시 등록 화면을 연다. 데이터 대상은 명시적으로 고정한 production endpoint이고, 공개 서버의 회원가입은 계속 닫아 둔다. 이 임시 서버에 터널/외부 프록시를 붙이지 않는다. 등록 후 임시 서버를 종료하고 배포 HTTPS 주소에서 다시 로그인한다. 등록 화면용 JWT/cursor 키는 운영 세션 키와 다르다.

## 검증 증거

- Backend 단위/HTTP 및 실제 PostgreSQL 회귀: `evidence/bootstrap-backend-checks.log`와 JUnit XML 결과.
- 메타데이터 역할이 사용자 행 SELECT/ledger DML을 실행하지 못하고 지문이 Backend와 같은지 실제 PostgreSQL 및 실제 Neon에서 확인했다.
- 순수 서명/결합/복원·reset 거부 정책: `evidence/bootstrap-policy-tests.log`.
- 공식 Cloudflare Vitest 런타임에서 인증 경계, SQLite 상태/감사, 객체 eviction 이후 차단 보존, 장애 시 미등록 유지 검사: `evidence/bootstrap-worker-runtime-tests.log`.
- 실제 Cloudflare 테스트 Worker+Neon 격리 DB의 일회 등록·nonce 서명·잘못된 대상 거부: `evidence/bootstrap-neon-authority-e2e.log`.
- `local` 예외가 없는 `prod,neon` Java 서버의 실제 Cloudflare 서명 수락·미허용 가입 거부·허용된 합성 가입/로그인/refresh·탈퇴/과거 세션 거부: `evidence/bootstrap-production-profile-e2e.log`.
- 별도 프런트 복사본의 실제 `npm run build`: `evidence/bootstrap-frontend-build.log` 및 `evidence/bootstrap-frontend-build-result.log`.

격리 Neon 브랜치 `money-map-bootstrap-20261018`에는 기존 스키마를 지우지 않고 새 `money_map_bootstrap_test` DB를 만들어 검증했다. 브랜치는 만료 예정이며 운영 데이터는 복제하지 않았다. 스키마만 복사한 DB의 Flyway 이력이 비어 V1과 충돌했던 최초 시도 및 Worker redirect 호환성/테스트 응답 스트림 정리 실패는 해결 후 재검증했다. PG18.6은 사용 중인 Flyway의 검증 지원 범위 경고가 있으므로 완전한 호환 인증을 주장하지 않는다.

전체 복원/WAL/백업 암호화·보존 및 운영 부하 검증은 완료로 바꾸지 않는다. 상위 요구사항 매트릭스의 기존 판정을 이 하위 검증으로 일괄 승격하지 않는다. 운영 Render 환경 연결 후의 HTTP 결과는 배포 기록에 별도로 남긴다.
