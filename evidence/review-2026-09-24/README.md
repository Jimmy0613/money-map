# Money Map

> 최신 종합 점검(2026-09-23): **개발·검증 진행 중, 배포 준비 완료 아님.** 추적표71사례26PASS/45NOT_RUN은 기능 완료율이 아닙니다. Android 실기기 위치 허용·거부·저장·Marker 확인은 완료(사용자 보고), iOS/운영 복원은 미검증입니다. 신규 코드 점검 사항과 다음 순서는 [self-review.md](self-review.md)에 정리했습니다. 아래 날짜별 실행 결과는 당시 이력입니다.

선택적 위치를 함께 기록하는 KRW 수입·지출 가계부입니다. Java 21 / Spring Boot 3.5.16, PostgreSQL 17, Next.js 16.3.5 / React 19 / TypeScript로 구성합니다. 원본 요구사항과 CHG-001~006 승인을 기준으로 구현했습니다.

현재 **로컬 실행과 핵심 흐름 검증 완료, 전체 요구사항 검증 진행 중**입니다. Docker 설치 후 실제 PostgreSQL 통합11개와 실제 브라우저 정상/응답 유실 흐름2개가 통과했고,10명×10000건의6분 부하 측정도 기준을 충족했습니다. 상세 결과는 test-report.md를 보세요. 이 상태는 배포 완료 또는 전체 Must 충족을 의미하지 않습니다. FR-027 설정 변경은 선택 범위로 구현하지 않았습니다.

## 구성

- `backend/`: REST API, 인증/CSRF, 거래·카테고리, 지도·통계, Flyway V1~V5
- `frontend/`: 모바일 웹, React Query, React Hook Form, Zod, Kakao adapter
- `scripts/`: Secret 비출력 로컬 설정 및 안전한 실행
- `evidence/`: 실제 검증 로그, 합성 데이터 화면, 전달 원본 해시와 원본 Markdown 사본
- `region-data.md`: 공식 지역 자료, 수집일과 과거 별칭 한계

원본 DOCX 6개는 수정하지 않았습니다. Git 저장소가 없는 상태로 전달받았으며 자동으로 git init/commit 하지 않았습니다.

## 도구

- Java 21. 실제 확인: Corretto 21.0.3.
- Gradle Wrapper 8.14.3과 배포 SHA-256 고정. Wrapper가 의존성을 내려받습니다.
- Node 22.22.0 / npm. `.nvmrc`, `.node-version`에 고정. 기존 시스템 Node 20.14.0은 일부 lint 의존성의 최소 요구보다 낮았습니다.
- Docker Desktop 4.91.0 / Docker Compose 5.5.1. 실제 테스트 DB는 Testcontainers가 별도로 만듭니다. 개발 DB를 테스트로 재사용하지 않습니다.

이 작업에서 Node는 프로젝트 `.tooling/node/node_modules/node/bin`에만 설치했습니다. 시스템 Node를 덮어쓰지 않았습니다. 다른 머신에서는 Node 22.22.0을 준비하거나 nvm 등 기존 관리자를 사용합니다.

## 최초 로컬 실행

프로젝트 루트에서 아래 순서로 실행합니다. 도구 설치 및 의존성 다운로드에는 네트워크 권한이 필요합니다.

```sh
python3 scripts/local-env.py
python3 scripts/run-local.py db
```

`local-env.py`는 `.env`가 없을 때만 0600 권한으로 새 난수 Secret을 생성합니다. 이미 있으면 보존하고 종료합니다. 실제 값은 출력하지 않습니다. `.env.example`은 설명용 placeholder이고 실제 실행용 비밀번호가 아닙니다.

별도 터미널에서 각각 실행합니다.

```sh
python3 scripts/run-local.py backend
python3 scripts/run-local.py frontend
```

처음 프런트엔드를 실행하기 전 `frontend`에서 `npm ci`를 수행합니다. 기본 브라우저 주소는 `http://localhost:3000`입니다. Origin과 쿠키 정책상 localhost와 127.0.0.1을 섞어서 사용하지 마세요. Backend 8080, PostgreSQL host 5432입니다. `scripts/run-local.py`는 `.env`를 shell로 실행하지 않고 값만 읽어 프로세스에 전달합니다.

