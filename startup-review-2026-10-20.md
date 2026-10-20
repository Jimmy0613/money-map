# JDBC 실행 구성 정리와 기동 비교

## 판단과 변경

거래·인증·조회는 JdbcTemplate과 SQL을 사용하고 JPA Repository·EntityManager를 직접 사용하는 업무 코드는 없었다. 다만 LedgerTransaction 엔티티 1개와 ddl-auto=validate가 있어 JPA가 완전히 무역할인 것은 아니었다. Hibernate가 transactions 테이블의 매핑을 시작 시 검증했다.

- spring-boot-starter-data-jpa를 spring-boot-starter-jdbc로 교체했다. 참조되지 않는 엔티티와 JPA 전용 설정·Hibernate 로그 설정을 제거했다.
- LedgerSchemaValidator가 Flyway 초기화 이후 거래 테이블의 필수 컬럼·타입·길이를 한 번 확인한다. 추가 컬럼은 허용한다. 누락·타입/길이 불일치는 기동을 거부한다. Flyway의 이력 체크섬 검증과 실제 테이블 구조 검사는 다른 역할이다.
- 트랜잭션은 JDBC DataSourceTransactionManager 계열을 사용한다. 기존 사용자 행 잠금·거래/작업 키의 원자성·인증 철회 저장 계약은 실제 DB 통합 테스트로 회귀 확인했다. V1~V6 마이그레이션을 변경하지 않았다.
- 이 검사는 기존 단일 엔티티가 맡던 거래 테이블 검증의 대체다. DB 전체 권한·모든 제약·인덱스·복원 여부를 검증하는 장치로 설명하지 않는다. 초기화 오류를 늦추는 lazy-initialization이나 보안 검사 해제는 적용하지 않았다.

## 다른 의존성·설정 검토

| 구성 | 사용 근거와 판단 |
|---|---|
| JDBC·Hikari·PostgreSQL | 실제 데이터 접근·연결 풀. 유지. Neon의 maximum-pool-size=3, minimum-idle=0 등 기존 설정 유지. |
| Flyway | V1~V6 마이그레이션과 이력 검증. 유지. PostgreSQL 18 지원 범위 경고는 별도 유지보수 과제다. |
| Actuator·Micrometer | readiness/liveness와 지도 오류 계수. 유지. 관리 엔드포인트를 추가 노출하지 않았다. |
| Scheduling | AuthService.expireSessions의 인증 세션 만료 정리. 유지. |
| Bouncy Castle | Argon2PasswordEncoder의 실행 의존성. 유지. |
| Nimbus JOSE JWT | 인증 토큰 서명·검증. 유지. |
| Validation·Security·Jackson | 요청 검증·쿠키/CSRF/권한·JSON 처리. 유지. |
| 프런트 런타임 의존성 | Query·Hook Form·Zod·resolver의 실제 import 확인. 제거하지 않았다. 서버 시작 안내·60초 타임아웃·재연결은 이미 구현돼 있어 중복 구현하지 않았다. |

## 통제한 로컬 비교

Windows x64, 동일 Corretto 21.0.4.7.1, 동일 PostgreSQL 18.0의 loopback 전용 fixture DB를 사용했다. 운영 데이터·키·DB는 연결하지 않았다. local 프로필로 외부 권위 검증을 제외했으므로 이 결과에 Neon 절전 복귀·Render 자원 제한·외부 서명 검증 지연은 포함되지 않는다.

JVM 상한은 두 실행 파일 모두 -Xmx192m, MaxMetaspaceSize=128m, ReservedCodeCacheSize=48m, MaxDirectMemorySize=32m, -Xss512k로 동일하다. JVM 옵션을 바꾸어 속도를 올린 결과가 아니다. 각 1회 예열 후 before/after/after/before/before/after 순서로 3회씩 측정했다. 최초 DB 생성·마이그레이션은 예열에 포함했고 측정에서는 이미 깨어 있는 같은 DB를 사용했다.

