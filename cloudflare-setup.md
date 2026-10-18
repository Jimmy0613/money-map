# Cloudflare 에이전트 설정 (2026-10-18)

사용자가 지정한 공식 안내 https://developers.cloudflare.com/agent-setup/prompt.md 를 가져와 Codex용 절차를 적용했다. 기존 Neon 구성과 사용자 설정은 보존했다.

- 공식 `cloudflare/skills` 저장소의 스킬 19개를 Codex 설치 도구로 `/Users/lmj/.codex/skills/`에 설치했다. 기존 디렉터리는 덮어쓰지 않았다. 스킬은 다음 턴부터 사용할 수 있다.
- 사용자 설정 `/Users/lmj/.codex/config.toml`에 `cloudflare` MCP (`https://mcp.cloudflare.com/mcp`)를 추가했다. 변경 전 설정은 ignored 비공개 디렉터리에 백업했다. OAuth 로그인 성공 및 enabled/streamable_http 구성을 확인했다. 실행 중인 세션에 MCP 도구 목록을 반영하려면 Codex 재시작이 필요하다. 실제 MCP 도구 호출 성공과는 구분한다.
- Wrangler 4.151.1을 프로젝트 도구 폴더 `.tooling/cloudflare/`에 설치하고 기존 Cloudflare 계정 OAuth를 연결했다. 사용자/계정 조회와 Workers 배포에 필요한 범위로 인증했다. 최초 인증 시간 초과 뒤 재시도에서 성공했다.
- 선택 항목인 베타 `cf` CLI는 설치하지 않았다. 프로젝트는 `infra/recovery-authority/wrangler.jsonc`와 Wrangler로 관리한다. 전역 Node/npm 설정이나 사용자 지침 파일은 변경하지 않았다.

로그인 토큰, 서비스 키, DB 비밀번호는 소스/문서에 기록하지 않는다. 스킬/MCP 설치 완료는 운영 서비스 개방 완료를 뜻하지 않는다.
