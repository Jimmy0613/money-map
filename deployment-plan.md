# 개인 실사용·포트폴리오 배포 계획

2026-10-18. 사용자 요청에 따라 기능 확장보다 첫 실사용 배포를 우선한다. 확인된 조건은 무료만 사용, Cloudflare 계정 보유, 우선 본인 실사용 및 별도 시연 계정이다. 불특정 다수 회원가입 공개는 첫 배포 범위에 포함하지 않는다.

## 현재 결정과 다음 단계

- 기존 Spring Boot/Next.js/PostgreSQL 구조를 유지한다.
- 실사용 계정과 합성 데이터 시연 계정을 분리한다. 시연 데이터에 사용자의 실제 거래/주소를 복사하지 않는다.
- 기존 로컬 DB를 초기화하거나 자동 이전하지 않는다. 최초 운영 DB 및 이전 여부는 별도 확정한다.
- 서버 후보는 Oracle Cloud Always Free VM이다. 아직 사용자 선택·가입·자원 확보가 되지 않았으므로 확정 배포 대상으로 간주하지 않는다.
- Cloudflare Containers는 Workers Paid 요금제가 필요하여 현재 무료 조건의 후보에서 제외한다. Cloudflare Tunnel은 서버 자체를 제공하지 않는다. 고정 공개 호스트를 연결하려면 Cloudflare 도메인이 필요하며 현재 도메인 보유 여부는 재확인되지 않았다.
- 운영 URL, 사용자 계정 접근 제한, 운영 Secret, DB 영속 저장, HTTPS/Kakao 등록, 재시작 검사, 개인/시연 데이터 격리를 배포 대상으로 검증한다.

## 실제 배포를 막는 코드·운영 연결

현재 RecoveryGate는 운영에서 서명된 외부 응답을 요구하며 bootstrap/갱신 실행기는 아직 없다. 호스팅에 이미지를 올리는 것만으로 로그인 가능한 서비스가 되지 않는다. 개인용으로 범위를 줄였다는 이유만으로 이 검사를 끄거나 임의 성공 receipt를 생성하지 않는다.

서버 선정 후 신규 빈 DB의 정당한 최초 등록/재시작과 지속 운영 경로를 구현해야 한다. 실제 백업 복원의 최신 ledger 검증은 별도 절차로 유지하며, 검증되지 않은 백업을 자동 복원·공개하지 않는다. 기존 요구사항을 수정해야 하는 선택은 명시적으로 사용자에게 설명한다.

## 무료 후보 확인 근거

2026-10-18 공식 문서 확인:

- Cloudflare Containers: Workers Paid에 포함되는 유료 기능. https://developers.cloudflare.com/containers/platform/pricing/
- Cloudflare Tunnel: 공개 애플리케이션에는 Cloudflare 도메인 필요. https://developers.cloudflare.com/tunnel/get-started/
- Oracle Always Free: A1 월1500 OCPU시간/9000 GB시간(2 OCPU/12GB 상당), 자원 부족 가능 및 유휴 VM 회수 조건 존재. https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm
- Oracle 가입: 본인 확인용 카드 및 일시 승인 가능. 무료 계정 유지와 Always Free 표시 자원만 사용하며 유료 업그레이드는 하지 않는다. https://www.oracle.com/cloud/free/faq/

무료 후보 검토는 가용 서버 확보나 무중단 보장이 아니다. 계정/호스트가 확보되기 전에는 실제 배포 완료로 기록하지 않는다.


## Oracle 가입 오류 이후 대안 검토 (2026-10-18)

Oracle 가입은 최종 계정 생성 오류로 고객지원 접수 후 대기 중이다. 재가입을 반복하지 않고 별도 무료 후보를 검토한다. 이번 대화의 권고 후보는 **Neon Free(PostgreSQL) + Render Free(Spring Boot Docker) + Vercel Hobby(Next.js)**다. 아직 가입/프로젝트 생성/호환성 실측/공개 배포를 완료한 상태가 아니다.

| 후보 | 무료·가입 조건 | 실사용에 영향을 주는 조건 | 판단 |
|---|---|---|---|
| Neon Free | 공식 가격표상 카드 불필요, 기간 제한 없음, 프로젝트당1GB/월100CU-hours | 한도 초과 시 compute 중단/쓰기 제한; 무료 복원 창 최대6시간 또는 변경1GB | DB 우선 후보 |
| Render Free Web Service | 결제 수단 미등록 상태 지원; 초과 시 서비스/빌드 제한 | Java는Docker. 512MB, 15분 미사용 시 sleep, 재시작 약1분, 임시 파일시스템 | 개인/포트폴리오용 서버 후보; 무중단 운영 보장 아님 |
| Render Free Postgres | 무료 | 생성30일 후 만료 | 실사용 DB 후보에서 제외 |
| Vercel Hobby | 무료, 개인 비상업 용도 제한 | 한도/공정 사용 정책 적용 | Next 화면 후보; 유료플랜 선택 없이 가입 화면 재확인 |
| Koyeb | 카드 확인 필요, 가입 선택 플랜의 비례 요금 청구 가능 | 공식 FAQ상 카드29달러 임시 승인과 선택 플랜 요금 | 무료만 허용하는 현재 경로에서 제외 |

