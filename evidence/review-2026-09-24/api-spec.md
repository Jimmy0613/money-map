# API 명세

> 현재 상태 안내(2026-09-23): 이 문서의 승인 계약은 유지합니다. Windows 미실행·예정·당시 상태 문구는 작성 시점의 이력이며, 현재 구현/검증 상태와 누락은 [자체 점검](self-review.md)을 따릅니다. 핵심 구현은 있으나 개발·검증 미완료이며 배포만 남은 상태가 아닙니다.

## 1. 공통 계약

Base URL은 동일 Origin의 /api/v1이다. 브라우저는 credentials=include로 요청한다. 인증은 mm_access와 mm_refresh HttpOnly 쿠키이며 토큰 JSON 응답은 없다. 변경 요청에는 X-XSRF-TOKEN, JSON 요청에는 Content-Type: application/json을 보낸다. requestId는 서버 UUID이며 X-Request-Id 응답 헤더에도 제공한다. 개인 응답은 Cache-Control: no-store다.

UUID 필드는 문자열, 금액은 소수점 없는 문자열, 날짜는 YYYY-MM-DD, 시각은 UTC ISO 8601 Z, 좌표는 소수점 최대 6자리 십진 문자열이다. accuracyM은 최대 소수점 3자리 문자열이다. version, 건수, 비율은 JSON number다. 요청 미지 필드는 400으로 거부한다. 본문 최대 16KiB, 초과는 413이다. 아래 예시의 example.invalid 이메일과 UUID는 합성 fixture이며 실제 개인정보나 Secret이 아니다.

성공은 {"data":...,"meta":{"requestId":"00000000-0000-4000-8000-000000000001"}}이며 204는 본문이 없다. 아래 각 응답 JSON은 반복을 줄이기 위해 data 내용만 제시한다. 실제 전송 시 이 envelope로 감싼다.

```json
{"error":{"code":"VALIDATION_FAILED","message":"입력값을 확인하세요.","fields":[{"field":"amount","code":"OUT_OF_RANGE","message":"1원 이상 입력하세요."}]},"meta":{"requestId":"00000000-0000-4000-8000-000000000001"}}
```

400 VALIDATION_FAILED/INVALID_CURSOR, 401 UNAUTHENTICATED/ACCESS_EXPIRED/REFRESH_REUSED, 403 CSRF_INVALID/ORIGIN_DENIED, 404 NOT_FOUND, 409 EMAIL_EXISTS/CATEGORY_CONFLICT/CURSOR_STALE/IDEMPOTENCY_CONFLICT/OPERATION_DELETED, 412 VERSION_CONFLICT, 428 PRECONDITION_REQUIRED, 429 RATE_LIMITED, 500 INTERNAL_ERROR, 503 SERVICE_UNAVAILABLE를 사용한다. 내부 SQL, 스택, 입력 원문, 존재하는 타인 객체 정보는 반환하지 않는다. 단일 객체 응답은 ETag: "0"처럼 version을 제공한다.

## 2. 공통 필터 및 DTO

### 필터

from/to는 함께 제공하며 양끝 포함, 생략하면 사용자 시간대 현재 월이다. 역전·잘못된 날짜는 400이다. type은 INCOME/EXPENSE, categoryIds는 반복 query 최대 100개 UUID, hasLocation은 true/false다. page size는 기본 20, 1~100. sort는 거래 date_desc만 지원하며 transactionDate DESC, createdAt DESC, id DESC다. 다른 sort는 400이다.

커서는 HMAC 서명된 사용자 ID, dataRevision, 필터 해시, 마지막 정렬 튜플, 만료(15분)를 포함하며 클라이언트는 해석하지 않는다. 다른 사용자 커서는 400, revision 변경은 409 CURSOR_STALE이다. 재시작할 때 전체 목록을 교체한다. 응답 page는 {"nextCursor":null,"hasNext":false,"size":20}, revision은 문자열이다. 목록/통계의 합계는 해당 페이지가 아니라 전체 필터 대상이다. 보관 categoryIds는 조회에 허용한다. 존재하지 않거나 타인 카테고리는 404다.

### TransactionWrite

```json
{"type":"EXPENSE","amount":"12000","currency":"KRW","categoryId":"00000000-0000-4000-8000-000000000010","transactionDate":"2026-09-15","memo":null,"location":null}
```

