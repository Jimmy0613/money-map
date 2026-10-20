# 컨테이너 보안 점검

2026-10-20 재검사: JDBC 정리 커밋 8c250df의 [CI 실행](https://github.com/Jimmy0613/money-map/actions/runs/39348114748)이 성공했다. 전체 Critical 2·Medium 119·Low 13, 비 Java High·Critical 0으로 기존 Spring 조건부 권고가 남는다. 운영은 같은 커밋 Live·등록 세대 5이며 [최신 검증](startup-review-2026-10-20.md)을 따른다. 아래 이미지 ID와 세대 4는 10월 19일 검사 이력이다.

남은 권고의 구체적 발생 조건, Spring 공식 등급과 스캐너 등급의 차이, 공개·유료 수정판 구분은 [최종 문서 대조](public-demo-final-review-2026-10-19.md)를 따른다.

## 검사 방식

로컬 Docker Desktop을 다시 실행하지 않았다. 공식 Anchore Grype 0.120.1의 Windows 배포본을 SHA-256 확인 후 실행해 공개 기반 이미지를 검사했다. GitHub Actions에서는 운영 비밀값 없이 저장소의 backend/Dockerfile로 별도 이미지를 만들고 전체 파일 시스템을 검사한다. 이 이미지는 Render의 내부 배포 이미지와 바이트 단위로 동일하다고 보증하지 않는다. 기반 태그와 apt 저장소가 바뀔 수 있으므로 검사 시점과 이미지 식별자를 함께 보관한다.

워크플로는 읽기 전용 저장소 권한만 사용하며 외부 레지스트리에 이미지를 게시하지 않는다. 체크아웃 자격 증명을 남기지 않고, 사용 Action을 커밋으로 고정했다. Grype는 버전과 체크섬을 고정했다. 검사 DB는 실행 시 갱신하고 전체 결과와 이미지 메타데이터를 14일 보관한다.

## 발견과 수정

검사한 eclipse-temurin:21-jre의 linux/amd64 매니페스트는 sha256:e6354536ca587af353f4927095003b3a5fc29fe1a1380fa1e55caec12815d188, OS는 Ubuntu 26.04였다. 검사 DB 생성 시각은 2026-10-18T06:27:17Z다. 기반 이미지에서 Critical 1건, High 8건, Medium 178건, Low 17건을 보고했다. 권고 수는 공격 경로 수와 같지 않다.

Critical·High 9건은 /usr/bin/pebble에 포함된 Go 1.27.1 표준 라이브러리였다. 이 앱은 ENTRYPOINT로 Java를 직접 실행하므로 Pebble을 서비스 관리자로 사용하지 않는다. 해당 파일을 최종 이미지에서 제거했다. 예외 목록으로 권고를 숨기지 않는다. 대표 권고 GO-2026-6612는 HTTP/2 서버 흐름 제어 문제이며, 파일 존재만으로 현재 Java 서버가 해당 기능을 실행한다고 판단하지 않는다.

단순히 오래된 OS 태그로 바꾸는 방법도 비교했지만, 다른 바이너리 권고가 증가해 적용하지 않았다. 기존 Java와 OS 계열을 유지하면서 사용하지 않는 파일만 제거했다.

## 자동 판정 범위

수정판이 있는 High·Critical의 비 Java 이미지 패키지(배포판 패키지와 Go 등 바이너리 라이브러리 포함)는 검사를 실패시킨다. 수정판 없는 권고와 Medium 이하도 원본 보고서에서 보존한다. 이 기준 통과는 취약점 0건을 뜻하지 않는다.

Java 아카이브는 별도 실행 의존성 검토 대상이다. 83개 Maven 실행 의존성을 OSV로 재조회했고 기존 Spring Web MVC 6.2.19 권고 2건이 그대로 남았다. 사용하지 않는 SSE view fragments와 XsltView 조건에 관한 검토는 [보안 점검](security-review-2026-10-19.md)에 있다. Java를 자동 판정에서 제외했다는 이유로 신규 권고를 무시해서는 안 된다.

기존 RecoveryAuthorityIntegrationTest도 격리 CI에 연결했다. 실제 PostgreSQL 컨테이너 재시작 후 이전 증거 거부, 서명·nonce·세대·유효기간 등 안전 검사를 검증한다. 운영 DB나 운영 키는 연결하지 않는다.

## 실행 결과

커밋 d2d0aade91525c51e18b3259d013f5c4a08330f6의 검사 실행 39209107011은 두 작업 모두 성공했다. RecoveryAuthorityIntegrationTest 11개는 실패·오류·건너뜀 없이 통과했다. 로컬에서 남아 있던 컨테이너 재시작 검증을 격리 환경에서 완료한 것이다.

빌드한 앱 이미지 ID는 sha256:41f69b1d3809d421cf88fc55e7ff23df5470abcafdaa656cf947da40f019309b다. 실행 사용자는 app, 진입점은 java -jar app.jar다. 최종 파일 시스템에서 Pebble 관련 Go 권고가 사라졌고, Java 이외의 High·Critical은 수정판 유무와 무관하게 0건이었다. 전체 보고서는 Critical 2건, Medium 119건, Low 13건이다. Critical 2건은 앞서 검토한 동일 Spring Web MVC 권고이며 새로 발견된 웹 공격 경로라고 판단하지 않는다. 중·하위 등급은 남아 있으므로 전체 이미지 취약점 0건이라고 보고하지 않는다.

실패 판정 도구는 수정판 있는 OS High, Go Critical을 거부하고, 수정판 없는 항목·별도 검토하는 Java의 보고 범위를 보존하며, 빈 탐지 결과·불완전 보고서를 구분하는 6개 입력으로 확인했다.

## 운영 반영

Render 배포 dep-dbarc5rm8hqs73bn389g는 위 수정 커밋으로 Live를 확인했다. 운영 권위 등록은 기존 데이터·키를 유지한 채 RELEASED 세대 4로 갱신하고 기존 공개 키로 새 증거의 서명을 확인했다. readiness 200 UP, 동시 CSRF 조회 5건 모두 200, 공개 회원가입 403 REGISTRATION_CLOSED를 확인했다. 배포된 내부 이미지의 직접 추출 검사는 하지 않았으며, 검사한 별도 빌드 이미지와 운영 커밋을 연결한 확인이다.

## 참고

- [Grype 공식 설치 안내](https://oss.anchore.com/docs/installation/grype/)
- [Grype 검사 대상](https://oss.anchore.com/docs/guides/vulnerability/scan-targets/)
- [Go 공식 권고 GO-2026-6612](https://pkg.go.dev/vuln/GO-2026-6612)
- [공식 Temurin Dockerfile 저장소](https://github.com/adoptium/containers)
- [검사 실행](https://github.com/Jimmy0613/money-map/actions/runs/39209107011)