전체 컨테이너 경로:

```sh
python3 scripts/check-compose.py
docker compose --profile app up --build -d
```

Compose config 출력에는 Secret이 포함될 수 있으므로 일반 `docker compose config` 출력은 사용하지 않습니다. 제공 스크립트는 `--quiet`로 검증합니다. DB는 127.0.0.1에만 바인딩하고 `pgdata` 볼륨을 사용합니다. backend는 Flyway 성공 및 DB readiness 이후 정상 상태가 되며 frontend는 backend healthy 이후 시작합니다.

실행 확인:

```sh
curl --fail http://localhost:8080/actuator/health/liveness
curl --fail http://localhost:8080/actuator/health/readiness
```

이 명령들은 사용자 데이터나 Secret을 반환하지 않습니다. DB 연결 실패는 readiness 실패이며 빈 데이터 성공으로 바꾸지 않습니다.

## 종료·재시작

호스트에서 실행한 Backend/Frontend는 해당 터미널에서 Ctrl+C로 종료합니다. DB/Compose 서비스는 다음과 같이 중지·재시작합니다.

```sh
python3 scripts/run-local.py stop
python3 scripts/run-local.py db
```

DB 초기화·볼륨 삭제 명령은 제공하지 않습니다. Flyway는 적용된 Migration을 변경하지 않고 새 버전을 추가합니다. `ddl-auto=validate`만 사용합니다. 운영 DB/사용자 데이터로 테스트하지 않습니다.

## 테스트

프로젝트 내부 런타임을 사용할 때:

```sh
export PATH="$PWD/.tooling/node/node_modules/node/bin:$PATH"
export GRADLE_USER_HOME="$PWD/.tooling/gradle"
export PLAYWRIGHT_BROWSERS_PATH="$PWD/.tooling/playwright"
backend/gradlew -p backend test bootJar --no-daemon
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix frontend run e2e
```

`test`는 Docker가 필요 없는 테스트, `integrationTest`는 실제 PostgreSQL Testcontainers 테스트입니다. 별도 명령 분리는 테스트 실패를 숨기기 위한 skip이 아닙니다. 통합 검증은 반드시 아래 명령으로 따로 실행해야 합니다.

```sh
backend/gradlew -p backend integrationTest --no-daemon
```

브라우저가 아직 없으면 `frontend`에서 `npx playwright install chromium`을 실행합니다. E2E는 Playwright가 localhost:3000 서버를 시작하며 API는 명시적으로 Mock 처리합니다. 실제 DB E2E·실기기 위치 검증을 대신하지 않습니다. 포트가 사용 중이면 기존 프로세스를 종료하지 말고 설정과 테스트 baseURL을 함께 바꾸세요.

## 환경변수와 지도

필수 비공개 설정은 POSTGRES_PASSWORD, SPRING_DATASOURCE_PASSWORD, JWT_SECRET, CURSOR_SECRET입니다. JWT/CURSOR 키는 서로 다른 32바이트 이상 난수의 Base64여야 합니다. 비밀값이 없거나 형식이 잘못되면 정상 시작으로 취급하지 않습니다.

