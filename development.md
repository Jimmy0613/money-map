# 세 기기 공통 개발 안내

기준일: 2026-10-20. 현재 PC, 집 PC, 맥북은 같은 저장소를 사용하되 실행 환경과 비밀값은 각각 준비합니다. 새 맥북에서 설치까지 재현한 결과는 아닙니다.

## 작업을 이어가는 순서

1. 시작 전 `git status`로 해당 기기의 미완료 변경을 확인합니다. 변경이 없을 때 `git pull --ff-only`로 최신 내용을 받습니다.
2. 다른 기기로 옮기기 전에 변경을 검토하고 커밋·푸시합니다. 미완료 작업도 별도 브랜치에 커밋하면 이어갈 수 있습니다. 동시에 수정했다면 먼저 변경을 보존하고 병합합니다.
3. 잠금 파일이 바뀌면 해당 디렉터리에서 `npm ci`를 실행합니다. 의존성·빌드 캐시를 기기 간 복사하지 않습니다.

## 도구와 최초 실행

Git, Node 22.22.0과 npm, Java 21, Python 3을 준비합니다. Java 경로는 각 기기의 `JAVA_HOME`으로 설정합니다. Gradle Wrapper는 8.14.3입니다. Docker와 Compose는 로컬 DB와 Testcontainers 검증에 필요합니다. 프런트엔드만 작업할 때는 필수가 아닙니다.

프로젝트 루트에서 실행합니다. Windows에서는 `python3` 대신 `python`을 사용할 수 있습니다.

```sh
npm --prefix frontend ci
python3 scripts/local-env.py
python3 scripts/run-local.py db
```

`local-env.py`는 `.env`가 없을 때 난수 비밀값을 생성하며 기존 파일은 보존합니다. Kakao 키 등 외부 설정은 `.env`에 각자 채웁니다. 브라우저 주소는 `APP_ORIGIN`과 일치시킵니다. `localhost`와 `127.0.0.1`은 서로 다른 Origin입니다.

별도 터미널에서 각각 실행합니다.

```sh
python3 scripts/run-local.py backend
python3 scripts/run-local.py frontend
```

DB 중지는 `python3 scripts/run-local.py stop`입니다. 이 안내는 DB 컨테이너와 호스트에서 실행하는 앱을 기준으로 합니다. 운영 프로파일을 로컬 프로파일로 바꾸어 검증을 우회하지 않습니다.

## 검증

```sh
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend test
npm --prefix frontend run build
```

백엔드는 `backend`에서 macOS는 `./gradlew test bootJar --no-daemon`을 실행합니다. 현재 Windows용 Wrapper는 없으므로 Gradle 8.14.3을 설치해 `gradle test bootJar --no-daemon`을 사용하거나 `run-local.py`가 찾을 수 있는 PATH에 추가합니다. 실제 DB·재시작 검사 조건은 CI 정의와 [검증 보고서](test-report.md)를 확인합니다.

Worker 수정 시 `infra/recovery-authority`에서 `npm ci` 후 해당 `package.json`의 검사 명령을 실행합니다. 루트 Neon 설정 도구가 필요하면 루트에서도 `npm ci`를 실행합니다. 서로 다른 실행 단위의 잠금 파일은 모두 유지합니다.

## 공유 범위

| Git으로 공유 | 별도 관리 |
|---|---|
| 코드, 테스트, Flyway 이력, 잠금 파일, 버전 고정 | `.env`, 운영 토큰, 개인키, 쿠키 |
| 공용 개발·배포·복원 문서 | `.tooling`의 개인 백업과 진단 원본 |
| 합성 데이터 포트폴리오 | 개인 거래 화면, 로컬 DB·캐시·IDE 사용자 설정 |

`.codex/config.toml`의 공통 Neon 대상은 유지하되 각 기기에서 따로 인증합니다. 필요한 비밀값은 비밀번호 관리자 등 비공개 경로로 전달합니다. 로컬 DB는 자동 동기화되지 않습니다. 운영 DB를 로컬 테스트에 직접 연결하지 않습니다.

운영 변경은 [배포 갱신 절차](recovery-deployment-upgrade.md)를 따릅니다. Render 배포 커밋과 독립 제어기 등록 revision이 어긋나면 서비스가 차단될 수 있습니다.

## 포트폴리오 생성

최신 생성기는 `portfolio/build_portfolio_v2.py`이며 ReportLab과 Pillow가 필요합니다. Windows에서는 맑은 고딕을 기본으로 사용합니다. macOS에서는 사용할 권한이 있는 한글 TTF를 준비하고 `MONEY_MAP_FONT_REGULAR`, `MONEY_MAP_FONT_BOLD` 환경변수에 일반·굵은 폰트의 절대 경로를 지정합니다. 의존성은 `python3 -m pip install -r portfolio/requirements.txt`로 설치하고 `python3 portfolio/build_portfolio_v2.py`로 생성합니다. 폰트 파일을 저장소에 복사하지 않습니다. 생성 후 글자와 페이지 배치를 확인합니다. 현재 PDF와 필요한 이미지 5개는 Git으로 공유합니다.
