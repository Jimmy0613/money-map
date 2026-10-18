# macOS 환경 점검

> 최신 종합 점검(2026-10-18): **개발·검증 진행 중, 배포 준비 완료 아님.** 복원 기본 차단·서명 응답 검증·독립 상태 제어 도구를 구현했습니다. 2026-09-25 최종 실행 근거는 Backend 단위·HTTP42/실제 PostgreSQL 통합24/제어 도구11PASS 및 bootJar 성공입니다. Frontend55/생산 Mock2는 이전 화면 보완 실행 근거입니다. 상위 추적표33PASS/38NOT_RUN 유지. [복원 실행 계약](recovery-authority-contract.md)에 구현과 남은 운영 검증기·배포 연결을 구분합니다. 아래 날짜별 기록은 당시 이력입니다.

초기 점검(2026-09-18) 이력. 판정: **CONDITIONAL PASS**. 프로젝트 파일 권한은 정상이며 Docker 부재로 PostgreSQL·Testcontainers·Compose 실행은 차단된다. DB 초기화, 이미지 다운로드, 컨테이너 실행은 환경 점검 단계에서 하지 않았다.

- 실제 루트와 cwd: `/Users/lmj/money-map`, 허용된 쓰기 범위 내부.
- macOS 26.2 arm64, zsh. 루트 내 심볼릭 링크 없음.
- 전달 파일: DOCX 6개 OOXML 열기/본문 추출 성공, Markdown 9개 UTF-8 읽기 성공. 한글 파일명은 macOS 분해형 유니코드이며 NFC 정규화 시 전달 목록과 일치한다. CHG-006 및 추가 작업 검토 기록 확인. 원본 해시는 `evidence/transfer-manifest.json`.
- Git 2.50.1. `git status --short --branch`, `git rev-parse --show-toplevel`: exit 128, `fatal: not a git repository (or any of the parent directories): .git`. 기존 Git 변경 비교 불가. 원본 파일 보존 후 신규 산출물 추가.
- 고유 `.sandbox-check-*` 디렉터리/파일 생성 → UTF-8 읽기 일치 → 수정/재읽기 일치 → 생성 파일 unlink → 빈 디렉터리 rmdir → 부재 확인: 모두 PASS. 기존 파일을 삭제 테스트에 사용하지 않았다.
- Java: Corretto OpenJDK 21.0.3, `JAVA_HOME` 미설정이지만 `java -version` 성공. javac 존재.
- Node v20.14.0, npm 10.7.0. Gradle/Maven/pnpm/yarn 명령 없음. Wrapper는 신규 프로젝트여서 미생성.
- `docker --version`, `docker compose version`, `docker info --format '{{.ServerVersion}}'`: `FileNotFoundError: [Errno 2] No such file or directory: 'docker'`. daemon 접근 및 Compose 검증 불가.
- 127.0.0.1 TCP 3000/8080/5432: 점검 시 리스너 없음. 이후 실행 시 재확인 필요.
- `df -h .`: 가용 131Gi.
- 시작 시 frontend/backend/package.json/build.gradle/Compose/.gitignore/.env.example/.env 없음. 이후 생성 파일과 구분한다.
- 환경변수 APP_ORIGIN, FRONTEND_PORT, BACKEND_PORT, DB_PORT, POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD, SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD, JWT_SECRET, CURSOR_SECRET, AUTH_COOKIE_SECURE, SPRING_PROFILES_ACTIVE, BACKEND_INTERNAL_URL, NEXT_PUBLIC_KAKAO_MAP_APP_KEY 모두 UNSET. 값은 출력하지 않았다.
- 네트워크: sandbox의 curl Maven Central은 `Could not resolve host`, npm은 `getaddrinfo ENOTFOUND registry.npmjs.org`. 승인된 sandbox 외 재시도에서 Maven POM HTTP 200 및 npm Next 버전 조회 성공. OS 전체 네트워크 장애로 단정하지 않는다.

진행 가능: 코드·Migration·설정 작성, 의존성 설치(네트워크 권한 필요), 컴파일·단위 테스트·Mock UI 검증. 차단: Docker DB 통합/성능/복원 검증. 실제 Kakao는 공개키/허용 도메인, 모바일 위치는 HTTPS/실기기 준비 시 검증한다. 운영 호스트 선택 및 백업 최신성 검증은 배포 전에 필요하다. 이 점검은 제품 테스트 PASS가 아니다.

## 재개 후 실제 확인 (2026-09-19)