Kakao 공개키는 `NEXT_PUBLIC_KAKAO_MAP_APP_KEY`입니다. [Kakao Developers](https://developers.kakao.com/)에서 앱을 생성하고 JavaScript 키 및 웹 허용 도메인을 설정합니다. 서버용 Kakao Secret은 사용하지 않습니다. 키는 소스나 보고서에 붙이지 말고 로컬 `.env`에 설정합니다. 공개키 변경 시 Next.js 재빌드가 필요합니다. host 실행은 `run-local.py`가 주입하고 Compose는 build argument로 전달합니다.

키가 없어도 거래 CRUD와 위치 없는 저장은 구현되어 있습니다. 지도/검색 UI는 설정 누락을 표시합니다. 현재 위치는 사용자가 버튼을 누를 때만 요청하며 1,000m 초과 오차는 확인을 받아야 합니다. 실제 모바일 LAN 위치 입력은 HTTPS가 필요합니다.

포트 변경 시 APP_ORIGIN, FRONTEND_PORT, BACKEND_PORT, DB_PORT, SPRING_DATASOURCE_URL, BACKEND_INTERNAL_URL 및 Kakao 허용 도메인을 함께 맞춥니다. Next의 rewrite 대상이 바뀌면 재빌드합니다. 기본 Compose는 외부 공개 배포용 TLS proxy를 포함하지 않습니다.

## 보안·운영 제한

로그아웃 실패 시 `mm.logoutPending` boolean만 localStorage에 남기며 개인 Query cache, 화면과 자동 갱신을 차단합니다. 좌표·토큰은 localStorage에 저장하지 않습니다. 성공한 204를 받은 뒤에만 차단을 해제합니다. 브라우저 저장소가 금지되면 새로고침 후 지속성을 보장할 수 없다는 안내를 표시합니다.

탈퇴는 `privacy.deletion_ledger` 삽입과 사용자 cascade 삭제를 같은 DB Transaction에서 수행합니다. 실제 DB 원자성 및 ledger 실패 rollback은 통합 테스트로 확인했습니다. 운영 복원 훈련은 미실행입니다. 운영 백업은 암호화, 최대 30일 보존, 최신 ledger 별도 보전/WAL 복구가 필요합니다. public 복원 후 ledger 사용자 재삭제·전체 세션 폐기 및 최신성 확인 전 외부 접근을 열지 않아야 합니다. 이 저장소는 운영 백업 시스템이나 검증된 복원 실행기를 제공한 상태가 아닙니다.

로그인 제한은 단일 프로세스의 최대 10,000개 메모리 버킷으로 동작하고 재시작 시 초기화됩니다. 역방향 프록시 환경의 신뢰 IP 설정과 부하 측정은 운영 전 검증 대상입니다. 약관 v1 UI는 개발용 안내이며 운영 약관 확정은 배포 전에 필요합니다. 실제 Kakao SDK 출처에 맞춘 CSP, HTTPS·브라우저 호환성, 운영 Secret/로그 검사는 아직 출시 검증이 남아 있습니다.

## 기술 근거

Java/Gradle 호환성은 [Spring Boot 3.5 시스템 요구사항](https://docs.spring.io/spring-boot/3.5/system-requirements.html), Next 사용법은 설치된 `next/dist/docs`와 [공식 문서](https://nextjs.org/docs)를 확인했습니다. 정확한 npm 버전은 lockfile, Java 의존성은 build.gradle에 기록합니다. 공식 지역 자료의 출처 및 버전은 region-data.md를 따릅니다.


## 실제 통합·성능 검증

로컬 db/backend/frontend를 실행한 상태에서 실제 API 브라우저 검증:

```sh
cd frontend
npx playwright test --config playwright.real.config.ts
```

매번 새 합성 계정을 만들어 정상 흐름과 서버 commit 후 응답 유실 복구를 확인합니다. 성공 시 테스트 계정을 탈퇴합니다. 실패 시 합성 계정이 남을 수 있으나 DB 전체를 초기화하지 않습니다. trace/video는 인증값을 남기지 않도록 끕니다.

프로젝트 루트에서 별도 격리 DB 부하 측정:

```sh
backend/gradlew -p backend performanceTest --no-daemon
```

사용자10명·각10000건,1분 워밍업·5분 측정으로 약6분 이상 걸립니다. 일반 test와 분리되어 있으므로 실행하지 않고 성능 PASS를 주장하지 않습니다. Secret을 출력하지 않는 로컬 로그 검사는 `python3 scripts/audit-local-evidence.py`입니다.


2026-09-19 추가 회귀는73개 PASS였으며 서버 필드 오류·입력 접근성·위치 지연 응답 보완이 반영되어 있습니다. 이전 성능 검증1개는 별도 이력입니다. 현재 요구사항 추적표는26개 상위 사례 PASS,45개 미실행 또는 부분 확인 상태로 전체 완료를 뜻하지 않습니다.


2026-09-21: 사용자 Kakao 설정 반영 후 실제 SDK/검색/주소 변환/위치 저장/Marker 상세 E2E1개 PASS. 재현은 로컬 db/backend/frontend 실행 상태에서 `frontend/node_modules/.bin/playwright test --config frontend/playwright.kakao.config.ts`. 실제 Kakao 호출과 새 합성 계정 생성이 포함된다. 실기기·HTTPS 검증은 별도다.
