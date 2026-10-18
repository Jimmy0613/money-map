# 요구사항 테스트 추적표

## 사용 방법과 현재 상태

이 문서는 테스트 계획이며 실행 결과가 아니다. 모든 사례 상태는 NOT_RUN이다. Windows에서는 문서 검토만 완료하며 코드·Migration·테스트 실행은 SKIPPED - Windows 문서 작업 모드다. 기준은 requirements.md, architecture.md, erd.md, api-spec.md와 CHG-001~004다. 승인된 기대 결과를 테스트 통과를 위해 완화하지 않는다.

각 FR의 -01은 정상 흐름, -02는 오류·경계 흐름이다. 여러 변형은 구현 시 a/b/c 하위 사례로 나누고 상위 ID를 유지한다. 증거는 실제 실행 후 명령·환경·시각·로그 위치를 test-report.md에 연결한다. 아직 보고서나 성공 로그를 생성하지 않는다. 검증 계층은 Unit=순수 계산, DB=Testcontainers PostgreSQL, API=MockMvc/통합, Component=Vitest/RTL/MSW, E2E=Playwright, 운영=격리된 백업 복원 검증이다.

## F0 공통 합성 fixture

Clock은 2026-09-15T01:00:00Z, 사용자 A/B 시간대 Asia/Seoul, 사용자별 분류와 데이터는 분리한다. 각 사례는 독립 초기 상태에서 시작하며 변경 사례가 다른 사례의 합계를 바꾸지 않게 한다. 지역 사전에는 CODE:11680 및 서울특별시/강남구 별칭을 둔다. 이는 테스트용 최소 사전이며 실제 자료 검증을 대신하지 않는다.

| 거래 | 사용자 | 거래일 | 유형 | 금액 KRW | 분류 | 위치 |
|---|---|---|---|---|---|---|
| T1 | A | 2026-09-15 | EXPENSE | 12000 | 식비 | 검색 P1, 코드 11680, 37.500000/127.000000 |
| T2 | A | 2026-09-14 | EXPENSE | 4000 | 식비 | 같은 P1, 코드 null, 서울특별시/강남구 이름·주소 있음 |
| T3 | A | 2026-09-13 | EXPENSE | 3000 | 교통 | MAP, 주소·지역 없음, 37.510000/127.010000 |
| T4 | A | 2026-09-12 | EXPENSE | 10000 | 쇼핑 | 위치 없음 |
| T5 | A | 2026-09-11 | INCOME | 50000 | 기타 수입 | 검색 P1 |
| T6 | A | 2026-09-10 | INCOME | 100000 | 급여 | 위치 없음 |
| T7 | A | 2026-08-20 | EXPENSE | 8000 | 식비 | 검색 P1 |
| T8 | B | 2026-09-15 | EXPENSE | 99999 | B의 식비 | B 소유의 별도 Place |

T2와 T5의 좌표는 T1과 같다. 식비·교통·쇼핑은 EXPENSE, 급여·기타 수입은 INCOME이며 실제 name은 요구사항의 기타를 사용한다. 외부 장소 ID가 같아도 B의 Place ID는 A와 다르다. 전체 지도 fixture 범위는 south=37,north=38,west=126,east=128이다. 9월 총 거래는 A 6건, 지출 4건, 위치 지출 3건이다.

## 기능 요구사항

