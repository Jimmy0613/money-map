# 현재 시스템 구조

기준일: 2026-10-20. 제품 계약은 [요구사항](requirements.md), 데이터 구조는 [ERD](erd.md), 요청·응답은 [API 명세](api-spec.md)를 함께 확인합니다.

## 실행 구성

브라우저는 Vercel의 Next.js 앱에 접속합니다. 같은 Origin의 API 경로를 통해 Render의 Spring Boot API를 사용하고, API는 Neon PostgreSQL에 Spring JDBC로 접근합니다. 지도와 장소 검색은 Kakao SDK 어댑터를 사용합니다. 지도 연동 실패 시에도 위치 없는 거래를 기록할 수 있습니다.

백엔드는 Java 21 / Spring Boot 3.5.16이며 JPA 엔티티·Hibernate를 사용하지 않습니다. Flyway V1~V6가 변경 이력을 관리하고 LedgerSchemaValidator가 스키마 계약을 검사합니다. 과거 migration도 운영 검증에 필요하므로 삭제하거나 다시 쓰지 않습니다.

## 인증과 일관성

- Access·Refresh 토큰은 쿠키로 전달하며 CSRF와 Origin을 검사합니다. 사용자 소유권을 API와 DB 제약으로 보호합니다.
- 생성 요청의 멱등성과 revision 기반 커서로 중복 제출과 변경 중 페이지 탐색을 다룹니다. 오류·재시도 계약은 API 명세를 따릅니다.
- 금액은 KRW 정수이며 날짜와 시각은 구분합니다. 지도·소비 통계의 기본 대상은 지출입니다.
- 체험 계정은 개인 계정과 분리하고 생성·세션·요청 한도를 적용합니다. 일반 회원가입은 운영에서 닫습니다. [체험 설정](public-demo-setup.md)을 참조합니다.

## 운영 상태 제어

독립 Cloudflare Worker와 Durable Object가 복원·배포 상태를 관리합니다. 백엔드 RecoveryGate는 신선한 서명 응답과 환경·배포 revision을 확인합니다. DB만 과거로 복원했을 때 서비스가 자동으로 쓰기를 재개하지 않도록 합니다. Worker 등록, 서명 키, 배포 revision은 실제 운영 의존성입니다.

백엔드 변경 배포는 [기존 등록 갱신](recovery-deployment-upgrade.md)을 따릅니다. 구조 구현과 전체 운영 백업·WAL 복원 훈련 완료는 구분합니다. 잔여 조건은 [복원 절차](restore-runbook.md)와 [제어 계약](recovery-authority-contract.md)에 남깁니다.

## 첫 방문과 배포 단위

첫 화면은 소개를 먼저 보여줍니다. 체험하기 선택 후 로그인 화면에서 서버 준비 상태와 경과 시간을 안내합니다. 제한된 재시도 뒤 재시도 선택을 제공하며 상시 깨우기 요청은 보내지 않습니다. [화면 동작](first-visit-2026-10-20.md), [기동 분석](startup-analysis-2026-10-20.md)을 참조합니다.

프런트엔드, 백엔드, 독립 제어기는 별도 배포 단위입니다. 각 npm 잠금 파일과 Gradle Wrapper는 필요한 빌드 입력입니다. 기기별 캐시와 비밀값은 제외하며 [개발 안내](development.md)를 따릅니다.