모든 키는 필수이고 memo/location만 null 허용. 금액 1~999999999999, 통화 KRW, 날짜는 사용자 today 이하여야 한다. memo는 trim 후 최대 500자다. 위치는 null 또는 다음 LocationWrite다.

### LocationWrite와 LocationView

```json
{"source":"SEARCH","externalPlaceId":"fixture-place-01","placeName":"예시 장소","latitude":"37.500000","longitude":"127.000000","accuracyM":null,"capturedAt":null,"lowAccuracyAccepted":false,"roadAddress":"예시 도로명 주소","parcelAddress":null,"regionCode":"11680","regionSido":"서울특별시","regionSigungu":"강남구"}
```

CURRENT는 externalPlaceId=null, accuracyM>=0과 capturedAt 필수다. capturedAt은 서버 현재보다 5분 넘게 미래이면 거부한다. accuracyM>1000이면 lowAccuracyAccepted=true 필수다. MAP은 externalPlaceId, accuracyM, capturedAt=null이다. SEARCH는 placeName 필수, externalPlaceId는 없으면 null 허용, accuracyM와 capturedAt=null이다. CURRENT 외 lowAccuracyAccepted는 false다. provider는 서버가 KAKAO로 고정한다. 위도 -90~90, 경도 -180~180, 장소명 200자, 주소 각 500자, 지역 시도 50자, 시군구 100자, 지역코드 숫자 5자다. 지역코드를 제외한 문자열은 trim하며 빈 값 null이다.

선택과 저장은 거래 POST/PUT에 포함되며 별도 Place 생성 API가 없다. 브라우저 keywordSearch 입력 {"query":"예시","page":1}은 SDK 어댑터 계약이고 Backend Endpoint가 아니다. 결과를 위 DTO로 정규화하고 300ms debounce, 최신 응답만 적용한다. 주소 해석 실패는 주소와 지역 null을 허용한다. 저장 응답 LocationView는 입력에 placeId(UUID 또는 null)와 regionKey를 추가하고 외부 ID는 그대로 표시한다. regionKey와 placeId는 클라이언트 지정 불가다.

### TransactionView

```json
{"id":"00000000-0000-4000-8000-000000000020","type":"EXPENSE","amount":"12000","currency":"KRW","category":{"id":"00000000-0000-4000-8000-000000000010","name":"식비","archived":false},"transactionDate":"2026-09-15","memo":null,"location":null,"version":0,"createdAt":"2026-09-15T01:00:00Z","updatedAt":"2026-09-15T01:00:00Z"}
```

## 3. 인증 Endpoint

### GET /auth/csrf

인증 불필요. 요청 본문 없음. 200 data={"headerName":"X-XSRF-TOKEN"}, XSRF-TOKEN 쿠키 발급. 실제 토큰은 예시에서 생략한다. 가입/로그인 전에 호출하고 인증 전환 뒤 재발급한다. FR-001~FR-004 보안 지원.

### POST /auth/signup

FR-001. 인증 불필요, CSRF 필요.

```json
{"email":"member@example.invalid","password":"<POLICY_COMPLIANT_PASSWORD>","passwordConfirmation":"<POLICY_COMPLIANT_PASSWORD>","termsAccepted":true,"termsVersion":"v1"}
```

password 예시는 placeholder이며 실제 요청에는 정책을 충족하는 사용자의 비밀번호를 보낸다. 이메일 254자 이하 및 형식 검증, 비밀번호 10~64자 영문자/숫자 포함, 두 비밀번호 일치, termsAccepted=true 및 현재 termsVersion 필수. 201 응답:

```json
{"id":"00000000-0000-4000-8000-000000000002","email":"member@example.invalid","timezone":"Asia/Seoul"}
```

가입과 기본 카테고리 생성은 원자적이다. 자동 로그인하지 않는다. 400 입력 오류, 409 EMAIL_EXISTS, 429 제한. 동의할 실제 약관 내용과 버전은 운영 출시 전에 제공해야 한다.

### POST /auth/login

FR-002. 요청 {"email":"member@example.invalid","password":"<PASSWORD>"}. 200 응답 {"userId":"00000000-0000-4000-8000-000000000002","timezone":"Asia/Seoul","today":"2026-09-15"}와 두 인증 Set-Cookie. 400 형식, 401 자격 불일치, 429 제한. 존재하지 않는 이메일과 틀린 비밀번호는 같은 응답.

