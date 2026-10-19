# 서버 배포 등록 갱신

일반 서버 코드 변경을 새 Git 커밋으로 배포하기 위한 관리 경로다. DB 복원 승인, 차단 해제, DB 교체, 계보·스키마 변경 승인을 대신하지 않는다. 이 기능을 구현했다고 운영 배포를 완료한 것은 아니다.

## 허용 조건

- POST /upgrade는 기존 ADMIN_TOKEN으로만 호출한다. DECISION_TOKEN이나 비인증 요청은 거부한다.
- 새 deployment는 Worker의 RECOVERY_DEPLOYMENT와 일치하는 소문자 40자리 Git SHA여야 한다. 브랜치명·짧은 SHA·임의 커밋을 받지 않는다.
- 기존 등록이 RELEASED이고 환경, previousDeployment, expectedGeneration이 현재 등록과 정확히 같아야 한다. BLOCKED·미등록 상태는 거부한다.
- Neon provider와 메타데이터 전용 SQL 관찰을 다시 수행한다. 최초 등록과 동일한 provider, 논리 DB·계보·스키마 결합을 유지해야 한다. 개인정보나 거래 내용은 읽지 않는다.
- 복원·reset·계보·스키마·endpoint 변경이 발견되면 기존 차단 정책에 따라 BLOCKED를 보존한다. 네트워크 장애는 요청만 실패시키며 등록을 덮어쓰지 않는다.
- 요청 target은 재관찰한 현재 물리 지문과 같아야 한다. compute 절전·재기동은 기존과 같은 실관찰 검사를 통과한 경우에만 인정한다.
- 외부 관찰 이후 현재 상태·이전 커밋·세대를 다시 비교한다. 동시 갱신은 한 요청만 성공한다. 관찰 중 block이 발생하면 갱신할 수 없다.
- 새 등록 세대는 정확히 1 증가한다. 등록과 일반 감사 이력, 배포 감사 이력을 SQLite 동기 트랜잭션 하나로 저장한다. 감사 저장 실패 시 전부 롤백한다. 기존 등록 시각·DB 결합·과거 감사 기록은 유지한다.
- 성공 요청의 재전송은 기존 세대가 달라 409로 거부한다. 이때 새 요청으로 무작정 다시 갱신하지 않고 /status 및 배포 상태를 확인한다.

## 요청과 응답

Content-Type: application/json, Authorization: Bearer ADMIN_TOKEN. 토큰은 비공개 설정에서 읽고 소스·명령 인자·로그에 넣지 않는다. 요청 본문 최대 2048바이트와 기존 인증 경계를 유지한다.

```json
{
  "environment": "production",
  "deployment": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "previousDeployment": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "expectedGeneration": 1,
  "target": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "nonce": "00000000-0000-4000-8000-000000000001"
}
```

위 값은 형식 예시다. 실제 배포에는 사용하지 않는다. target은 기존 검증 도구와 동일하게 실제 metadata observation의 physical 문자열에 SHA-256을 적용한 값이다. expectedGeneration은 관리 /status에서 확인하며 nonce는 요청마다 UUID v4로 만든다.

성공은 200과 state=RELEASED, 증가한 generation, 새 deployment를 반환한다. 입력 오류는 400 INVALID_UPGRADE, 이전 등록·세대·상태 불일치는 409 REGISTRATION_CONFLICT, 물리 지문 불일치는 409 TARGET_CHANGED다. 검증 장애·DB 결합 변경은 503 RECOVERY_REQUIRED다. 성공 후에도 /decision은 매 요청 실관찰과 기존 Ed25519 서명·nonce·최대 5초 유효기간 검사를 계속 수행한다.

## 운영 적용 순서

1. 별도 검증 환경에서 이 Worker 버전의 정책·런타임 테스트 및 실제 Neon 연결 검증을 완료한다. 이번 로컬 런타임 테스트는 관찰 응답을 합성하여 동시성과 저장을 검증하며 실제 Neon 연결 검증을 대체하지 않는다.
2. 배포할 서버의 최종 커밋을 확정하고 현재 Render 배포 커밋·등록 세대·환경을 확인한다. DB migration이 달라지는 배포는 이 코드 전용 갱신 경로를 사용하지 않는다.
3. Worker의 기존 Secret·Durable Object 이름·환경·키·등록을 보존한다. 새 Worker를 배포할 때 RECOVERY_DEPLOYMENT를 새 최종 커밋으로 설정한다. 저장소 기본 UNREGISTERED 값을 운영에 그대로 배포하지 않는다.
4. 관리 권한으로 /upgrade를 호출한다. Worker 환경 변경부터 Render 새 서버 준비까지 API가 차단되는 유지보수 시간이 발생한다. 무중단 배포 기능은 아니다.
5. 갱신 성공 후 동일 커밋의 서버를 Render에 배포하고 RECOVERY_DEPLOYMENT도 맞춘다. 실제 RENDER_GIT_COMMIT 검사를 우회하지 않는다.
6. liveness뿐 아니라 readiness, 인증·조회·변경 API, 공개 체험 계정 제한을 운영에서 확인한다. 실패하면 자격 증명 공개를 보류한다.
7. 롤백도 현재 세대·현재 커밋을 기준으로 명시적 갱신과 재관찰이 필요하다. 예전 요청 재전송, 등록 삭제·재생성, 보호 해제로 되돌리지 않는다.

운영 관리 토큰과 검증 DB 연결 값이 없는 상태에서는 실제 갱신을 수행하지 않는다. 운영 값의 변경 및 실제 서비스 API 사용 가능 확인은 별도 실행 근거로 기록한다.

## 로컬 검증

infra/recovery-authority에서 npm test와 npm run test:runtime을 실행한다. 표준 Cloudflare Workers 런타임과 실제 Durable Object SQLite에서 기존 등록 보존, 관리자 인증, 세대 충돌, 동시성, block 우선, 감사 원자성, 객체 eviction 이후 보존 및 새 커밋에 대한 Ed25519 서명을 검증한다. 현재 운영 등록을 수정하지 않고 실행할 수 있다.

## 2026-10-19 실제 연결 검증

기존 격리 브랜치에 새 빈 money_map_upgrade_20261019 DB를 만들고 정상 Flyway V1~V6를 적용했다. 운영 거래를 복사하거나 기존 DB를 초기화하지 않았다. 메타데이터 전용 역할은 관찰 함수를 실행할 수 있지만 사용자 이메일·비밀번호 조회와 탈퇴 이력 변경은 거부됨을 확인했다.

별도 검증 Worker에서 최초 등록과 이전 커밋의 서명 판단, 신규 커밋으로 갱신, 잘못된 인증·세대·재전송·이전 커밋 거부, 새 세대의 실제 Ed25519 서명, 명시적 차단 후 갱신 거부를 확인했다. 실행 도구는 scripts/check-upgrade-live.mjs이며 운영 환경 이름·브랜치·DB는 입력으로 받지 못하도록 제한한다. 검증 Worker는 마지막에 BLOCKED로 남겼다. 이 기록은 운영 배포 완료를 뜻하지 않는다.