| 사례 ID | 대상 | 조건·행동 및 기대 결과 | 계층 | 상태 |
|---|---|---|---|---|
| TC-FR-001-01 | FR-001 가입과 기본 분류 | 정상 가입 후 사용자와 수입 5개·지출 11개 기본 분류 생성 | API/DB | NOT_RUN |
| TC-FR-001-02 | FR-001 가입과 기본 분류 | 공백·대소문자만 다른 이메일 재가입은 409, 두 번째 사용자 생성 없음 | API/DB | NOT_RUN |
| TC-FR-002-01 | FR-002 로그인과 토큰 갱신 | 정상 로그인 후 쿠키 발급 및 본인 조회 200 | API/DB | NOT_RUN |
| TC-FR-002-02 | FR-002 로그인과 토큰 갱신 | 틀린 비밀번호와 없는 이메일은 같은 401; refresh 재사용은 가족 폐기 | API/DB | NOT_RUN |
| TC-FR-003-01 | FR-003 로그아웃과 실패 복구 | 로그아웃 204 후 기존 access 요청 401 | API/E2E | NOT_RUN |
| TC-FR-003-02 | FR-003 로그아웃과 실패 복구 | 응답 유실 후 새로고침·다른 탭에서도 개인 화면 및 refresh 차단, 재시도 204 후 해제 | API/E2E | NOT_RUN |
| TC-FR-004-01 | FR-004 소유권 격리 | A 인증으로 A 거래·장소·분류만 조회 | API/DB | NOT_RUN |
| TC-FR-004-02 | FR-004 소유권 격리 | B의 ID로 조회·수정·삭제 404; DB 복합 FK도 타인 분류/장소 연결 거부 | API/DB | NOT_RUN |
| TC-FR-005-01 | FR-005 거래 생성과 멱등성 | F0 T1 입력 저장 후 합계에 한 번 반영 | API/DB | NOT_RUN |
| TC-FR-005-02 | FR-005 거래 생성과 멱등성 | 같은 키/본문 동시 2회는 거래 1건, 다른 본문은 409, 삭제 후 키 재사용도 부활 없음 | API/DB | NOT_RUN |
| TC-FR-006-01 | FR-006 목록 필터와 페이지 | F0 현재 월 목록은 6건, size=2 페이지 합집합에 중복 없음 | API/DB | NOT_RUN |
| TC-FR-006-02 | FR-006 목록 필터와 페이지 | 페이지 사이 거래 변경 시 다음 커서 409 CURSOR_STALE; 다른 사용자 커서는 400 | API/DB | NOT_RUN |
| TC-FR-007-01 | FR-007 상세 표시 | T1 날짜·금액·위치·감사 시각이 저장값과 일치 | API/E2E | NOT_RUN |
| TC-FR-007-02 | FR-007 상세 표시 | 없는 ID와 B 거래 ID는 같은 404 | API/E2E | NOT_RUN |
| TC-FR-008-01 | FR-008 거래 수정 | T1 12000원을 15000원으로 수정하면 9월 지출 32000원 | API/E2E | NOT_RUN |
| TC-FR-008-02 | FR-008 거래 수정 | 같은 version 동시 수정은 한 건 성공, 다른 건 412; 응답 유실은 재조회·초안 보존 | API/E2E | NOT_RUN |
| TC-FR-009-01 | FR-009 거래 삭제 | T1 삭제하면 9월 지출 17000원, 해당 위치 제거 | API/DB/E2E | NOT_RUN |
| TC-FR-009-02 | FR-009 거래 삭제 | 반복 DELETE 404에 추가 부작용 없음; 응답 유실 후 GET 404는 현재 없음으로 안내 | API/DB/E2E | NOT_RUN |
| TC-FR-010-01 | FR-010 카테고리 선택 | EXPENSE 활성 목록은 지출 분류만 표시 | API/DB | NOT_RUN |
| TC-FR-010-02 | FR-010 카테고리 선택 | 수입 분류로 지출 생성 400 VALIDATION_FAILED, DB FK도 거부; 보관 신규 지정 409 | API/DB | NOT_RUN |
| TC-FR-011-01 | FR-011 개인 분류 관리 | 생성·이름 변경 후 보관, 기존 거래 표시 유지 | API/E2E | NOT_RUN |
| TC-FR-011-02 | FR-011 개인 분류 관리 | 중복 이름 409, 기본 분류 수정 409, 보관 분류 신규 선택 불가; 기존 거래에서 그대로 유지 가능 | API/E2E | NOT_RUN |
| TC-FR-012-01 | FR-012 위치 없음 | T4는 위치 행 없이 저장, 일반 합계 포함·지도 제외 | DB/E2E | NOT_RUN |
| TC-FR-012-02 | FR-012 위치 없음 | Kakao 키 없음과 위치 권한 거부 상태에서도 T4 저장 성공 | DB/E2E | NOT_RUN |
| TC-FR-013-01 | FR-013 현재 위치 | accuracyM=1000이면 경고 없이 확인 후 저장 | Unit/API/E2E | NOT_RUN |
| TC-FR-013-02 | FR-013 현재 위치 | 1000.001은 확인 없이 400; 권한 거부·10초 timeout 시 폼 보존 및 위치 없음 선택 | Unit/API/E2E | NOT_RUN |
| TC-FR-014-01 | FR-014 장소 검색 | 2자 이상 검색어를 300ms 지연 후 검색하고 선택한 스냅샷 저장 | Component/E2E | NOT_RUN |
| TC-FR-014-02 | FR-014 장소 검색 | 역순 응답은 최신 요청만 반영, 검색 없음·SDK 오류 때 재시도와 위치 없음 제공 | Component/E2E | NOT_RUN |
| TC-FR-015-01 | FR-015 지도 직접 선택 | 확정 전 반올림한 6자리 좌표와 저장 좌표 일치 | Component/API | NOT_RUN |
| TC-FR-015-02 | FR-015 지도 직접 선택 | 주소 변환 실패도 유효 좌표 저장 가능, 지도 실패 시 위치 없이 계속 가능 | Component/API | NOT_RUN |
| TC-FR-016-01 | FR-016 위치 변경과 제거 | 위치 제거 PUT 후 장소명·주소·좌표가 응답과 위치 테이블에서 제거 | API/E2E | NOT_RUN |
| TC-FR-016-02 | FR-016 위치 변경과 제거 | 미리보기 실패해도 제거 가능; 변경 요청 실패하면 폼 위치 초안 유지 | API/E2E | NOT_RUN |
| TC-FR-017-01 | FR-017 장소 동일성 | F0 T1/T2는 같은 P1로 합계 16000원 | API/DB | NOT_RUN |
| TC-FR-017-02 | FR-017 장소 동일성 | 같은 이름의 다른 외부 ID는 별개; 수동 동일 좌표 시각 묶음은 Place 생성/병합 없음 | API/DB | NOT_RUN |
| TC-FR-018-01 | FR-018 지도 영역 조회 | F0 전체 위치 영역 지출 19000원, 3건; 기간 전체 지출 29000원 | API/DB | NOT_RUN |
| TC-FR-018-02 | FR-018 지도 영역 조회 | categoryIds 생략 허용; 반전 위도 400; 날짜 변경선 BBox의 양쪽 경도 포함·중간 제외 | API/DB | NOT_RUN |
| TC-FR-019-01 | FR-019 Marker와 Cluster | 500개 묶음에서 level=5 조회 및 클라이언트 Cluster 선택 가능 | API/Component | NOT_RUN |
| TC-FR-019-02 | FR-019 Marker와 Cluster | level 5의 501개, level 7의 301개, level 11의 201개는 빈 items+requiresNarrowing, 합계 보존 | API/Component | NOT_RUN |
| TC-FR-020-01 | FR-020 Marker 상세 | P1 Marker 16000원·2건과 동일 BBox/기간 상세 합계 일치 | API/E2E | NOT_RUN |
| TC-FR-020-02 | FR-020 Marker 상세 | revision 변경은 409 후 지도 재조회; 타인 MarkerKey는 404 | API/E2E | NOT_RUN |
| TC-FR-021-01 | FR-021 필터 상태 유지 | 지도 필터 적용·상세 왕복 시 중심과 필터 복원 | Component/E2E | NOT_RUN |
| TC-FR-021-02 | FR-021 필터 상태 유지 | 지도 이동 응답 역전 무시; 재조회 실패 시 이전 결과와 오류 함께 표시 | Component/E2E | NOT_RUN |
| TC-FR-022-01 | FR-022 기간 요약 | F0 9월 수입 150000원·지출 29000원·잔액 121000원 | Unit/API | NOT_RUN |
| TC-FR-022-02 | FR-022 기간 요약 | 빈 기간은 합계/건수 0, 서버 실패는 503이며 0으로 표시하지 않음 | Unit/API | NOT_RUN |
| TC-FR-023-01 | FR-023 카테고리 통계 | F0 식비 16000원(55%), 교통 3000원(10%), 쇼핑 10000원(34%) | Unit/API | NOT_RUN |
| TC-FR-023-02 | FR-023 카테고리 통계 | P1 위치 필터 시 식비 16000원·100%; 금액 합계 일치, 반올림 비율 합 99 허용 | Unit/API | NOT_RUN |
| TC-FR-024-01 | FR-024 장소 통계 | P1 순위 16000원·2건, 미식별 위치 3000원·1건, 위치 없음 10000원·1건 | API/E2E | NOT_RUN |
| TC-FR-024-02 | FR-024 장소 통계 | P1 상세에 type=EXPENSE를 전달해 같은 장소 수입 50000원 제외 | API/E2E | NOT_RUN |
| TC-FR-025-01 | FR-025 지역 통계 | F0 CODE:11680 16000원, UNKNOWN 3000원, located 합계 19000원 | Unit/DB/API | NOT_RUN |
| TC-FR-025-02 | FR-025 지역 통계 | 코드 없는 T2도 이름으로 같은 코드에 합침; 중복 별칭은 UNKNOWN; 위치 없는 T4 제외 | Unit/DB/API | NOT_RUN |
| TC-FR-026-01 | FR-026 월 비교 | 9월 29000원, 8월 8000원, 차이 21000원·262.5% | Unit/API | NOT_RUN |
| TC-FR-026-02 | FR-026 월 비교 | 이전 합계 0이면 growthPercent=null, NOT_COMPARABLE; 양쪽 키 합집합 사용 | Unit/API | NOT_RUN |
| TC-FR-027-01 | FR-027 설정 변경 | 시간대 변경 후 재로그인해도 설정 유지, 기존 transactionDate 불변 | Unit/API | NOT_RUN |
| TC-FR-027-02 | FR-027 설정 변경 | 잘못된 ZoneId 400, 오래된 If-Match 412; Could 항목으로 MVP 필수 승격 금지 | Unit/API | NOT_RUN |
| TC-FR-028-01 | FR-028 탈퇴와 복원 | ledger 삽입과 A 삭제가 함께 commit, A 로그인 불가·B 데이터 유지 | DB/API/운영 | NOT_RUN |
| TC-FR-028-02 | FR-028 탈퇴와 복원 | ledger 또는 사용자 삭제 실패 시 모두 rollback; 최신 ledger 없이 복원 환경 접근 개방 금지 | DB/API/운영 | NOT_RUN |