### POST /auth/refresh

FR-002, FR-003. 요청 {}, refresh 쿠키 및 CSRF 필수. 200 응답 {"refreshed":true}와 교체한 두 쿠키. refresh 해시 원문은 JSON에 없음. 만료/재사용은 401, 재사용 시 세션 가족 폐기, 병렬 요청도 한 번만 회전한다. 응답 유실은 재로그인으로 복구한다.

### POST /auth/logout

FR-003. 요청 {}, CSRF 필수, 유효 access 또는 저장된 refresh 해시로 현재 세션을 폐기한다. 이미 만료/삭제된 세션도 204로 쿠키를 지운다. Cookie 삭제 Path는 발급과 동일하다. 반환 본문 없음. CSRF 실패 403, 서버 처리 실패 503. 클라이언트는 통신 실패 또는 응답 유실 시 개인 캐시를 비우고 개인 화면과 자동 인증 갱신을 차단하며 logoutPending 및 재시도를 제공한다. 204 응답 확인 후에만 완료로 전환한다. 이 예외 규칙은 CHG-003으로 승인되었다.

## 4. 사용자 Endpoint

### GET /users/me

FR-004, FR-027. 요청 본문 없음. 200 응답:

```json
{"id":"00000000-0000-4000-8000-000000000002","email":"member@example.invalid","displayName":null,"timezone":"Asia/Seoul","today":"2026-09-15","version":0}
```

미인증 401. ETag 제공.

### PATCH /users/me

FR-027(Could). If-Match 필수. 요청 {"displayName":"예시 사용자","timezone":"Asia/Seoul"}. 제공한 필드만 변경, 최소 한 필드, 이름 null 또는 1~50자, ZoneId 유효값. 200은 GET /users/me와 같은 DTO, version 증가. 400/412/428. 기존 거래 날짜는 변경하지 않는다.

### DELETE /users/me

FR-028. 요청 {"currentPassword":"<PASSWORD>","confirmation":"DELETE"}. CSRF 및 재인증 필수, 사용자 데이터와 세션 cascade 삭제, 204 본문 없음 및 쿠키 만료. 400 확인문구, 401 비밀번호 불일치, 503 삭제 실패. 성공은 DB commit 이후만 반환한다.

## 5. 거래 Endpoint

### POST /transactions

FR-005, FR-012~FR-017. Idempotency-Key UUID 필수, 요청은 TransactionWrite. 201 응답은 TransactionView, Location: /api/v1/transactions/{id}, ETag 제공. 같은 키/본문 재요청은 200 현재 TransactionView와 Idempotent-Replay:true. 다른 본문은 409, 이미 삭제한 거래 키는 409 OPERATION_DELETED. 키가 없거나 UUID가 아니면 400. 유효성 400, category 소유권 404, 보관 카테고리 신규 지정 409.

### GET /transactions

FR-006, FR-007. 예: /transactions?from=2026-09-01&to=2026-09-30&type=EXPENSE&size=20. 공통 필터 외 placeId 또는 regionKey 허용. placeId 타인/없음은 404. regionKey=UNKNOWN도 조회 가능하며 이 경우 위치 없는 거래는 제외된다. 요청 본문 없음.

```json
{"items":[],"page":{"nextCursor":null,"hasNext":false,"size":20},"revision":"0","totals":{"incomeTotal":"0","expenseTotal":"0","balance":"0","count":0}}
```

items 원소는 TransactionView. 200 빈 목록은 정상, 400/404/409 커서 조건 적용. 고정 정렬 외 요청 거부.

### GET /transactions/{id}

FR-007. 본문 없음. 200 TransactionView와 ETag. 다른 사용자 및 없는 거래는 404.

### PUT /transactions/{id}

FR-008, FR-016. If-Match: "0" 필수. 요청 TransactionWrite 전체 교체. location=null은 기존 위치 삭제, 수정값에는 등록과 같은 검증. 기존 보관 categoryId 유지 가능. 200 TransactionView(version 증가), 400/404/409/412/428. 동일 version 동시 요청 중 한 건만 성공.

### DELETE /transactions/{id}

