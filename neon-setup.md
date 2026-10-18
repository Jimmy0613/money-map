# Neon 연결 결과

2026-10-18. 사용자 지정 공식 스킬 URL과 설치된 neon/SKILL.md의 SHA256이 일치함을 확인했다. neon/neon-postgres/neon-postgres-branches 지침에 따라 기존 Spring Boot JDBC·Flyway와 자체 인증을 유지했다.

## 변경 사항

- 공식 Neon CLI8.8.0을 `.tooling/neon-cli`의 격리 npm 전역 prefix에 설치했다. `scripts/neon` 래퍼로 이 프로젝트의 Node/CLI를 선택한다. 시스템 Node/npm 설정은 변경하지 않았다.
- `.agents/skills`에 공식 Neon 스킬9개 및 `skills-lock.json`이 설치됐다. 지정 URL 재확인 때는 같은 내용이라 재설치하지 않았다. 부속 reference 파일도 설치 도구가 제공한 파일이다.
- `.codex/config.toml`에 OAuth 방식 Neon MCP를 추가하고 `wispy-leaf-78515154`로 고정했다. API 키를 프로젝트 파일에 넣지 않았다. OAuth 인증 후 실제 MCP list_branches 읽기 성공을 확인했다.
- 사용자 지정 조직/프로젝트의 production 브랜치를 `.neon`에 연결했다. `.neon`과 루트 node_modules는 gitignore에 추가했다.
- CLI가 neon.ts와 루트 package.json/package-lock.json을 생성했다. 사용 서비스는 Postgres뿐이다. `neon config plan`과 `neon deploy`는 모두 원격 정책 변경 없음으로 성공했다.
- 기존 `.env` 변수 값은 모두 보존됐다. DATABASE_URL, DATABASE_URL_UNPOOLED, NEON_BRANCH만 추가됐다. 기존 값의 비공개 사본은 `.tooling/neon-setup/before-*`에 보관했다. 연결 URL/인증 정보는 출력하지 않았다.
- Backend NeonEnvironment 환경 처리기를 추가했다. 명시적으로 neon 프로필을 켤 때만 Neon URL을 JDBC 설정으로 변환한다. 일반 요청은 pooled URL, Flyway는 direct URL이다. TLS 인증서/호스트 검증과 channel binding, 작은 커넥션 풀을 사용한다. local 설정을 자동으로 Neon으로 바꾸지 않는다.
- 루트 npm run build는 기존 프런트 빌드에 위임한다. `scripts/check-neon-connection.py`와 `scripts/e2e-neon.cjs`는 기존 실행본을 보존한 별도 빌드에서 실제 Neon 브라우저 검증을 수행한다.

## 실제 검증

- Backend 단위·HTTP46개 실패0, bootJar 성공: evidence/neon-backend-checks.log.
- npm run build 성공: evidence/neon-build.log. 기존 `.next` 보존을 위해 복사한 소스/루트 package.json에서 실행했다.
- 실제 Neon schema-only 브랜치 `money-map-wiring-20261018`에서 V1~V5/Flyway와 JPA validate, 서버 readiness 성공.
- 실제 Next.js→Spring Boot→Neon 경로의 가입·로그인·거래 저장·새로고침 후 조회·통계 대조·합성 계정 탈퇴/세션 거부까지 성공: evidence/neon-live-e2e.log. Mock API를 사용하지 않았다.
- 테스트는 production 쓰기 없이 별도 브랜치에서 수행했다. 해당 브랜치에는 자동 만료를 설정했고 검증용 서버는 종료했다. 사용자 로컬 DB는 연결/초기화하지 않았다.

## 발생한 실패 및 경고

초기 npx 명령과 기본 init의 플러그인 설치가 Node 경로에서 npm 전역 lib 디렉터리를 찾지 못해 실패했다. 격리 npm prefix로 CLI를 설치했고, 스킬·MCP는 직접 설정한 뒤 init --no-agent-setup으로 중복 설치를 피했다. 실패 로그는 비공개 `.tooling/neon-setup`에 보존했다.

Neon 프로젝트의 현재 PostgreSQL은18.6이다. 로컬 기본 검증 버전17.6보다 새 버전이며 Flyway가 지원 버전 경고를 냈다. 실제 V1~V5 및 앱 흐름은 통과했지만 이를 PostgreSQL18 전체 호환성/부하 검증으로 확대하지 않는다.

## 아직 배포 완료가 아닌 부분

production 브랜치의 앱 마이그레이션/실사용 데이터 이전, Render/Vercel 서버 배포, 운영 URL/Kakao 등록, 개인·시연 접근 제한과 운영 RecoveryGate의 bootstrap/갱신·관리형DB 결합은 남아 있다. 기존 운영 차단을 끄거나 가짜 개방 증거를 발급하지 않았다. 이번 성공은 loopback의 local,neon 프로필 및 별도 DB 브랜치 검증이다. 실제 공개 HTTPS 서비스가 시작된 상태가 아니다.
