# 요구사항 테스트 추적표

> 최신 종합 점검(2026-09-25): **개발·검증 진행 중, 배포 준비 완료 아님.** 추가 자율 검증으로 Frontend43/Backend38·DB13 및 변경 API 부하를 확인했습니다. 상위 추적표는 실제 기대값을 확인한7사례를 갱신해 **33PASS/38NOT_RUN**입니다. 운영 복원 최신성·수정본 휴대폰 검증 등 남은 조건은 [self-review.md](self-review.md), 실행 근거는 [test-report.md](test-report.md)를 따릅니다. 아래 날짜별 기록은 당시 이력입니다.

## 사용 방법과 현재 상태

2026-09-19~23 macOS 실행 및 사용자 수동 확인을 기록한다. 원본 71개 상위 ID·기대값·F0는 유지하며 원본은 evidence/original-documents/에 보존했다. 기준은 승인된 요구사항/설계/CHG-001~004다. PASS는 해당 기대를 실제 확인한 사례만 표시한다. 복합 사례의 일부만 검사했으면 상위 결과는 NOT_RUN을 유지하고 Note에 통과한 부분과 미실행 부분을 구분한다. NOT_RUN은 구현 실패를 뜻하지 않는다.

실행 명령·시각·환경·JUnit 상세와 브라우저 로그는 test-report.md에 연결한다. Mock은 실제 Kakao/기기 검증을 대체하지 않는다. FR-027 두 사례는 Could 선택 범위 미포함이다.

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