검토 근거:

- https://neon.com/pricing
- https://neon.com/docs/introduction/plans
- https://render.com/docs/free
- https://render.com/docs/faq
- https://render.com/docs/docker
- https://render.com/docs/compute-plans
- https://vercel.com/docs/plans/hobby
- https://www.koyeb.com/docs/faqs/pricing

### 코드 호환성 확인 후 배포

현재 RecoveryGate는 물리 PostgreSQL 식별/재시작 시각과 독립 SQLite 권위 서버를 전제로 한다. Neon은 관리형DB이며 Render 무료 서버의 로컬 SQLite는 영속 권위 저장소로 사용할 수 없다. 이 조합을 새로 추천했다는 이유로 검사 flag를 끄거나 같은 DB 안으로 권위 저장소를 이동하지 않는다. 관리형 DB 식별·권한·sleep/wake, 외부 영속 권위 저장소 및 신규 DB bootstrap/갱신 경로를 구현·검증해야 실제 로그인 가능한 배포가 된다.

그 외 실제 배포 전 확인할 연결은 Render512MB 내 Spring/Argon2 메모리, 플랫폼 PORT, Vercel API rewrite의 HTTPS/Origin·Secure 쿠키, DB TLS/커넥션 풀과 Flyway 직접 연결, API 호출 취소/콜드스타트 안내, 개인 계정·시연 계정 접근 제한이다. 아직 이 제공자 조합으로 실행 테스트하지 않았다.

사용자에게는 계정 준비를 한 번에 요구하지 않고 Neon Free 가입부터 안내한다. DB 연결 문자열/비밀번호/API 키는 채팅에 요청하지 않는다. 가입만으로 앱 배포 완료라고 보고하지 않는다.


## Neon 준비 완료 및 다음 단계 (2026-10-18)

Neon CLI/스킬/OAuth MCP 설정, 지정 프로젝트 연결과 실제 브랜치 E2E가 완료됐다. 상세는 neon-setup.md를 따른다. 다음 사용자 준비는 Render 계정 가입이며 Free 서비스 생성 전 서버 메모리/환경 변수/운영 gate 연결을 구체화한다. 아직 Render/Vercel에 서비스를 생성하거나 배포하지 않았다.


## 프런트 주소 확보와 Render 서버 준비 (2026-10-18)

Vercel https://money-map-frontend-murex.vercel.app/login 의 로그인 화면을 확인했다. 아직 BACKEND_INTERNAL_URL은 임시 목적지다. Render 허용 APP_ORIGIN을 실제 Vercel 주소로 맞췄으며, Free/Docker/Singapore/backend 설정을 확인했다. 로그인 차단 상태로 서버 실행부터 확인할 계획과 liveness health check를 render-deployment.md에 기록했다. 사용자 데이터/기존 실행본은 유지한다.


## Render 실행과 Vercel 연결 점검 (2026-10-18)

Render 주소 https://money-map-r459.onrender.com 에서 liveness200/UP 및 readiness·csrf503/RECOVERY_REQUIRED를 확인했다(evidence/render-first-health.json). Vercel의 BACKEND_INTERNAL_URL 변경 후 재배포를 진행했다. 첫 외부 재확인은 양쪽 HTTP 시간 초과였으며, 브라우저에서 Vercel /api/v1/auth/csrf를 열자 Render의 SERVICE WAKING UP 페이지가 전달됐다. 따라서 실제 서버로의 프록시 전달은 관찰했지만, 이번 재배포 뒤 정상 JSON 응답까지 확인한 상태와 구분한다. 최신 HTTP 결과는 evidence/vercel-render-link.json을 따른다.


재확인: evidence/vercel-render-link-retry.json에서 Vercel /api/v1/auth/csrf가503 RECOVERY_REQUIRED, Render liveness가200 UP을 반환했다. Vercel→Render 요청 전달을 실제 응답으로 확인했다. 현재 서비스는 로그인 차단 상태이며 정상 신규 DB의 운영 등록 절차와 개인/시연 접근 범위를 구현·검증해야 한다.


## 개인 계정 접근 제한 구현 (2026-10-18)

사용자가 지정한 이메일은 Git 소스/증거 문서에 넣지 않고 로컬 비공개 Render 환경 파일에만 보관했다. `APP_ACCESS_RESTRICTED=true`와 `APP_ALLOWED_EMAILS`로 가입·로그인·refresh·access token 인증을 제한한다. 제한 활성화 상태에서 목록이 비어 있으면 전부 거부한다. 일반 개발 환경의 기존 동작은 기본 설정에서 유지한다. 허용 목록에서 제거된 사용자의 기존 세션도 다음 인증/갱신 시 거부한다. 이메일 제한은 메일 소유권 인증을 대신하지 않으므로 최초 계정 등록은 운영 차단 상태/통제된 개방 과정에서 진행해야 한다.