| 지표 | 변경 전 | 변경 후 |
|---|---|---|
| 프로세스 실행부터 readiness 200까지(초) | 4.997 / 4.975 / 5.395 | 4.182 / 4.586 / 4.185 |
| readiness 중앙값 | 4.997초 | 4.185초(약 16.2% 감소) |
| Spring 시작 로그 중앙값 | 4.342초 | 3.453초 |
| 실행 JAR 크기 | 69,985,395바이트 | 40,671,247바이트(약 41.9% 감소) |
| JAR 내부 라이브러리 수 | 74 | 54 |
| 시작 중 GC pause 합계 중앙값 | 81.448ms | 64.931ms |

6회 측정에서 Full GC·OOM은 관측되지 않았다. 작은 로컬 표본만으로 운영의 메모리 여유·GC 병목을 판단하지 않는다. 서버 기동 로그의 구간별 시간도 특정 메서드의 순수 실행 비용과 같지 않다. 무료 인스턴스 시작 시간의 개선 비율을 보장하거나 주기적 깨우기 요청·유료 전환을 적용하지 않았다.

재현: scripts/measure-jdbc-startup.py. 기존 disposable DB의 jdbc:postgresql://127.0.0.1:포트/fixture_이름만 허용한다. 원시 로그와 실행 파일은 Git 제외 영역에 보관하며 공개 증빙은 [정제된 측정 결과](evidence/startup-jdbc-2026-10-20.json)다.

## 검증

단위·HTTP·대기 용량 69개와 실제 로컬 PostgreSQL 통합 19개, bootJar 빌드가 통과했다. 새 검증은 ORM 없는 기동과 JDBC 트랜잭션 관리자, 컬럼 누락·타입 변경 거부, DDL 롤백 후 정상 구조 복원을 다룬다. GitHub 격리 CI도 모든 backend 변경에서 단위·전체 PostgreSQL 통합 및 컨테이너 검사를 실행하도록 확장했다.

## CI와 운영 반영

- 변경 커밋: 8c250df21fa4d73fbe2305afcb3ac02eb5e09a94. [GitHub Actions 실행](https://github.com/Jimmy0613/money-map/actions/runs/39348114748)의 test·integrationTest와 이미지 검사 작업이 성공했다. 통합 XML은 일반 PostgreSQL 19개와 실제 컨테이너 재시작 11개, 합계 30개 모두 실패·오류·건너뜀 0이다. 단위·HTTP·대기 용량 69개는 로컬 실행에서도 통과했다.
- 새 이미지 보고서는 Critical 2, Medium 119, Low 13이다. Critical은 기존 Spring Web MVC 6.2.19의 조건부 권고 2건이며 비 Java High·Critical은 0이다. JDBC 정리로 이 권고가 해결됐다고 주장하지 않는다. 실행 Java 패키지 62개를 OSV로 확인했고 새 권고는 없었다. 세부 노출 조건은 [문서 대조](public-demo-final-review-2026-10-19.md)를 따른다.
- Render dep-dbbbciavcj2c739li2c0에서 위 커밋의 Live를 확인했다. 기존 데이터·서명 키를 보존하고 독립 등록을 RELEASED 세대 5로 갱신했다. readiness 200 UP, 동시 CSRF 조회 5건 모두 200, 공개 가입 403 REGISTRATION_CLOSED를 확인했다.
- Vercel 서비스에서 기존 체험 세션으로 장소명 Enter 검색·장소 미리보기·위치 확정 후 별도 저장 버튼이 남는 동작을 확인했다. 촬영 중 거래 저장은 실행하지 않아 샘플 6건을 변경하지 않았다.
- 운영 Spring 시작 로그는 93.597초(process running 104.391초)였다. 한 번의 배포 관측이며 이전 운영 실행과 조건을 맞춘 비교가 아니다. 로컬 16.2% 결과를 운영 절전 복귀 개선율로 확대하지 않는다. PostgreSQL 18.6에 대한 Flyway 지원 범위 경고도 남아 있으며 업그레이드 검증이 후속 과제다.

## 기술 근거

- [Spring Boot 3.5 SQL 구성](https://docs.spring.io/spring-boot/3.5/reference/data/sql.html)
- [JDBC 트랜잭션 자동 구성](https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/autoconfigure/jdbc/DataSourceTransactionManagerAutoConfiguration.html)
- [기동 분석과 지연 초기화의 대가](https://docs.spring.io/spring-boot/3.5/reference/features/spring-application.html)
