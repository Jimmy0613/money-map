# 복원 제어 계층 실행 계약

2026-09-25. 구현: Backend `RecoveryGate`와 독립 실행 도구 `scripts/recovery-authority.py`. 이 문서는 실제 운영 최신성 검증기의 구현·연결 또는 배포 완료 증거가 아니다. 운영 저장소가 정해지지 않아 WAL 확보/연속성·동기 내구성 검사와 ingress 제어는 아직 연결하지 않았다. 합성 테스트 서명키·성공 receipt를 운영에서 사용하지 않는다.

## 애플리케이션 접근 검사

운영 프로필은 외부 권위 서버의 유효한 응답 없이는 모든 요청에503 `RECOVERY_REQUIRED`를 반환한다. `/actuator/health/liveness`만 예외다. `APP_RECOVERY_REQUIRED=true`는 강제 차단이고 false는 공개 승인이 아니다. 설정 누락도 차단한다.

필수 설정은 `RECOVERY_AUTHORITY_URL`, `RECOVERY_AUTHORITY_PUBLIC_KEY`, `RECOVERY_ENVIRONMENT`, `RECOVERY_DEPLOYMENT`다. public key는 Ed25519 X.509 DER의 Base64이며 비밀키를 Backend에 전달하지 않는다. URI는 HTTPS 또는 숫자 loopback HTTP만 허용하고 redirect·userinfo·query·fragment를 거부한다. 별도 호스트에서 제어 서버를 운영하려면 TLS/접근 제어 프록시가 필요하다.

각 요청은 DB system identifier, DB OID, PostgreSQL 시작 시각, Flyway 이력을 SHA256으로 결합한다. 따라서 DB 교체·DB 재시작·Migration 변경 뒤에는 다시 검증해야 한다. 지문은 최신 삭제 commit 증명이 아니다. 물리 복원은 PostgreSQL을 정지하고 새 격리 대상에 수행해야 하며, 실행 중인 DB에 관리자 권한으로 DML을 되돌리는 작업을 이 지문만으로 탐지할 수 있다고 주장하지 않는다. 애플리케이션 역할은 `pg_control_system()` 조회 권한이 필요하며 권한이 없으면 차단한다.

Backend는 환경·배포·지문·새 UUID nonce를 POST하고, pinned 공개키로 응답 서명을 검증한다. 응답의 같은 결합값, RELEASED 상태, 양수 세대, 최대5초 유효기간을 검사한다. 응답 캐시는 없다. HTTP 응답은8KiB/750ms, 동시 검증8개로 제한하며 초과·실패는 차단한다. DB 쿼리 timeout은1초이고 커넥션 대기는 datasource 설정을 따른다. Secret·응답 본문·DB 지문·SQL 오류는 로그로 내보내지 않는다.

`local` 예외는 `http://localhost[:port]` origin만 허용하고 기본 bind는127.0.0.1이다. 필터도 직접 non-loopback 접근을 거부한다. local 개발 서버를 공개 프록시나 터널에 연결해 예외를 우회하면 안 된다. 기존 휴대폰 서버는 이전 실행본 그대로 유지했다. 새 운영 빌드는 증거 없이 휴대폰 URL에 교체하지 않는다.

## 독립 상태 저장소와 실행 명령

`recovery-authority.py`는 Python 표준 라이브러리/SQLite/OpenSSL의 Ed25519를 사용한다. 서비스 DB 백업과 독립된 영속 경로 및 OS 권한이 필요하다. DB는 생성 시0600이며 기존 파일을 초기화하거나 덮어쓰는 기능은 없다. DB·WAL 백업과 함께 이 저장소를 과거로 되돌려서는 안 된다. 실제 독립 보존/접근 정책은 배포 환경에서 검증해야 한다.

다음은 **명령 형식**이며 현재 작업에서 운영 키나 운영 저장소를 만들지 않았다. 경로는 운영자가 관리하는 위치다. private key는 애플리케이션 JWT/cursor 키와 별도이며, 검증기 키와 제어 서버 응답 서명키도 분리한다.

```sh
python3 scripts/recovery-authority.py --store /독립경로/authority.sqlite init --verifier-public-key /독립경로/verifier-public.pem
python3 scripts/recovery-authority.py --store /독립경로/authority.sqlite begin --environment production --deployment candidate-id --target 대상DB지문 --expected-generation 현재세대 --owner worker-id
python3 scripts/recovery-authority.py --store /독립경로/authority.sqlite advance --receipt /검증결과/receipt.json --verifier-public-key /독립경로/verifier-public.pem --owner worker-id
python3 scripts/recovery-authority.py --store /독립경로/authority.sqlite serve --signing-key /독립경로/authority-private.pem --port 8091
```

초기 세대는0이며 begin은 현재 세대 CAS/60초 작업자 lease를 검사하고 세대를 증가시켜 BLOCKED로 만든다. CLI begin/advance는 운영 파일 접근 권한을 가진 제어기의 작업이다. HTTP에는 상태 변경/공개 전환 API가 없다. 서버는127.0.0.1에만 바인딩한다. 초기화 시 verifier 공개키 지문을 pin하고 advance 때 임의 키로 바꾸는 것을 거부한다.