단위 테스트 51개 성공 및 bootJar 빌드 성공(evidence/personal-access-checks.log). 변경 코드는 아직 운영에 배포하지 않았다. 신규 DB bootstrap은 미구현이다. 기존 recovery 계약은 매 요청 독립 서명 증거를 요구하며 단순 env 등록이나 Neon 브랜치 식별만으로 이를 충족한다고 판정하지 않는다. 강제 차단을 유지한다.

추가 회귀: Docker Desktop 실행/소켓 지정 후 실제 PostgreSQL 통합24개 성공. 개인정보를 포함한 배포 설정은 비공개 파일에만 준비했고 아직 Render에 적용하지 않았다.

## 신규 운영 DB 등록 완료 / Render 연결 대기 (2026-10-18)

검증된 소스 커밋 `9f7cb262df62856152c46df894607e9c89519265`를 main에 push했다. 운영 Neon의 사용자/삭제/세션/거래 0을 확인하고 V6와 메타데이터 전용 역할을 추가했다. 독립 Cloudflare Worker https://money-map-recovery-authority.mjlee95613.workers.dev 에서 실제 초기 관찰/일회 등록/운영 전용 Ed25519 서명 검증이 성공했다(`evidence/bootstrap-production-registration.log`). 등록 직전 실제 Render API503 RECOVERY_REQUIRED를 확인했다.

Render에 적용할 비공개 차이 파일은 `.tooling/render-setup/backend-bootstrap.env`(0600, 10개 변수)이다. 기존 DB/JWT/cursor 환경 값은 유지하며 공개 회원가입은 false다. 현재 Render 환경에는 이 파일을 아직 적용하지 않았다. 브라우저 도구의 새 인증 정보 입력 handoff 규칙에 따라 사용자 직접 가져오기/저장이 필요하다. 현재 API 로그인 가능 상태로 보고하지 않는다. 적용 뒤 실제 Render/Vercel200, 미허용 가입 거부, 본인 로컬 등록과 HTTPS 로그인 검증이 남아 있다.

실제 Render commit을 등록한 배포에 결합했으므로, 후속 증거 문서만의 commit도 main에 push하면 새 자동 배포가 차단될 수 있다. 현재 증거 갱신은 로컬에 보존하고 배포 결합을 갱신하지 않은 임의 추가 push를 하지 않는다.

## Render 연결 성공 / 본인 계정 등록 대기 (2026-10-18)

사용자가 중복 APP_RECOVERY_REQUIRED를 false 하나로 정리하고 저장·재빌드·배포했다. Render 배포 `dep-dbadp5e1egvs73aaq540`가 커밋 `9f7cb26`으로 Live인 것을 확인했다. 실제 HTTPS Render liveness/readiness와 Vercel `/api/v1/auth/csrf` 모두 200이다(`evidence/bootstrap-live-deployment.json`). 허용된 이메일도 공개 signup에서 403 REGISTRATION_CLOSED로 거부되는 것을 확인했다(`evidence/bootstrap-public-signup-closed.json`). 검증 요청은 termsAccepted=false였으며 계정/동의를 만들지 않았다.

본인이 직접 비밀번호를 정하고 동의할 수 있게 `scripts/personal-registration.py`를 실행했다. 임시 frontend localhost:3004/signup, backend 127.0.0.1:8086이 200으로 응답한다. 터널 없이 loopback만 바인딩했고 기존 Docker DB/개발 서버는 보존했다. 화면을 열어 전달했으며 비밀번호 입력·동의·가입 제출은 사용자가 직접 수행한다. 등록 완료 후 이번 임시 프로세스만 종료하고 실제 Vercel HTTPS 로그인/데이터 저장을 검증해야 한다. 이 절차는 아직 완료로 보고하지 않는다.

## 본인 계정 저장 확인 (2026-10-18)

사용자의 가입 완료 안내 뒤 운영 DB의 사용자 1명/기본 카테고리 16개를 집계로 확인했다. 이메일·비밀번호·사용자 행 내용은 조회하지 않았다. 임시 등록 launcher를 정상적으로 인터럽트하여 이번에 시작한 두 서버만 종료했다. 실제 HTTPS Vercel 로그인 화면을 열어 사용자에게 전달했다. 로그인 자격 증명은 사용자가 직접 입력하며 실제 로그인 성공과 실사용 거래 저장은 이후 확인 대상이다. 증거는 `evidence/personal-registration-verified.json`을 따른다.

## 본인 HTTPS 로그인 확인 (2026-10-19)

사용자가 실제 배포 주소에서 로그인 완료를 알렸다. Vercel HTTPS 홈에서 로그아웃/설정과 거래 기록·지도·통계 링크가 있는 인증 후 대시보드를 읽기 전용 확인했다. 비밀번호·쿠키·토큰은 읽지 않았다. 증거는 `evidence/production-personal-login-confirmed.json`과 동일 이름 jpg다. 본인이 입력한 첫 운영 거래의 저장/새로고침 유지 검증은 아직 남아 있으며, 임의 개인 거래를 생성하지 않았다.
