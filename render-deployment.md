# Render 서버 첫 배포

2026-10-18. 프런트 공개 주소는 https://money-map-frontend-murex.vercel.app 이다. 로그인 화면 표시를 확인했다. 첫 서버 배포는 네트워크·실행 환경을 확인하는 차단 상태이며, 아직 개인 실사용 공개 승인이 아니다.

## Render 설정

- 저장소: Jimmy0613/money-map, 브랜치 main
- Runtime: Docker
- Root Directory: backend
- Dockerfile: Root Directory 기준 ./Dockerfile
- Region: Singapore
- Compute: Free ($0/month)
- Advanced → Health Check Path: /actuator/health/liveness

backend.env에는 BACKEND_PORT=10000을 사용한다. Render의 기본 PORT와 맞춘 값이다. SPRING_PROFILES_ACTIVE=prod,neon으로 pooled JDBC와 direct Flyway를 구분한다. JAVA_TOOL_OPTIONS는 무료512MB 인스턴스용 시작 한도를 설정했으며 실제 Render에서 메모리·시작 시간을 별도로 확인해야 한다.

환경 변수는 .tooling/render-setup/backend.env에0600으로 보관한다. 기존 로컬 .env를 그대로 업로드하지 않는다. DB URL과 운영용 서명키 값은 문서/로그/채팅에 넣지 않는다. 현재 APP_ORIGIN은 위 Vercel 주소이고 APP_RECOVERY_REQUIRED=true다.

liveness를 health check로 지정하는 이유는 서버 프로세스 배포와 API 공개 승인을 구분하기 위해서다. 이 단계에서는 readiness와 로그인/가입/개인 API가503 RECOVERY_REQUIRED여야 한다. 다른 경로를 health check로 우회하거나 local 프로필을 공개 환경에서 사용하지 않는다.

## 이후 확인 순서

1. GitHub에 최신 Neon 연결 코드가 반영된 commit으로 배포됐는지 확인한다.
2. Render의 생성된 HTTPS URL과 liveness200/API503 차단을 확인한다. 실제 시작/메모리 실패가 있으면 로그의 Secret/SQL/URL을 노출하지 않고 진단한다.
3. 실제 Render URL을 Vercel BACKEND_INTERNAL_URL에 넣고 프런트를 다시 빌드한다. Next.js rewrite는 빌드 시 확정된다.
4. Kakao 허용 도메인에 Vercel의 실제 HTTPS origin을 등록한다.
5. 관리형 DB에 맞는 정당한 신규 bootstrap/개방 증거·갱신 및 개인/시연 접근 범위를 구현·검증한 뒤 API를 개방한다.

현재 APP_RECOVERY_REQUIRED를 false로 바꾸는 것만으로는 RecoveryGate가 열리지 않는다. 실제 최신성 증거 없는 복원·가짜 RELEASED 응답·local 예외를 운영 개방 수단으로 사용하지 않는다. 공개 서버가 실행됐다는 이유로 로그인 가능한 실사용 서비스 완료라고 보고하지 않는다.
