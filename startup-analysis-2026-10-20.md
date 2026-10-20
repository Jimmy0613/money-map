# Spring 기동 단계 분석

확인일: 2026-10-20. 대상은 운영과 같은 JDBC 전환 빌드 8c250df다. 이번 작업은 분석이며 운영 코드·설정·요금제를 변경하지 않았다. /actuator/startup도 공개하지 않았다.

## 결론

- 로컬 기동 시간은 설정 클래스 분석, Tomcat 생성, 데이터 접근 초기화, Security·MVC 초기화에 분산돼 있다. 추가 스키마 검사는 중앙값 약 42ms다.
- 운영의 Spring 시작 시간은 93.597초이고, Flyway가 보고한 마이그레이션 검증 실행은 0.897초였다. 검증을 끄면 전체 지연이 사라진다는 근거가 없다.
- 운영에서는 초기 웹 컨텍스트 구성 로그까지도 32.296초가 소요됐다. 제한된 실행 자원·클래스 로딩·JIT·원격 연결 등이 후보지만, 기존 로그만으로 CPU 제한이나 네트워크 대기의 비율을 확정하지 않는다.
- 유휴 절전 대기와 JVM 내부 초기화는 구분해야 한다. 추가 최적화는 운영과 같은 자원에서의 프로파일이 필요하다. 이번 로컬 결과를 Render의 속도 개선율로 확대하지 않는다.

## 방법과 한계

BufferingApplicationStartup으로 Spring 단계를, 같은 실행의 JFR profile 설정으로 GC·CPU 샘플·소켓 읽기를 수집했다. 진단 실행기는 scripts/diagnostics에만 있으며 운영 JAR에 포함되지 않는다. 기존 bootJar를 풀어 동일 MoneyMapApplication 구성 클래스를 실행한다.

- Windows x64 / Corretto 21.0.4.7.1 / PostgreSQL 18.0. 예열 1회 후 새로운 JVM으로 3회. 운영 데이터와 분리된, 이미 마이그레이션된 loopback fixture DB를 사용했다.
- 최대 힙 192MB, Metaspace 128MB, Code Cache 48MB, Direct Memory 32MB, 스택 512KB.
- 운영과 같은 풀 최대 3·최소 유휴 0·유휴 제한 30초·연결 제한 15초·keepalive 0, Flyway 연결 분리를 적용했다. 양쪽 연결은 실험에서 같은 로컬 DB를 사용한다.
- local 프로필로 loopback·임의 포트에 바인딩하고 가입을 비활성화했다. 외부 등록 검증은 로컬 예외이며 운영 보호를 해제한 것이 아니다.
- 풀린 classpath·JFR·별도 실행기의 영향이 있다. SpringApplication.run 반환 시점을 측정하므로 이전 java -jar의 HTTP readiness 중앙값 4.185초와 직접 비교하지 않는다.

## Spring 단계별 결과

Spring 실행은 3.751 / 3.571 / 3.642초, 중앙값 3.642초였다. 매번 이벤트 429개를 수집했다. 풀 크기·유휴·연결 제한과 Flyway 연결 분리는 실행 중 실제 설정 값으로도 확인했다.

| 단계 | 중앙값 | 해석 |
|---|---:|---|
| 컨텍스트 refresh | 3,044ms | 여러 하위 단계를 포함하는 상위 구간 |
| 설정 클래스 분석 | 778ms | 자동 구성·설정 클래스 파싱 |
| 웹 서버 생성 | 448ms | Tomcat·웹 초기화 포함 |
| Security 구성 | 398ms | MVC 매핑 등 하위 빈 생성 포함 |
| Flyway 빈 생성 | 342ms | 연결 소스 등 하위 초기화 포함 |
| DataSource 빈 생성 | 182ms | Flyway 빈 생성 시간과 중첩 |
| RecoveryGate 빈 생성 | 144ms | 로컬 클라이언트·키 초기화; 운영 서명 요청 시간 아님 |
| Flyway initializer | 137ms | 이력 검증·최신 상태 확인 |
| 추가 스키마 검사 빈 | 42ms | 컬럼·타입 검사와 필요한 연결 획득 포함 |

포함 시간은 중첩되므로 합산하지 않는다. 공개 JSON의 exclusive_top_steps는 직접 자식 이벤트의 구간 합집합을 뺀 값이며 특정 메서드의 순수 CPU 시간은 아니다.

## JFR 결과

| 지표 | 1회 | 2회 | 3회 |
|---|---:|---:|---:|
| GC 이벤트 | 24 | 25 | 25 |
| GC 정지 합계 | 56.641ms | 53.983ms | 54.004ms |
| Full GC | 0 | 0 | 0 |
| GC 시점 최대 관측 힙 | 89.266MiB | 89.796MiB | 91.431MiB |
| 실행 스택 샘플 | 19 | 21 | 20 |
| 기록된 소켓 읽기 합계 | 91.752ms | 90.391ms | 84.751ms |