개발 환경 판정: **PASS**. 외부 지도·실기기·운영 검증 조건은 아래와 같이 별도 남아 있다. 초기 06 점검 이후 사용자가 Docker 설치를 승인하여 구현 단계에서 설치했다.

- Docker Desktop 4.91.0(239619), 공식 Apple Silicon DMG와 Apple 코드 서명 검증. macOS 관리자 인증은 사용자 시스템 창에서 처리했다. Docker Engine/CLI 29.8.0, Compose 5.5.1 실행 확인. `desktop-linux` 컨텍스트 정상.
- Java 21, Gradle Wrapper 8.14.3, 프로젝트 내부 Node 22.22.0, npm lockfile로 빌드 성공. 시스템 Node를 덮어쓰지 않았다.
- `scripts/check-compose.py`: PASS (`evidence/compose-validation.log`), 치환된 Secret을 출력하지 않았다.
- Docker 설치 전 테스트는 Docker 부재로 실패했으며 `evidence/initial-docker-blocked/`에 보존했다. 설치 후 실제 PostgreSQL 17.6 Testcontainers 테스트 8개 PASS. currency JPA CHAR 매핑 결함 수정 후 재검증했다.
- 로컬 DB 시작 전 Docker 볼륨과 3000/8080/5432 리스너가 없음을 확인했다. 새 `.env`만 exclusive-create, mode 0600으로 생성했다. 실제 값은 출력하지 않았다. 개발 DB는 새 `money-map_pgdata`에 구성하고, Testcontainers는 별도 임시 DB를 사용했다. 초기화/volume 삭제를 실행하지 않았다.
- 로컬 Backend/Frontend 실행 및 실제 브라우저 가입·CRUD·로그아웃·합성 계정 탈퇴 확인. 재개 시 프로세스가 종료되어 재시작했으며 기존 V1~V5 Migration 검증과 `No migration necessary` 확인. `evidence/local-backend.log`, `readiness.json` 참조.
- `.env`로 로컬 실행에 필요한 APP_ORIGIN/포트/DB 접속/JWT_SECRET/CURSOR_SECRET/AUTH_COOKIE_SECURE/프로필/BACKEND_INTERNAL_URL을 주입한다. NEXT_PUBLIC_KAKAO_MAP_APP_KEY는 미설정이다. 실제 키·HTTPS 실기기·운영 호스트는 아직 준비되지 않았다.
- sandbox 내 DNS/소켓 제한이 있어 필요한 다운로드·서버·테스트 명령은 승인된 권한으로 실행했다. 자동 승인 검토 사용량 한도로 한 차례 재개 작업이 중단되었고, 사용자 재개 후 계속했다. 제한을 우회하지 않았다.

임시 권한 검증 파일은 초기 점검에서 제거 확인했고 사용자 원본 15개는 원본 해시/사본으로 보존한다. 환경 PASS는 전체 요구사항 또는 운영 배포 PASS가 아니다.

실제 부하 측정도 별도 격리 DB에서 완료했다. 결과는 test-report.md 및 evidence/performance-summary.json을 따른다.


## Kakao 설정 갱신 (2026-09-21)

NEXT_PUBLIC_KAKAO_MAP_APP_KEY: SET 여부만 확인, APP_ORIGIN localhost3000 일치. 기존 프런트엔드 프로세스만 정상 종료·재시작. 실제 SDK/검색/주소·지역코드/지도 Marker 상세 E2E1개 PASS. Secret 값이나 .env 내용을 출력하지 않았다. 남은 사용자 준비는 실기기·HTTPS와 운영 환경이다.


## 휴대폰 HTTPS 환경 (2026-09-21)

사용자 승인으로 공식 cloudflared2026.9.1 다운로드 및 release asset SHA-256 일치를 확인했다. 기존 localhost3000/8080/5432와 .env 유지. 별도 생산 빌드 프런트3001/백엔드8081/DB55432는 loopback 바인딩, PostgreSQL17.6 전용 볼륨 사용. 기존 DB 복사·초기화 없음. 별도 Secret은 ignored0600 파일에 저장하며 출력하지 않는다. APP_ORIGIN은 HTTPS origin, Secure 쿠키 설정. HTTPS E2E2개 PASS 및 미인증401/다른 Origin403 확인. 실제 휴대폰 및 새 Kakao 도메인 검증은 대기. mobile-preview.md 참조.