| Requirement ID | Requirement | Test ID | Test Type | Result | Evidence | Bug ID | Note |
|---|---|---|---|---|---|---|---|
| FR-001 | 가입과 기본 분류 | TC-FR-001-01 | API/DB | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 정상 가입 후 사용자와 수입 5개·지출 11개 기본 분류 생성<br>실행: 실제 DB 기본 지출11/수입5 및 실제 브라우저 가입 PASS. |
| FR-001 | 가입과 기본 분류 | TC-FR-001-02 | API/DB | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 공백·대소문자만 다른 이메일 재가입은 409, 두 번째 사용자 생성 없음<br>실행: 공백/대소문자 정규화 중복409와 사용자1건 유지 PASS. |
| FR-002 | 로그인과 토큰 갱신 | TC-FR-002-01 | API/DB | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 정상 로그인 후 쿠키 발급 및 본인 조회 200<br>실행: 실제 로그인·쿠키 발급·브라우저 내계정 조회 PASS. |
| FR-002 | 로그인과 토큰 갱신 | TC-FR-002-02 | API/DB | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 틀린 비밀번호와 없는 이메일은 같은 401; refresh 재사용은 가족 폐기<br>실행: 잘못된 비밀번호/없는 이메일 인증 오류 동일성과 refresh 재사용 가족 폐기 PASS. |
| FR-003 | 로그아웃과 실패 복구 | TC-FR-003-01 | API/E2E | PASS | [검증 상세](test-report.md); [실제 DB](evidence/backend-integration.log) | — | 기대: 로그아웃 204 후 기존 access 요청 401<br>실행: 실제 Chromium+API+DB 정상 logout 및 commit 후 응답 유실·새로고침·다중 탭·204 재시도 PASS. |
| FR-003 | 로그아웃과 실패 복구 | TC-FR-003-02 | API/E2E | PASS | [검증 상세](test-report.md); [실제 DB](evidence/backend-integration.log) | — | 기대: 응답 유실 후 새로고침·다른 탭에서도 개인 화면 및 refresh 차단, 재시도 204 후 해제<br>실행: 실제 Chromium+API+DB 정상 logout 및 commit 후 응답 유실·새로고침·다중 탭·204 재시도 PASS. |
| FR-004 | 소유권 격리 | TC-FR-004-01 | API/DB | NOT_RUN | [검증 상세](test-report.md) | — | 기대: A 인증으로 A 거래·장소·분류만 조회<br>실행: F0 A/B 격리, 타인 거래404, 타인 분류 복합 FK 거부 PASS. 모든 리소스의 CRUD 공격 조합은 미실행. |
| FR-004 | 소유권 격리 | TC-FR-004-02 | API/DB | PASS | [추가 자율 검증](test-report.md#추가-자율-검증-2026-09-2425) | — | 기대: B의 ID로 조회·수정·삭제 404; DB 복합 FK도 타인 분류/장소 연결 거부<br>실행: categoryTypeAndOwnerAttacks/F0의 타인 거래 GET·PUT·DELETE404와 기존 타인 분류 FK 거부에 더해, anotherOwnersPlaceCannotBeLinkedEvenDirectlyInDatabase에서 타인 Place 연결의 DB FK 거부·조회404·기존 연결 보존 확인. 2026-09-24 통합13PASS. |
| FR-005 | 거래 생성과 멱등성 | TC-FR-005-01 | API/DB | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: F0 T1 입력 저장 후 합계에 한 번 반영<br>실행: F0 T1 저장 및 P1/기간 합계, POST replay 한 번 반영 PASS. |
| FR-005 | 거래 생성과 멱등성 | TC-FR-005-02 | API/DB | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 같은 키/본문 동시 2회는 거래 1건, 다른 본문은 409, 삭제 후 키 재사용도 부활 없음<br>실행: 동시 같은 키/본문1건, 다른 본문 IDEMPOTENCY_CONFLICT409, 삭제 후 OPERATION_DELETED PASS. |
| FR-006 | 목록 필터와 페이지 | TC-FR-006-01 | API/DB | PASS | [검증 상세](test-report.md); [실제 DB](evidence/backend-integration.log) | — | 기대: F0 현재 월 목록은 6건, size=2 페이지 합집합에 중복 없음<br>실행: F0 6건 size=2 중복 없는 순회, 타인 커서400 및 변경 후 CURSOR_STALE PASS. |
| FR-006 | 목록 필터와 페이지 | TC-FR-006-02 | API/DB | PASS | [검증 상세](test-report.md); [실제 DB](evidence/backend-integration.log) | — | 기대: 페이지 사이 거래 변경 시 다음 커서 409 CURSOR_STALE; 다른 사용자 커서는 400<br>실행: F0 6건 size=2 중복 없는 순회, 타인 커서400 및 변경 후 CURSOR_STALE PASS. |
| FR-007 | 상세 표시 | TC-FR-007-01 | API/E2E | NOT_RUN | [검증 상세](test-report.md) | — | 기대: T1 날짜·금액·위치·감사 시각이 저장값과 일치<br>실행: 실제 화면 날짜·금액·위치 없음 표시 확인. 감사 시각 정밀 대조/없는 ID와 타인 ID 전부 비교는 미실행. |
| FR-007 | 상세 표시 | TC-FR-007-02 | API/E2E | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 없는 ID와 B 거래 ID는 같은 404<br>실행: 실제 화면 날짜·금액·위치 없음 표시 확인. 감사 시각 정밀 대조/없는 ID와 타인 ID 전부 비교는 미실행. |
| FR-008 | 거래 수정 | TC-FR-008-01 | API/E2E | PASS | [검증 상세](test-report.md); [실제 DB](evidence/backend-integration.log) | — | 기대: T1 12000원을 15000원으로 수정하면 9월 지출 32000원<br>실행: F0 12000→15000 후 지출32000, 동시200/412, 실제 PUT 응답 유실 후 초안과 재조회 일치 PASS. |
| FR-008 | 거래 수정 | TC-FR-008-02 | API/E2E | PASS | [검증 상세](test-report.md); [실제 DB](evidence/backend-integration.log) | — | 기대: 같은 version 동시 수정은 한 건 성공, 다른 건 412; 응답 유실은 재조회·초안 보존<br>실행: F0 12000→15000 후 지출32000, 동시200/412, 실제 PUT 응답 유실 후 초안과 재조회 일치 PASS. |
| FR-009 | 거래 삭제 | TC-FR-009-01 | API/DB/E2E | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: T1 삭제하면 9월 지출 17000원, 해당 위치 제거<br>실행: F0 T1 수정 후 삭제 시17000, 위치 행 제거 직접 검사 및 실제 DELETE UI PASS. |
| FR-009 | 거래 삭제 | TC-FR-009-02 | API/DB/E2E | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 반복 DELETE 404에 추가 부작용 없음; 응답 유실 후 GET 404는 현재 없음으로 안내<br>실행: 반복 DELETE404 후 합계17000 불변 및 실제 응답 유실 후 GET404 안내 PASS. |
| FR-010 | 카테고리 선택 | TC-FR-010-01 | API/DB | NOT_RUN | [검증 상세](test-report.md) | — | 기대: EXPENSE 활성 목록은 지출 분류만 표시<br>실행: 실제 지출 카테고리 선택 PASS. 유형 FK와 보관 신규 지정 경계 전체 조합은 미실행. |
| FR-010 | 카테고리 선택 | TC-FR-010-02 | API/DB | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 수입 분류로 지출 생성 400 VALIDATION_FAILED, DB FK도 거부; 보관 신규 지정 409<br>실행: 수입분류로 지출400/DB 유형 FK 거부, 보관 분류 신규 지정409 PASS. |
| FR-011 | 개인 분류 관리 | TC-FR-011-01 | API/E2E | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 생성·이름 변경 후 보관, 기존 거래 표시 유지<br>실행: DB 생성·이름 변경·보관·기존 참조 유지 PASS. 중복 이름/기본 분류 변경/화면 보관까지 전체 흐름은 미실행. |
| FR-011 | 개인 분류 관리 | TC-FR-011-02 | API/E2E | NOT_RUN | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 중복 이름 409, 기본 분류 수정 409, 보관 분류 신규 선택 불가; 기존 거래에서 그대로 유지 가능<br>실행: 기본 분류 수정409, 중복 이름409, 보관 신규 선택409, 기존 참조 유지 PASS. 카테고리 관리 UI 회귀는 미실행. |
| FR-012 | 위치 없음 | TC-FR-012-01 | DB/E2E | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: T4는 위치 행 없이 저장, 일반 합계 포함·지도 제외<br>실행: 위치 없는 저장에 DB 위치행0, F0 일반 합계 포함/지도 제외, 실제 UI PASS. |
| FR-012 | 위치 없음 | TC-FR-012-02 | DB/E2E | NOT_RUN | [검증 상세](test-report.md) | — | 기대: Kakao 키 없음과 위치 권한 거부 상태에서도 T4 저장 성공<br>실행: 실제 위치 없는 저장 및 키 없는 UI 대체 PASS. Galaxy S25 Android16 Chrome 권한 거부 후2,000원·위치 없음 저장은 사용자 수동 확인(2026-09-23). Kakao 키 없는 실제 DB 결합 시나리오는 미실행. |
| FR-013 | 현재 위치 | TC-FR-013-01 | Unit/API/E2E | NOT_RUN | [검증 상세](test-report.md) | — | 기대: accuracyM=1000이면 경고 없이 확인 후 저장<br>실행: 1000/1000.001 경계, 정확도 확인 UI, 거부/timeout adapter PASS. Galaxy S25 Chrome153의 약98.4m 위치 저장과 권한 거부 후 위치 없는 저장은 사용자 확인. 정확도 경계/timeout·폼 입력 보존 전체 결합은 미실행. |
| FR-013 | 현재 위치 | TC-FR-013-02 | Unit/API/E2E | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 1000.001은 확인 없이 400; 권한 거부·10초 timeout 시 폼 보존 및 위치 없음 선택<br>실행: 1000/1000.001 경계, 정확도 확인 UI, 거부/timeout adapter PASS. Galaxy S25 Chrome153의 약98.4m 위치 저장과 권한 거부 후 위치 없는 저장은 사용자 확인. 정확도 경계/timeout·폼 입력 보존 전체 결합은 미실행. |
| FR-014 | 장소 검색 | TC-FR-014-01 | Component/E2E | PASS | [실제 Kakao 검증](evidence/kakao-live-e2e.log); [Component](evidence/followup-frontend-tests.log) | — | 기대: 2자 이상 검색어를 300ms 지연 후 검색하고 선택한 스냅샷 저장<br>실행: 2글자/300ms Component PASS와 실제 Kakao 검색·주소변환·선택 스냅샷 DB 저장 PASS(2026-09-21). |
| FR-014 | 장소 검색 | TC-FR-014-02 | Component/E2E | NOT_RUN | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 역순 응답은 최신 요청만 반영, 검색 없음·SDK 오류 때 재시도와 위치 없음 제공<br>실행: Component 역순/빈 검색/오류/위치 없음 PASS. 실제 키는 준비됨. 실제 SDK 장애 주입/재시도 조합은 미실행. |
| FR-015 | 지도 직접 선택 | TC-FR-015-01 | Component/API | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 확정 전 반올림한 6자리 좌표와 저장 좌표 일치<br>실행: 6자리 반올림/좌표 경계와 지도 키 없음 fallback PASS. 주소 실패 좌표 저장 component+API 결합 미실행. |
| FR-015 | 지도 직접 선택 | TC-FR-015-02 | Component/API | NOT_RUN | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 주소 변환 실패도 유효 좌표 저장 가능, 지도 실패 시 위치 없이 계속 가능<br>실행: Component 주소 역변환 실패 후 좌표 확정/키 없음 대체 PASS. 실제 MAP 직접 선택부터 DB까지 결합 미실행. |
| FR-016 | 위치 변경과 제거 | TC-FR-016-01 | API/E2E | NOT_RUN | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 위치 제거 PUT 후 장소명·주소·좌표가 응답과 위치 테이블에서 제거<br>실행: 실제 DB PUT location=null 응답 및 위치행0 확인. 기존 위치가 있는 브라우저 화면 제거 경로는 미실행. |
| FR-016 | 위치 변경과 제거 | TC-FR-016-02 | API/E2E | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 미리보기 실패해도 제거 가능; 변경 요청 실패하면 폼 위치 초안 유지<br>실행: 실제 F0 PUT location=null 응답 확인. DB 위치 잔존 직접 검사 및 미리보기 실패 제거 UI는 미실행. |
| FR-017 | 장소 동일성 | TC-FR-017-01 | API/DB | PASS | [검증 상세](test-report.md); [실제 DB](evidence/backend-integration.log) | — | 기대: F0 T1/T2는 같은 P1로 합계 16000원<br>실행: F0 P1 동일성16000 PASS. 같은 이름 다른 외부 ID/수동 좌표 묶음의 전체 조합 미실행. |
| FR-017 | 장소 동일성 | TC-FR-017-02 | API/DB | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 같은 이름의 다른 외부 ID는 별개; 수동 동일 좌표 시각 묶음은 Place 생성/병합 없음<br>실행: F0 P1 동일성16000 PASS. 같은 이름 다른 외부 ID/수동 좌표 묶음의 전체 조합 미실행. |
| FR-018 | 지도 영역 조회 | TC-FR-018-01 | API/DB | PASS | [추가 자율 검증](test-report.md#추가-자율-검증-2026-09-2425) | — | 기대: F0 전체 위치 영역 지출 19000원, 3건; 기간 전체 지출 29000원<br>실행: F0ActualDatabaseQueriesAndCursor에서 viewportExpenseTotal=19000, viewportCount=3, periodExpenseTotal=29000을 직접 대조. 2026-09-24 통합13PASS. |
| FR-018 | 지도 영역 조회 | TC-FR-018-02 | API/DB | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: categoryIds 생략 허용; 반전 위도 400; 날짜 변경선 BBox의 양쪽 경도 포함·중간 제외<br>실행: categoryIds 생략·반전 위도400·날짜 변경선 양쪽 경도 포함/중간 제외 PASS. |
| FR-019 | Marker와 Cluster | TC-FR-019-01 | API/Component | NOT_RUN | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 500개 묶음에서 level=5 조회 및 클라이언트 Cluster 선택 가능<br>실행: DB level5 정확히500개 반환 PASS. 실제 클라이언트 Cluster 선택 미실행. |
| FR-019 | Marker와 Cluster | TC-FR-019-02 | API/Component | NOT_RUN | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: level 5의 501개, level 7의 301개, level 11의 201개는 빈 items+requiresNarrowing, 합계 보존<br>실행: DB level5/7/11 정확히500/300/200 및 초과501/301/201의 빈 items·합계·안내 플래그 PASS. 실제 Cluster Component 조합 미실행. |
| FR-020 | Marker 상세 | TC-FR-020-01 | API/E2E | NOT_RUN | [검증 상세](test-report.md) | — | 기대: P1 Marker 16000원·2건과 동일 BBox/기간 상세 합계 일치<br>실행: P1 marker/details 합계16000 PASS. 상세 stale·타인 markerKey·브라우저 sheet 전체 흐름 미실행. |
| FR-020 | Marker 상세 | TC-FR-020-02 | API/E2E | NOT_RUN | [검증 상세](test-report.md) | — | 기대: revision 변경은 409 후 지도 재조회; 타인 MarkerKey는 404<br>실행: P1 marker/details 합계16000 PASS. 상세 stale·타인 markerKey·브라우저 sheet 전체 흐름 미실행. |
| FR-021 | 필터 상태 유지 | TC-FR-021-01 | Component/E2E | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 지도 필터 적용·상세 왕복 시 중심과 필터 복원<br>실행: 필터/중심 메모리 상태와 auth 초기화 구현. 왕복/응답 역전/오류 이전 결과 통합 테스트 미실행. 지도 준비/재조회 위험은 self-review.md R-03/04 참조. |
| FR-021 | 필터 상태 유지 | TC-FR-021-02 | Component/E2E | PASS | [추가 자율 검증](test-report.md#추가-자율-검증-2026-09-2425) | — | 기대: 지도 이동 응답 역전 무시; 재조회 실패 시 이전 결과와 오류 함께 표시<br>실행: map-state.test.tsx에서 늦은 이전 영역 응답 무시와 재조회503 시 이전 목록·안내 유지 확인. 실패 재현 후 수정하여 최종 통과. 실제 기기 검증이 아닌 Component 장애 주입이다. |
| FR-022 | 기간 요약 | TC-FR-022-01 | Unit/API | PASS | [검증 상세](test-report.md); [실제 DB](evidence/backend-integration.log) | — | 기대: F0 9월 수입 150000원·지출 29000원·잔액 121000원<br>실행: F0 실제 DB 150000/29000/121000 및 Mock503 처리 PASS. 빈 기간 전체 계약 미실행. |
| FR-022 | 기간 요약 | TC-FR-022-02 | Unit/API | NOT_RUN | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 빈 기간은 합계/건수 0, 서버 실패는 503이며 0으로 표시하지 않음<br>실행: 실제 빈 기간0, MSW503를 성공0으로 변환하지 않음 PASS. DB 중단을 통한 실제 HTTP503 장애 주입은 미실행. |
| FR-023 | 카테고리 통계 | TC-FR-023-01 | Unit/API | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: F0 식비 16000원(55%), 교통 3000원(10%), 쇼핑 10000원(34%)<br>실행: F0 금액16000/3000/10000 및 비율55/10/34의 실제 DB assertion PASS. |
| FR-023 | 카테고리 통계 | TC-FR-023-02 | Unit/API | PASS | [추가 자율 검증](test-report.md#추가-자율-검증-2026-09-2425) | — | 기대: P1 위치 필터 시 식비 16000원·100%; 금액 합계 일치, 반올림 비율 합 99 허용<br>실행: F0 P1 필터 expenseTotal/분류금액=16000, 단일 분류100% 확인. 전체 비율55/34/10 및 합계29000 확인. 2026-09-24 통합13PASS. |
| FR-024 | 장소 통계 | TC-FR-024-01 | API/E2E | NOT_RUN | [검증 상세](test-report.md) | — | 기대: P1 순위 16000원·2건, 미식별 위치 3000원·1건, 위치 없음 10000원·1건<br>실행: F0 장소 합계·미식별3000·위치없음10000 PASS. 브라우저 상세의 EXPENSE 쿼리 검사는 미실행. |
| FR-024 | 장소 통계 | TC-FR-024-02 | API/E2E | NOT_RUN | [검증 상세](test-report.md) | — | 기대: P1 상세에 type=EXPENSE를 전달해 같은 장소 수입 50000원 제외<br>실행: F0 장소 합계·미식별3000·위치없음10000 PASS. 브라우저 상세의 EXPENSE 쿼리 검사는 미실행. |
| FR-025 | 지역 통계 | TC-FR-025-01 | Unit/DB/API | PASS | [추가 자율 검증](test-report.md#추가-자율-검증-2026-09-2425) | — | 기대: F0 CODE:11680 16000원, UNKNOWN 3000원, located 합계 19000원<br>실행: F0 CODE:11680=16000, UNKNOWN=3000, locatedExpenseTotal=19000을 키와 금액으로 직접 대조. 과거 사전 유효 기간 전체 검증과는 별도인 본 사례를 통과. 2026-09-24 통합13PASS. |
| FR-025 | 지역 통계 | TC-FR-025-02 | Unit/DB/API | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 코드 없는 T2도 이름으로 같은 코드에 합침; 중복 별칭은 UNKNOWN; 위치 없는 T4 제외<br>실행: F0 이름별칭→CODE:11680과 UNKNOWN 합계 PASS. 중복 별칭 경계 및 공식 과거 유효 기간 미확보. |
| FR-026 | 월 비교 | TC-FR-026-01 | Unit/API | PASS | [추가 자율 검증](test-report.md#추가-자율-검증-2026-09-2425) | — | 기대: 9월 29000원, 8월 8000원, 차이 21000원·262.5%<br>실행: F0 current=29000/previous=8000/difference=21000/growthPercent=262.5를 직접 대조. 2026-09-24 통합13PASS. |
| FR-026 | 월 비교 | TC-FR-026-02 | Unit/API | PASS | [추가 자율 검증](test-report.md#추가-자율-검증-2026-09-2425) | — | 기대: 이전 합계 0이면 growthPercent=null, NOT_COMPARABLE; 양쪽 키 합집합 사용<br>실행: monthlyUnionIncludesPreviousOnlyAndCurrentOnlyGroups에서 양쪽 분류·지역 키 합집합, 이전0의 null/NOT_COMPARABLE, 이전에만 있는 지역의 current0/previous8000/difference-8000 확인. 2026-09-24 통합13PASS. |
| FR-027 | 설정 변경 | TC-FR-027-01 | Unit/API | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 시간대 변경 후 재로그인해도 설정 유지, 기존 transactionDate 불변<br>실행: Could: 이번 MVP 선택 범위 미포함. GET 내계정/탈퇴는 구현했으며 설정 편집은 미구현. |
| FR-027 | 설정 변경 | TC-FR-027-02 | Unit/API | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 잘못된 ZoneId 400, 오래된 If-Match 412; Could 항목으로 MVP 필수 승격 금지<br>실행: Could: 이번 MVP 선택 범위 미포함. GET 내계정/탈퇴는 구현했으며 설정 편집은 미구현. |
| FR-028 | 탈퇴와 복원 | TC-FR-028-01 | DB/API/운영 | NOT_RUN | [검증 상세](test-report.md) | — | 기대: ledger 삽입과 A 삭제가 함께 commit, A 로그인 불가·B 데이터 유지<br>실행: DB ledger commit/cascade 및 ledger 실패 rollback PASS. 운영 최신 ledger 복원 gate 훈련은 미실행. |
| FR-028 | 탈퇴와 복원 | TC-FR-028-02 | DB/API/운영 | NOT_RUN | [검증 상세](test-report.md) | — | 기대: ledger 또는 사용자 삭제 실패 시 모두 rollback; 최신 ledger 없이 복원 환경 접근 개방 금지<br>실행: DB ledger commit/cascade 및 ledger 실패 rollback PASS. 운영 최신 ledger 복원 gate 훈련은 미실행. |

## 비기능 요구사항

| Requirement ID | Requirement | Test ID | Test Type | Result | Evidence | Bug ID | Note |
|---|---|---|---|---|---|---|---|
| NFR-001 | 반응형 | TC-NFR-001-01 | Playwright 및 실기기 | NOT_RUN | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 360/390/768/1440px에서 핵심 화면 가로 넘침 없음, 주요 터치 영역 44px 이상<br>실행: 등록 및 상세360/390/768/1440px 넘침 없음 PASS. 전체 핵심 화면·44px·실기기 전수 미실행. |
| NFR-002 | 접근성 | TC-NFR-002-01 | 접근성 검사 및 키보드/스크린리더 | NOT_RUN | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 키보드로 가입·입력·위치 선택·삭제 수행, Sheet 포커스 복원, 연결 레이블·오류 설명·비색상 상태 확인<br>실행: Chromium Enter로 위치 dialog 열기/Escape/포커스 복원 PASS. 오류 aria-describedby 연결 PASS. 전체 키보드 시나리오·스크린리더 미실행. |
| NFR-003 | API 성능 | TC-NFR-003-01 | 부하 측정 및 DB 계획 | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 사용자당 10000건, 동시 10명, 1분 워밍업 후 5분 측정; 일반 p95<=500ms, 지도/통계<=1000ms<br>실행: 기존10명×10000건·1분 준비/5분 HTTP 측정 기준 PASS. 추가10000행 목록 EXPLAIN ANALYZE14.411ms, transaction_filter Bitmap Index Scan 및 반환21행 확인. 기존 조회7개 경로 외에 2026-09-24 등록/수정/삭제/분류 PATCH 및 상세 GET을 같은 규모·시간으로 추가 측정: p95 각각10/10/7/8/3ms, 오류0(evidence/autonomous-mutation-metrics.txt). 인증 등 나머지 경로와 모든 SQL 계획 전수는 미검증. NFR-003 원문 전체 완료는 아님. |
| NFR-004 | 화면 성능 | TC-NFR-004-01 | 브라우저 성능 기록 | NOT_RUN | [검증 상세](test-report.md) | — | 기대: Fast 4G 조건 LCP<=2.5초, SDK 로드 후 지도 조작<=3초, 500개 표시<=2초<br>실행: Fast4G LCP·실제 SDK500개 표시 성능 미실행. 실제 지도 키는 준비됨. 조건을 고정한 성능 계측 필요. |
| NFR-005 | 인증 보안 | TC-NFR-005-01 | API/보안 | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 미인증·타인 객체 접근 거부, 쿠키 보안 속성 확인, refresh 회전과 이전 토큰 거부<br>실행: 인증401·소유권404·Secure/HttpOnly·refresh 재사용 폐기 PASS. 전체 보안 공격 조합은 미실행. |
| NFR-006 | 검증 | TC-NFR-006-01 | API/보안 | NOT_RUN | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 변경 API의 CSRF 누락·잘못된 Origin 거부, 길이/범위 검증, 내부 SQL·스택 미노출<br>실행: CSRF/Origin/JSON/본문/금액·좌표 및 안전한 서버 필드 오류 PASS. 모든 길이·DB 오류 유도 조합은 미실행. |
| NFR-007 | 로그와 Secret | TC-NFR-007-01 | 로그 검사 | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 합성 민감 표식으로 요청 후 body·query·쿠키·좌표·이메일이 앱 및 프록시 로그에 없음<br>실행: 로컬 Secret 일치 스캔·실제 합성 계정/쿠키 로그 부재 PASS. 별도 합성 query/좌표 표식과 운영 프록시 검증 미실행. |
| NFR-008 | 위치 최소 수집 | TC-NFR-008-01 | 어댑터 spy/DB | PASS | [추가 검증](test-report.md#추가-보완-검증-2026-09-19); [DB 로그](evidence/followup-backend.log) | — | 기대: 현재 위치 버튼 전 Geolocation 호출 0회, watchPosition 호출 0회, 위치 없음 거래에 위치 행 없음<br>실행: 렌더/창 열기 전 Geolocation0회, 명시적 버튼 후1회, watchPosition0회 spy 및 DB 위치행0 PASS. |
| NFR-009 | 삭제 | TC-NFR-009-01 | DB/운영 복원 훈련 | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 탈퇴 활성 데이터 제거와 ledger 원자성, 최대 30일 백업 보존, 복원 재삭제 및 최신성 gate<br>실행: 실제 DB 탈퇴 cascade/원자성 PASS. 운영 백업30일·최신 ledger 재삭제/gate는 미실행. |
| NFR-010 | 실패 복구 | TC-NFR-010-01 | 통합/E2E 장애 주입 | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 중복 POST 1건, PUT/DELETE 응답 유실 뒤 상태 확인, 위치 오류에도 폼 유지<br>실행: 실제 commit 후 POST/PUT/DELETE 응답 유실과 복구 PASS. 위치 오류와 거래 폼의 통합 장애 주입 미실행. |
| NFR-011 | 호환성 | TC-NFR-011-01 | 수동 및 E2E | NOT_RUN | [검증 상세](test-report.md) | — | 기대: 출시 기준 최신 2개 주요 버전 대상 명시, iOS Safari/Android Chrome 실기기 HTTPS 위치 입력 성공·거부<br>실행: Chromium 모바일 viewport 검증. 격리 HTTPS와 Chromium 정상/유실2PASS 확보. Galaxy S25 Android16 Chrome 위치 허용·주소 저장·Marker 상세 및 거부 후 위치 없는 저장은 사용자 수동 확인. Chrome153.0.8010.52 사용자 확인. 최신2개 주요 버전 전수는 미검증. iPhone/iPad 없음(2026-09-23 사용자 확인)으로 iOS Safari 실기기는 미실행. |
| NFR-012 | 유지보수 | TC-NFR-012-01 | 코드 검토/정적 검사 | PASS | [검증 상세](test-report.md); [로그 검사](evidence/log-audit.json) | — | 기대: Controller 업무 로직/Entity 응답 없음, strict 타입 검사, 테스트 fixture와 운영 seed 분리<br>실행: TypeScript strict/lint/build PASS, Controller는 서비스 위임, Entity 직접 반환 없음. 운영 seed/F0 분리. |
| NFR-013 | 관측성 | TC-NFR-013-01 | 로그 검사 | PASS | [검증 상세](test-report.md); [로그 검사](evidence/log-audit.json) | — | 기대: requestId·템플릿 경로·상태·durationMs·오류 코드 존재, 민감 필드 부재<br>실행: 실제 로컬 요청 로그 requestId/템플릿 path/status/durationMs/code 및 알려진 민감값 부재 검사 PASS. NFR-013 원문의 외부 SDK 오류 코드 집계는 미구현(R-07). |
| NFR-014 | 금액 정밀도 | TC-NFR-014-01 | Unit/DB/API | PASS | [검증 상세](test-report.md); [로그 검사](evidence/log-audit.json) | BUG-001 | 기대: 최대 금액 999999999999 x 10000 = 9999999999990000을 문자열로 동일 반환, 소수·0·상한 초과 거부<br>실행: DB 999999999999 ×10000 = 문자열9999999999990000, Unit 잘못된 금액 거부, UI BigInt 표시 PASS. |
| NFR-015 | 날짜 경계 | TC-NFR-015-01 | 고정 Clock Unit/API | NOT_RUN | [검증 상세](test-report.md) | — | 기대: Asia/Seoul 자정 전후 today 변화, 윤년 2/29·월말·연말 범위, UTC 감사 시각 및 거래일 불변<br>실행: 윤년/월말/연말/서울 자정 계산 PASS. 실제 API 고정 Clock과 UTC 감사 시각 불변 전체 조합 미실행. |

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

2026-09-21 갱신: 실제 Kakao 기본 연결은 PASS. 과거 행의 키 미설정 표기는 당시 이력이며, 아직 수행하지 않은 실제 SDK 경계/Cluster/실기기 사례는 계속 NOT_RUN이다.

2026-09-21 HTTPS: FR-003 정상/유실·다중 탭 복구2PASS. evidence/mobile-https-verified-e2e.log. 실기기는 대기, 상위26PASS/45NOT_RUN 유지.


2026-09-21 HTTPS Kakao 도메인 등록 후 검증: 사용자 등록 완료 보고 뒤 실제 HTTPS Chromium에서 지도/장소 검색/주소·지역 코드/위치 저장/Marker 상세/합성 계정 탈퇴 E2E1개 PASS(11.7초). evidence/mobile-https-kakao-e2e.log. 실제 Galaxy S25의 GPS·위치 거부·터치 검증은 아직 대기이며 상위 추적표 상태는 유지한다.

## 자체 점검 후 하위 회귀 매핑 (2026-09-24)

상위71개 사례 ID/기대값/Result는 유지한다. 이번 수정과 검증은 다음 요구사항의 하위 증거를 보완한다. 전체 조건 충족으로 자동 승격하지 않는다.

| 하위 검증 | 대응 요구사항/점검 | 결과·근거 |
|---|---|---|
| 분류 연속 수정/보관,412 초안 보존, 응답 유실 대조 | 분류 PATCH 계약, NFR-010 / R-02 | review-regressions9개 중3개 PASS |
| SDK 지연 후 Marker 표시,409 최신 revision 순차 조회, 삭제 Marker 종료 | 지도 표시/상세 계약 / R-03/04 | 동일 파일3개 PASS |
| 선택 좌표 Marker 및 해제, 실패 안내 | FR-013/016 / R-05 | 동일 파일2개 PASS; 실제 기기의 새 미리보기는 미확인 |
| 장소·지역 카테고리 범위, 지역 건수, EXPENSE 상세 | FR-023/025 / R-06 | 동일 파일1개 PASS |
| 지도 고정 오류 코드·timeout·집계/인증/허용 목록 | NFR-013 / R-07 | map-diagnostics3개, Backend MapDiagnosticsTest/HttpContractTest PASS; 운영 수집 연결은 미설정 |
| public 복원, 탈퇴 재삭제, 고아/세션 검사, 공개 차단 | 탈퇴 복원 계약 / R-01 | review-restore-test.log PASS, RecoveryGateTest PASS; 외부 최신성/WAL/보존은 미검증 |

전체 실행 수치/명령은 test-report.md의2026-09-24절을 따른다. 기존 NFR-003 조회 부하 이력은 재실행하지 않았으며 변경 API p95 공백을 해소했다고 주장하지 않는다.