FR-009. If-Match 필수, 본문 없음. 프런트에서 금액·날짜가 포함된 확인 창을 거친다. 204 본문 없음, 404/412/428. 반복 삭제는 404이며 부작용 없음. 삭제 뒤 합계 및 지도 cache를 무효화한다.

## 6. 카테고리 Endpoint

### GET /categories

FR-010. 예: /categories?type=EXPENSE&includeArchived=false. type 선택, includeArchived 기본 false. 본문 없음, 200 응답:

```json
{"items":[{"id":"00000000-0000-4000-8000-000000000010","type":"EXPENSE","name":"식비","isDefault":true,"archived":false,"icon":"food","color":null,"sortOrder":0,"version":0}]}
```

sortOrder ASC,id ASC. 잘못된 enum은 400.

### POST /categories

FR-011. 요청 {"type":"EXPENSE","name":"개인 분류","icon":null,"color":null}. name 1~30자, icon은 food/cafe/transport/shopping/home/life/medical/culture/travel/education/salary/money/business/finance/other 또는 null, color #RRGGBB 또는 null. 201은 위 category 원소 형태, isDefault=false, archived=false, version=0. 400, 이름 중복 409.

### PATCH /categories/{id}

FR-011. If-Match 필수. 요청 {"name":"변경 분류","icon":"other","color":"#336699"} 또는 {"archived":true}. 이름 변경과 보관은 동시에 요청하지 않는다. archived=false 복원은 미지원 400. type/isDefault 변경은 금지. 200 category DTO, 404 타인/없음, 409 기본 또는 이미 보관된 항목/중복 이름, 412/428. 보관된 명칭은 고정된다.

## 7. 지도 Endpoint

### GET /map/markers

FR-018, FR-019, FR-021. south/north/west/east 좌표 문자열과 level(1~14)은 필수다. from/to는 함께 생략하면 현재 월이며 categoryIds는 선택이고 생략하면 전체 지출 카테고리다. south<=north 필수, west>east는 날짜 변경선 처리. type은 입력받지 않고 EXPENSE 고정. 예: /map/markers?south=37.000000&north=38.000000&west=126.000000&east=128.000000&level=5.

```json
{"items":[{"markerKey":"place:00000000-0000-4000-8000-000000000030","title":"예시 장소","latitude":"37.500000","longitude":"127.000000","amount":"12000","count":1}],"viewportExpenseTotal":"12000","viewportCount":1,"periodExpenseTotal":"15000","locationlessCount":1,"requiresNarrowing":false,"limit":500,"revision":"5"}
```

수동 위치 markerKey는 coord:latitude:longitude다. level 1~5 500개, 6~9 300개, 10~14 200개. 초과 시 items=[]/requiresNarrowing=true, 실제 viewport 합계·count 유지. 0건은 items=[]/requiresNarrowing=false/viewport 합계 0. cap은 Marker 묶음 수 기준이다. 클라이언트 Cluster가 가까운 Marker를 묶으며 사용자 장소 기록을 병합하지 않는다. 400 잘못된 좌표/level, 404 카테고리, 503 조회 실패.

### GET /map/marker-details

FR-020. 요청 본문 없음. markers와 동일 필터 및 BBox에 markerKey, revision, size, cursor를 추가한다. 200 응답:

```json
{"markerKey":"place:00000000-0000-4000-8000-000000000030","title":"예시 장소","address":"예시 도로명 주소","amount":"12000","count":1,"items":[{"id":"00000000-0000-4000-8000-000000000020","type":"EXPENSE","amount":"12000","currency":"KRW","category":{"id":"00000000-0000-4000-8000-000000000010","name":"식비","archived":false},"transactionDate":"2026-09-15","memo":null,"location":{"source":"SEARCH","externalPlaceId":"fixture-place-01","placeName":"예시 장소","latitude":"37.500000","longitude":"127.000000","accuracyM":null,"capturedAt":null,"lowAccuracyAccepted":false,"roadAddress":"예시 도로명 주소","parcelAddress":null,"regionCode":"11680","regionSido":"서울특별시","regionSigungu":"강남구","placeId":"00000000-0000-4000-8000-000000000030","regionKey":"CODE:11680"},"version":0,"createdAt":"2026-09-15T01:00:00Z","updatedAt":"2026-09-15T01:00:00Z"}],"page":{"nextCursor":null,"hasNext":false,"size":20},"revision":"5"}
```