GC 정지는 이 표본에서 전체 기동의 약 1.5%다. 관측 힙은 GC 전후 표본이며 정확한 최고점·전체 프로세스 메모리가 아니다. CPU 샘플이 적어 메서드별 비율을 발표할 근거가 부족하다. 소켓 이벤트도 임계값과 스레드 중첩이 있으므로 전체 DB 통신 시간이나 임계 경로 합계가 아니다.

## 운영 로그와 대조

Render 배포 dep-dbbbciavcj2c739li2c0의 같은 실행을 브라우저에서 확인했다. UTC 시각이며 운영 JFR는 수집하지 않았다.

| 관측 로그 | 시각·값 |
|---|---|
| Starting MoneyMapApplication | 00:32:35.239 |
| Root WebApplicationContext 초기화 완료 | 00:33:08.538 / 32,296ms |
| Flyway Database 식별 | 00:33:27.341 |
| 마이그레이션 6개 검증 성공 | 00:33:29.140 / 실행 0.897초 |
| 스키마 최신, 새 마이그레이션 없음 | 00:33:30.241 |
| Hikari 시작 / 완료 | 00:33:38.038 / 00:33:38.647 |
| Actuator health 1개 노출 | 00:33:47.139 |
| Tomcat 시작 완료 | 00:33:56.943 |
| Spring 시작 완료 | 00:33:57.437 / 93.597초, 프로세스 경과 104.391초 |

웹 컨텍스트 로그~Flyway Database 로그 사이 18.803초를 전부 Neon 연결 대기라고 부를 수 없다. 중간 클래스 로딩·빈 구성·TLS·통신의 상세 이벤트가 없다. Hikari 시작~완료는 0.609초지만 minimum-idle=0이어서 완료 로그만으로 실제 DB 연결 준비를 판단하지 않는다. Flyway 최신 확인 이후 Tomcat 시작까지도 26.702초다.

Spring 완료 이전 환경 준비·JVM 시작 비용과 플랫폼 인스턴스 배정·이미지 준비·절전 복귀는 별개다. Starting 로그~Started 로그의 차이와 Spring이 보고한 93.597초도 같지 않다. 배포 직후 등록 갱신 전 발생한 일부 503은 [갱신 이후 정상 확인](startup-review-2026-10-20.md)과 구분하며 Spring 기동 시간에 합산하지 않는다.

## 적용 판단과 다음 순서

1. 약 42ms의 로컬 검사를 없애기보다 잘못된 스키마에서 기동을 거부하는 동작을 유지한다. Security·MVC·Actuator도 실제 기능을 담당한다.
2. lazy initialization, 검사 제거, 메모리 증설, 주기적 깨우기는 이번에 적용하지 않았다. 초기화 실패를 첫 요청으로 미루거나 측정 없이 정책을 바꾸지 않는다.
3. CPU 제한·JIT·클래스 로딩의 기여를 확정하려면 운영과 같은 자원에서 비공개 JFR를 수집한다. JVM 초기 컴파일 옵션·AOT/CDS는 정상 실행 성능·빌드 복잡도의 교환을 별도로 비교한 뒤 판단한다.
4. 유휴 절전 제거가 목적이면 유료 인스턴스 전환으로 해결하는 범위와 남는 배포·재시작 비용을 구분한다. 이번 분석으로 유료 전환이나 완전한 첫 접속 개선을 실행·보장하지 않았다.

## 재현과 공개 범위

운영과 분리된 loopback PostgreSQL에 demo_test 역할·fixture_ 접두사 disposable DB를 준비하고 현재 bootJar를 빌드한다. JDK 21의 javac·jfr가 필요하다. 실행기는 DB나 역할을 생성하지 않는다.

```text
python scripts/profile-startup.py --jar backend/build/libs/money-map-0.1.0.jar --jdk JDK경로 --jdbc jdbc:postgresql://127.0.0.1:55439/fixture_startup_20261020
python scripts/summarize-startup-profile.py --input .tooling/startup-profile --jdk JDK경로 --output evidence/startup-profile-2026-10-20.json
```

별도 라이브러리 설치는 필요 없다. 이번 임시 PostgreSQL 서버는 분석 종료 후 중지했다. 원본 JFR는 환경 변수·시스템 속성·프로세스 정보를 담을 수 있어 로그와 함께 Git 제외 .tooling에만 보관한다. 공개 [정제 결과](evidence/startup-profile-2026-10-20.json)는 단계명·코드 식별자·시간·계수만 추출하며 DB 주소·사용자 데이터·키를 포함하지 않는다.

공식 근거: [Spring Application Startup](https://docs.spring.io/spring-boot/3.5/reference/features/spring-application.html), [Actuator startup 응답 구조](https://docs.spring.io/spring-boot/3.5/api/rest/actuator/startup.html).