각 advance는 서명 검증 후 정확히 다음 단계만 허용한다. `BLOCKED → FENCED → LEDGER_VERIFIED → RESTORED → RECONCILED → VALIDATED → RELEASED`. SQLite 트랜잭션으로 상태와 감사 이력(sequence/환경/세대/단계/시각/receipt hash)을 함께 기록한다. 실패하면 이전 상태를 유지한다. BLOCKED~VALIDATED는 공개되지 않는다. RELEASED 갱신 실패 후에는 이전 receipt의 남은 유효기간만 인정하며 최대60초 후 차단한다. 즉시 철회할 때는 begin으로 세대를 올려 차단한다. 새 세대의 이전 응답은 nonce 때문에 재사용할 수 없다.

정상 신규 빈 DB의 별도 bootstrap 검증·등록 절차는 아직 제공하지 않는다. 신규 운영 환경도 자동 개방되지 않는다. 운영 자동 갱신 검증기가 없으므로 이 도구만 기동해 일반 서비스를 지속 운영할 수 없다.

## 서명된 검증 결과 계약

Envelope는 `payload`(원본 JSON bytes의 Base64), `signature`(그 bytes에 대한 Ed25519 서명 Base64)다. 제어 서버는 임의 CLI boolean을 증거로 인정하지 않는다. **pin된 독립 검증기의 서명을 신뢰하는 구조**이며, 서명 자체가 WAL 사실을 증명하는 것은 아니다. 검증기가 실제 저장소와 쓰기 차단 결과를 확인한 뒤 아래 값들을 발급해야 한다. 그 실제 어댑터는 아직 미구현이다.

공통 필드: version=1, environment, deployment, generation, state, target, sourceSystem, timelineHistoryDigest, targetLsn, ledgerDigest, runId, issuedAt, expiresAt. 시각은 UTC epoch 초다. 발급5초 이내/최대60초 만료이며 환경·배포·세대·대상은 현재 상태와 같아야 한다. FENCED 이후 source/목표LSN/timeline digest/ledger digest/run ID를 바꾸려면 새 세대로 다시 시작한다.

단계별 누적 검증:

| 단계 | 별도 검증기가 실제로 확인해야 하는 필드 |
|---|---|
| FENCED | fenced=true: ingress와 모든 쓰기 주체 중단·진행 중 commit 완료, 목표 WAL 고정 |
| LEDGER_VERIFIED | walContinuous=true, durableCommitBoundary=true: 독립 WAL의 연속성/계보와 최신 확정 commit 보전 |
| RESTORED | publicOnly=true: 새 격리 대상 public 복원, 최신 privacy 독립 보전 |
| RECONCILED | deletedUsersRemaining=0, sessionsRemaining=0, orphansRemaining=0 |
| VALIDATED | schemaVerified=true, oldCredentialsRejected=true |
| RELEASED | ingressBound=true: 동일 대상에 대한 배포 결합·연결 계획 검증 |

실제 ingress 개방은 별도 제어기의 원자적 대상 전환과 쓰기 fence에 의존한다. 개별 HTTP gate 검사만으로 진행 중인 요청까지 중단하거나 검증~실행 사이 모든 경합을 해결하지 않는다. 암호화·30일 만료·ledger 정리 증거도 이 프로토콜의 boolean으로 대체하지 않는다.

## 실행 검증과 남은 작업

Python 검사는 임시 SQLite와 합성 Ed25519 키를 사용하며 실제 루프백 HTTP 응답 검증도 포함한다. Backend 검사는 실제 Testcontainers PostgreSQL과 서명 HTTP 응답으로 DB 교체/재시작/스키마 변경, nonce 재사용, 서명 위조, 만료, 장애, 상태/대상 불일치를 검사한다. 기존 비즈니스 계약 검사에서는 권위 서버만 mock하고, 실제 gate는 별도 통합 검사로 분리했다. 자세한 개수·로그는 test-report.md 최신 절 참조.

남음: 운영 호스트/독립 저장소 선택, WAL·fence·ingress 실제 검증기 및 bootstrap/갱신 실행기 구현·연결, 비밀키 관리, 외부 TLS, 백업 암호화/보존 정책, 실제 전체 복원 훈련. 기존 restore-public.py는 여전히 releaseAllowed=false를 유지한다. 현재 도구를 운영 복원 전체 완료로 판정하지 않는다.


## 2026-10-18 신규 빈 Neon DB bootstrap 보완

정상 신규 빈 DB를 위한 독립 Cloudflare Durable Object 등록과 실시간 Neon 관찰·서명 경로를 추가했다. 상세 실행 경계와 증거는 [bootstrap-deployment.md](bootstrap-deployment.md)를 따른다. 기존 복원 상태 전이와 WAL 최신성·privacy 재삭제·ingress 조건은 완화하지 않는다. 초기화/복원/계보·스키마 변경은 기존 등록을 BLOCKED로 보존하며 이 초기 등록 경로로 다시 열 수 없다. 실제 WAL 전체 복원 승인은 여전히 미구현/미검증이다. 신규 운영 연결 결과는 배포 기록에 별도로 남긴다.