items는 TransactionView다. MarkerKey가 현재 필터에 없으면 404, revision 변경은 409로 지도 전체 재조회. 키는 선택 식별자이며 권한 증명이 아니다. 서버가 항상 user_id 조건을 추가한다.

## 8. 통계 Endpoint

모든 통계는 GET, 본문 없음, 200, 미인증 401, 잘못된 필터 400, 타인 카테고리/장소 404, 서버 실패 503을 공통 적용한다. 합계 0과 통신 실패를 구분한다. 금액은 문자열이며 퍼센트는 HALF_UP 정수다.

### GET /statistics/summary

FR-022. from/to만 지원. 예: /statistics/summary?from=2026-09-01&to=2026-09-30.

```json
{"incomeTotal":"100000","expenseTotal":"12000","balance":"88000","incomeCount":1,"expenseCount":1,"revision":"5"}
```

### GET /statistics/categories

FR-023. from/to, placeId, regionKey, 지도 south/north/west/east(네 개 함께)를 선택 필터로 허용, 조합은 AND. 위치 조건이 있으면 위치 없는 거래 제외. sum DESC,categoryId ASC.

```json
{"items":[{"categoryId":"00000000-0000-4000-8000-000000000010","name":"식비","amount":"12000","count":1,"percent":100}],"expenseTotal":"12000","revision":"5"}
```

### GET /statistics/places

FR-024. from/to, categoryIds, size, cursor, sort=amount_desc(기본) 또는 count_desc. 정렬 동률은 placeId ASC, 커서에 정렬값·ID·revision을 포함.

```json
{"items":[{"placeId":"00000000-0000-4000-8000-000000000030","name":"예시 장소","address":"예시 도로명 주소","amount":"12000","count":1,"lastTransactionDate":"2026-09-15"}],"identifiedExpenseTotal":"12000","unidentifiedLocation":{"amount":"0","count":0},"locationless":{"amount":"3000","count":1},"page":{"nextCursor":null,"hasNext":false,"size":20},"revision":"5"}
```

장소 상세는 GET /transactions?type=EXPENSE&placeId=... 및 동일 from/to·categoryIds를 사용한다. 장소 미식별 좌표는 순위에서 제외하되 unidentifiedLocation으로 알린다.

### GET /statistics/regions

FR-025. from/to, categoryIds, level=SIGUNGU(고정), size, cursor. amount DESC,regionKey ASC.

```json
{"items":[{"regionKey":"UNKNOWN","name":"지역 미분류","amount":"12000","count":1,"percent":100}],"locatedExpenseTotal":"12000","locatedCount":1,"unclassifiedCount":1,"locationlessCount":1,"page":{"nextCursor":null,"hasNext":false,"size":20},"revision":"5"}
```

percent 분모는 페이지 밖을 포함한 locatedExpenseTotal이다. 지역 상세는 /transactions?type=EXPENSE&regionKey=... 및 동일 from/to·categoryIds를 사용한다. 위치 없는 거래는 UNKNOWN에 넣지 않는다.

### GET /statistics/monthly-comparison

FR-026. month=YYYY-MM 필수, categoryIds 선택. 모든 금액은 지출, 비교월은 직전 달이다.

```json
{"month":"2026-09","previousMonth":"2026-08","total":{"current":"12000","previous":"0","difference":"12000","growthPercent":null,"status":"NOT_COMPARABLE","currentCount":1,"previousCount":0},"categories":[{"key":"00000000-0000-4000-8000-000000000010","name":"식비","current":"12000","previous":"0","difference":"12000","growthPercent":null,"status":"NOT_COMPARABLE"}],"regions":[],"revision":"5"}
```

previous>0이면 growthPercent는 소수점 1자리 HALF_UP JSON number, status=COMPARABLE. 카테고리는 두 달 키 합집합 전체, 지역은 위치 거래의 두 달 키 합집합 중 절대 차액 상위 10개다. regions 원소는 categories와 같은 비교 구조이며 key는 regionKey다. 한쪽 데이터 없음은 금액 0이며 total의 count로 구분한다.

## 9. 캐시 및 실패 복구