## 비기능 요구사항

| 사례 ID | 대상 | 측정 기준 및 기대 결과 | 방법 | 상태 |
|---|---|---|---|---|
| TC-NFR-001-01 | NFR-001 반응형 | 360/390/768/1440px에서 핵심 화면 가로 넘침 없음, 주요 터치 영역 44px 이상 | Playwright 및 실기기 | NOT_RUN |
| TC-NFR-002-01 | NFR-002 접근성 | 키보드로 가입·입력·위치 선택·삭제 수행, Sheet 포커스 복원, 연결 레이블·오류 설명·비색상 상태 확인 | 접근성 검사 및 키보드/스크린리더 | NOT_RUN |
| TC-NFR-003-01 | NFR-003 API 성능 | 사용자당 10000건, 동시 10명, 1분 워밍업 후 5분 측정; 일반 p95<=500ms, 지도/통계<=1000ms | 부하 측정 및 DB 계획 | NOT_RUN |
| TC-NFR-004-01 | NFR-004 화면 성능 | Fast 4G 조건 LCP<=2.5초, SDK 로드 후 지도 조작<=3초, 500개 표시<=2초 | 브라우저 성능 기록 | NOT_RUN |
| TC-NFR-005-01 | NFR-005 인증 보안 | 미인증·타인 객체 접근 거부, 쿠키 보안 속성 확인, refresh 회전과 이전 토큰 거부 | API/보안 | NOT_RUN |
| TC-NFR-006-01 | NFR-006 검증 | 변경 API의 CSRF 누락·잘못된 Origin 거부, 길이/범위 검증, 내부 SQL·스택 미노출 | API/보안 | NOT_RUN |
| TC-NFR-007-01 | NFR-007 로그와 Secret | 합성 민감 표식으로 요청 후 body·query·쿠키·좌표·이메일이 앱 및 프록시 로그에 없음 | 로그 검사 | NOT_RUN |
| TC-NFR-008-01 | NFR-008 위치 최소 수집 | 현재 위치 버튼 전 Geolocation 호출 0회, watchPosition 호출 0회, 위치 없음 거래에 위치 행 없음 | 어댑터 spy/DB | NOT_RUN |
| TC-NFR-009-01 | NFR-009 삭제 | 탈퇴 활성 데이터 제거와 ledger 원자성, 최대 30일 백업 보존, 복원 재삭제 및 최신성 gate | DB/운영 복원 훈련 | NOT_RUN |
| TC-NFR-010-01 | NFR-010 실패 복구 | 중복 POST 1건, PUT/DELETE 응답 유실 뒤 상태 확인, 위치 오류에도 폼 유지 | 통합/E2E 장애 주입 | NOT_RUN |
| TC-NFR-011-01 | NFR-011 호환성 | 출시 기준 최신 2개 주요 버전 대상 명시, iOS Safari/Android Chrome 실기기 HTTPS 위치 입력 성공·거부 | 수동 및 E2E | NOT_RUN |
| TC-NFR-012-01 | NFR-012 유지보수 | Controller 업무 로직/Entity 응답 없음, strict 타입 검사, 테스트 fixture와 운영 seed 분리 | 코드 검토/정적 검사 | NOT_RUN |
| TC-NFR-013-01 | NFR-013 관측성 | requestId·템플릿 경로·상태·durationMs·오류 코드 존재, 민감 필드 부재 | 로그 검사 | NOT_RUN |
| TC-NFR-014-01 | NFR-014 금액 정밀도 | 최대 금액 999999999999 x 10000 = 9999999999990000을 문자열로 동일 반환, 소수·0·상한 초과 거부 | Unit/DB/API | NOT_RUN |
| TC-NFR-015-01 | NFR-015 날짜 경계 | Asia/Seoul 자정 전후 today 변화, 윤년 2/29·월말·연말 범위, UTC 감사 시각 및 거래일 불변 | 고정 Clock Unit/API | NOT_RUN |

