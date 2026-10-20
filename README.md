# Money Map

소비 장소를 지도와 통계로 확인하는 수입·지출 가계부입니다.

- [공개 서비스](https://money-map-frontend-murex.vercel.app)
- [포트폴리오 PDF](output/pdf/money-map-fullstack-portfolio-v2.pdf)
- [세 기기 공통 개발 안내](development.md)

## 현재 상태

2026-10-20 기준 공개 체험 서비스를 운영합니다. 일반 회원가입은 닫고 개인 데이터와 분리한 체험 계정을 제공합니다. 첫 화면에서 서비스를 소개하며, 체험하기를 누른 뒤 서버 준비 상태를 안내합니다. 무료 Render 인스턴스는 절전 후 응답까지 시간이 걸릴 수 있습니다. 준비 안내는 경과 시간을 표시하며 완료 시간을 보장하지 않습니다.

Next.js 16.4.2 / React 19.2.4 / TypeScript, Java 21 / Spring Boot 3.5.16 / Spring JDBC 구성입니다. Flyway V1~V6와 별도 스키마 검사기를 사용하며 JPA는 사용하지 않습니다. 운영 DB는 Neon PostgreSQL 18.6, 로컬 Compose DB는 PostgreSQL 17.6입니다. Node는 22.22.0으로 고정합니다.

최근 확인한 검증은 프런트엔드 69개, 백엔드 69개, 실제 DB·재시작 통합 30개입니다. 시점과 범위는 [검증 보고서](test-report.md)에 구분했습니다. 전체 요구사항 완료나 무료 환경의 가용성을 보장하는 수치는 아닙니다.

## 개발과 문서

처음 설치하거나 다른 PC에서 이어갈 때는 [development.md](development.md)를 먼저 읽습니다. 소스·공용 설정·잠금 파일은 Git으로 공유하고 비밀값과 로컬 DB는 기기별로 관리합니다.

| 목적 | 문서 |
|---|---|
| 현재 구조 | [구조](architecture.md), [ERD](erd.md), [API](api-spec.md) |
| 제품 계약 | [요구사항](requirements.md), [인수 기준](acceptance-criteria.md), [추적표](requirement-test-matrix.md), [승인 이력](change-log.md) |
| 운영 배포 | [Render](render-deployment.md), [Neon](neon-setup.md), [Cloudflare](cloudflare-setup.md), [최초 구성](bootstrap-deployment.md) |
| 배포 변경·복원 | [배포 갱신](recovery-deployment-upgrade.md), [제어 계약](recovery-authority-contract.md), [복원 절차](restore-runbook.md) |
| 공개 체험·비용·보안 | [체험 설정](public-demo-setup.md), [공개 전 점검](public-demo-final-review-2026-10-19.md), [보안](security-review-2026-10-19.md), [컨테이너 검사](container-security-2026-10-19.md) |
| 기동 개선과 안내 | [분석](startup-analysis-2026-10-20.md), [검증](startup-review-2026-10-20.md), [첫 방문 화면](first-visit-2026-10-20.md) |
| 지역 데이터 | [원본과 재생성](region-data.md) |

날짜가 붙은 보고서는 당시 관측 기록입니다. 요금·사용량·취약점은 이후 달라질 수 있으므로 공개 규모를 늘리기 전 다시 확인합니다. 운영 권한 제어와 서명 검증은 실제 서비스 의존성이므로 삭제하거나 우회하지 않습니다.

## 구성

- `frontend/`, `backend/`: 서비스와 테스트
- `infra/recovery-authority/`: 독립 운영 상태 제어 Worker
- `scripts/`: 로컬 실행·검사·복원 도구
- `evidence/`: 지역 원본과 선별한 검증 근거
- `portfolio/`, `output/`: 최신 포트폴리오 생성기·PDF·필요 이미지
- `.codex/`, `.agents/`: 기기 간 공유하는 개발 도구 설정

정리 방침은 [저장소 관리 안내](repository-maintenance.md)를 따릅니다.