2026-09-21 HTTPS Kakao 도메인 등록 후 검증: 사용자 등록 완료 보고 뒤 실제 HTTPS Chromium에서 지도/장소 검색/주소·지역 코드/위치 저장/Marker 상세/합성 계정 탈퇴 E2E1개 PASS(11.7초). evidence/mobile-https-kakao-e2e.log. 실제 Galaxy S25의 GPS·위치 거부·터치 검증은 아직 대기이며 상위 추적표 상태는 유지한다.


2026-09-23 Galaxy S25 / Android16 Chrome 사용자 확인: 위치 권한 차단 상태에서 위치 없이 계속하기 후2,000원 거래 저장, 상세의2,000원·위치 없음 표시 확인. Android 실제 기기에서 위치 허용→주소→저장→Marker 상세와 거부→위치 없는 저장을 수동 확인했다. Chrome 정확한 버전/최신2버전 범위, iOS Safari,10초 timeout·입력 보존 전수 등은 미확인. 상위 복합 기준을 임의 PASS로 변경하지 않는다.


2026-09-23 테스트 기기 버전 확정(사용자 보고): Galaxy S25 / Android16 / Chrome153.0.8010.52. 위치 허용·주소 포함1,000원 저장·Marker 상세 및 권한 거부 후2,000원 위치 없는 저장 수동 확인. Chrome 최신2개 주요 버전 모두의 검증을 의미하지 않으며 iOS 기기 없음으로 Safari 미실행.


2026-09-23 운영 준비 제약(사용자 확인): 보유 서버/호스팅 계정·도메인 없음, 비용은 무료만 허용. 유료 전환·리소스 결제·도메인 구매는 승인되지 않음. 무료 호스트 선정 검토 중이며 운영 배포 및 백업/최신 ledger 복원 검증은 미완료.

## 보완 실행 환경 (2026-09-24)

기존 macOS/Node22/Java21/Gradle8.14.3 환경에서 단위·실제 Testcontainers PostgreSQL17.6·생산 빌드/브라우저 검증을 수행했다. Docker 및 로컬 테스트 소켓은 승인된 샌드박스 확장으로 실행했다. 실패한 합성 복원에서 발견한 CSV 줄바꿈/Python ISO 소수 초 호환성을 보완하고 재실행 PASS. 새 복원 테스트 컨테이너만 정리했다.

기존 개발/휴대폰 DB와 실행 서버, .env는 보존했다. Frontend 생산 검증은 .tooling의 새 복사본과3002 포트에서 실행·종료했고 합성 키만 사용했다. 실제 Kakao/기기의 수정본 재검증 및 운영 저장소/백업/WAL/30일 보존 환경은 아직 확보·검증되지 않았다. 이 실행은 배포 환경 적합성 전체 PASS가 아니다.


### 추가 자율 검증 (2026-09-24~25)

지도 조회 실패 시 이전 결과 소실을 보완하고 Frontend43/Backend38·격리 DB13, 변경 API 부하1, 타입/lint/생산 빌드 및 Mock 브라우저2PASS를 확인했다. 변경4경로 p95는7~10ms, 상세 조회3ms, HTTP 오류0이다. 실제 기대값을 추가 확인한7사례만 갱신하여 추적표33PASS/38NOT_RUN이다. 기존 DB·Secret·휴대폰 실행본은 보존했다. 상세 근거와 남은 범위는 test-report.md/self-review.md의 최신 절을 따른다.


## 복원 제어 구현 갱신 (2026-10-18)

REQ-R01의 설정 누락 시 개방 문제를 보완했다. Backend는 외부 권위의 새 nonce/서명/현재 DB·환경·배포 결합이 없으면 기본 차단한다. 독립 recovery-authority.py에는 pin된 검증기, 상태 순서, 세대 CAS, 작업자 lease, 감사 이력 및 최대60초 승인 만료를 구현했다. 합성 증거로 실제 최신 WAL을 입증했다고 판정하지 않는다. 실제 WAL/fence/ingress 검증기, 신규 bootstrap·갱신 실행기와 운영 배포는 남아 있다. [실행 계약](recovery-authority-contract.md) 참조.

중단 전 최종 실행 결과 Backend 단위·HTTP42/실제 PostgreSQL 통합24/제어 도구11PASS 및 bootJar 성공을 재확인했다. 기존 비즈니스 테스트는 권위 서버를 mock하고 실제 gate는 별도 DB·서명 HTTP 통합11개로 검증한다. 이번 재개에서는 같은 테스트를 재실행했다고 기록하지 않는다. 원본 추적표33PASS/38NOT_RUN, 기존 DB/.env 및 휴대폰 실행본 보존.