## 추가 경계값과 독립 장애 주입

- 금액: 0, 1, 999999999999, 1000000000000, 음수, 소수, 지수 표기. 허용은 범위 안 양의 정수 문자열만이다.
- 날짜: 2024-02-29는 유효, 2025-02-29와 미래 거래일은 400. 기간 양끝 포함과 from>to 거부.
- 좌표: 위도 +/-90, 경도 +/-180 허용; 그 범위 초과 및 소수점 7자리 요청은 400. 6자리 반올림은 저장 전 어댑터가 수행한다.
- 위치 정확도: 999.999/1000/1000.001, 확인 여부 조합. CURRENT 아닌 경우 accuracyM/capturedAt은 null.
- 버전: 누락 428, 오래된 값 412. 사용자 B의 객체는 값 비교 전에 소유권 오류 404.
- 페이지: 0건, 1건, 20건, 21건, 같은 날짜·생성시각의 UUID tie-breaker. 동일 revision 페이지 합집합이 전체와 일치.
- 지역: 코드 유효, 이름만 유일 대응, 별칭 유효 기간 경계, 이름 중복, 미등록 코드, 주소 없음. 불확실한 경우 UNKNOWN.
- 장애 위치: DB commit 전 실패, commit 후 HTTP 응답 유실, 재조회 실패를 구분한다. 전체 네트워크 끊김과 특정 요청 응답 유실을 같은 사례로 취급하지 않는다.
- 삭제 복원 훈련은 새로 만든 격리 DB와 합성 데이터로만 수행한다. 사용자 원본 DB나 파일을 복원 테스트 대상으로 사용하지 않는다.

## 실행 후 기록 규칙

NOT_RUN은 미실행, PASS는 실제 기대값 검증 성공, FAIL은 불일치, BLOCKED는 도구/환경 장애로 실행 불가다. 실패 시 assertion을 제거하거나 Mock 결과를 실제 외부 연동으로 기록하지 않는다. 자동화 성공과 Kakao 실제 연결·모바일 수동·운영 복원 결과는 각각 분리한다. FR-027은 Could이며 미구현이면 선택 범위 미포함으로 기록하고 Must 완료율 분모에 넣지 않는다.