거래 변경·카테고리 변경 후 거래 목록, 상세, 지도, 모든 통계 Query를 무효화한다. 인증·탈퇴 시 전체 개인 Query cache를 비운다. SDK 키 없음, 권한 거부, 검색 없음은 API 거래 검증 오류가 아니다. 사용자가 location=null로 다시 제출할 수 있고 기존 입력을 보존한다. 변경 요청 재전송은 POST Idempotency-Key 및 PUT/DELETE If-Match로 보호한다. 503을 성공으로 낙관 표시하지 않는다.

## 10. 상태

이 문서는 구현 계약이며 Endpoint가 실행 중이라는 의미가 아니다. 실제 Secret 발급·계정 로그인·빌드·테스트는 미수행이다. architecture.md의 FR 추적표가 전체 Endpoint/모델 대응을 제공한다. FR-003 통신 실패 예외는 CHG-003으로 승인되어 요구사항과 일치한다.

## 11. 교차 검토 보완 규약

### 로그아웃과 상태 확인

/auth/logout 요청 전에 mm.logoutPending=true를 기록하고 부트스트랩 및 다른 탭에서도 개인 조회·자동 refresh보다 먼저 검사한다. 쿠키 삭제 응답 유실과 403 재시도를 포함한 상세 상태 전이는 architecture.md 11절을 따른다. 새로고침으로 대기 상태가 해제되어서는 안 된다.

### 탈퇴 원자성

DELETE /users/me는 privacy.deletion_ledger 삽입과 users 삭제를 같은 DB Transaction으로 수행한다. 하나라도 실패하면 전체 rollback 및 503이다. 204는 두 작업 commit 후에만 반환한다. ledger는 개인정보 응답으로 노출하지 않고 별도 HTTP 관리 API를 만들지 않는다.

### 조건부 변경 결과 복구

PUT /transactions/{id}, PATCH /categories/{id}의 네트워크 결과가 불명확하면 현재 객체를 다시 읽어 제출한 업무 필드와 비교한다. 거래는 GET /transactions/{id}, 카테고리는 GET /categories?includeArchived=true에서 해당 ID를 찾는다. 현재 값이 같으면 일치 상태를 알리고, 다르면 초안을 보존해 사용자가 새 version으로 다시 저장하도록 한다. 412를 자동 성공 또는 자동 재저장으로 해석하지 않는다.

DELETE /transactions/{id}의 결과가 불명확하면 GET 상세를 확인한다. 404는 현재 존재하지 않는 상태, 200은 여전히 존재하는 상태다. 401/503은 결과 미확정이므로 인증 복구 또는 재조회가 필요하다. 무조건 새 If-Match로 자동 삭제하지 않는다.

### 지역 응답과 필터

regionKey는 CODE:숫자5자리 또는 UNKNOWN만 허용한다. NAME 키는 400이다. 코드 없는 주소도 사전에서 이름과 거래일로 유일하게 대응하면 CODE 키로 반환한다. 미분류는 UNKNOWN, 표시명은 지역 미분류다. LocationView는 원본 regionCode/regionSido/regionSigungu와 별개로 정규화된 regionKey를 반환한다. canonical_region_key는 내부 FK 컬럼으로 JSON에 추가하지 않는다. 통계 name은 사전의 표준 시도 및 시군구 이름을 결합한다.

장소와 지역 통계의 상세 조회에는 반드시 type=EXPENSE를 전달한다. from/to, categoryIds와 선택 식별자를 보존하여 수입이 섞이지 않게 한다. categoryIds는 지도와 통계에서 생략 가능하며 생략 시 전체 지출 카테고리다. 빈 categoryIds 값이나 INCOME 카테고리를 지출 전용 API에 지정하면 400이다.


## macOS 구현 대응 기록 (2026-09-19)

실제 Controller는 /api/v1 인증·거래·분류·지도·통계 계약을 구현했다. FR-027 프로필 편집은 Could 선택 범위 미포함이다. 오류 envelope의 fields는 Bean Validation 및 가입·거래 업무 검증의 field/code/message를 전달한다. 거부된 입력값은 포함하지 않는다. 프런트는 알려진 필드를 입력 오류 및 접근성 설명에 연결하며, 필드가 특정되지 않는 JSON 파싱/일반 오류는 전체 안내로 표시한다. 실행별 계약 검증 범위는 requirement-test-matrix.md 참조.
