# 임시 휴대폰 테스트 환경

> 최신 종합 점검(2026-09-23): **개발·검증 진행 중, 배포 준비 완료 아님.** 추적표71사례26PASS/45NOT_RUN은 기능 완료율이 아닙니다. Android 실기기 위치 허용·거부·저장·Marker 확인은 완료(사용자 보고), iOS/운영 복원은 미검증입니다. 신규 코드 점검 사항과 다음 순서는 [self-review.md](self-review.md)에 정리했습니다. 아래 날짜별 실행 결과는 당시 이력입니다.

2026-09-21 사용자 승인으로 실행한 주소:
https://cube-wesley-touched-acids.trycloudflare.com

- 기존 localhost와 별도 DB를 사용한다. 휴대폰에서는 테스트용 회원가입이 필요하다.
- 카카오 JavaScript SDK 도메인에 위 주소를 추가하고 localhost는 유지한다.
- Mac과 실행 프로세스가 켜져 있어야 접속 가능하며 재시작하면 주소가 바뀔 수 있다.
- 실행: `python3 scripts/mobile-preview.py`. 기존 preview를 먼저 중지해야 한다.
- 중지: `.tooling/mobile-preview/status.json`의 supervisorPid가 이 스크립트인지 확인하고 SIGTERM을 보낸다. 서버/tunnel과 테스트 DB를 중지하며 데이터를 삭제하지 않는다.
- Secret과 내부 로그는 ignored `.tooling/mobile-preview/`에 있다. 이 디렉터리를 공유하지 않는다.
- 프런트3001/백엔드8081/DB55432는 loopback에만 바인딩한다. 전용 DB 볼륨은 `money-map-mobile-preview-pgdata`이며 기존 DB를 복사하거나 초기화하지 않았다.
- HTTPS E2E2개 PASS. Galaxy S25/Android16의 Chrome·위치 설정 완료는 사용자 보고이며 실제 위치 허용/거부 검증은 대기다.
- 이 주소의 Kakao SDK는 도메인 추가 후 검증한다. 운영 배포가 아니다.

설치 및 연결 근거:
- https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/downloads/
- https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/


2026-09-21 HTTPS Kakao 도메인 등록 후 검증: 사용자 등록 완료 보고 뒤 실제 HTTPS Chromium에서 지도/장소 검색/주소·지역 코드/위치 저장/Marker 상세/합성 계정 탈퇴 E2E1개 PASS(11.7초). evidence/mobile-https-kakao-e2e.log. 실제 Galaxy S25의 GPS·위치 거부·터치 검증은 아직 대기이며 상위 추적표 상태는 유지한다.


2026-09-21 Galaxy S25/Android16 Chrome 사용자 후속 보고: 새로고침 후 현재 위치 주소 표시 성공. 최초 미표시의 원인은 확정하지 않았다. GPS 미리보기(오차 약98.4m)와 주소 표시는 수동 확인, 실제 저장·거부 시나리오는 아직 대기. 실제 좌표/주소는 수집하지 않았다.


2026-09-21 Galaxy S25 / Android16 Chrome 수동 검증(사용자 보고): 현재 위치를 확정한1,000원 거래의 금액·주소 저장 확인, 지도 Marker를 눌러1,000원 거래 표시 확인. 실제 좌표·주소는 수집하지 않았다. 실제 기기의 위치 허용→주소 표시→저장→지도 Marker 상세 경로를 확인했으며 위치 권한 거부·위치 없는 저장은 다음 단계로 남는다.


2026-09-23 Galaxy S25 / Android16 Chrome 수동 검증(사용자 보고): 사이트 위치 권한 차단 후 현재 위치 요청 시 “위치 권한이 거부되었습니다. 브라우저 권한 설정을 확인하거나 위치 없이 계속하세요.” 표시 확인. 권한 거부 안내는 확인됐으며, 거부 상태에서 위치 없이 거래 저장은 아직 대기.


2026-09-23 Galaxy S25 / Android16 Chrome 사용자 확인: 위치 권한 차단 상태에서 위치 없이 계속하기 후2,000원 거래 저장, 상세의2,000원·위치 없음 표시 확인. Android 실제 기기에서 위치 허용→주소→저장→Marker 상세와 거부→위치 없는 저장을 수동 확인했다. Chrome 정확한 버전/최신2버전 범위, iOS Safari,10초 timeout·입력 보존 전수 등은 미확인. 상위 복합 기준을 임의 PASS로 변경하지 않는다.


2026-09-23 사용자 준비 확인: 테스트 가능한 iPhone/iPad 없음. iOS Safari 실기기 검증은 미실행으로 유지하며 Android 또는 데스크톱 에뮬레이션으로 대체 통과시키지 않는다. Android Chrome 정확한 버전 확인을 다음 준비 단계로 안내한다.


2026-09-23 테스트 기기 버전 확정(사용자 보고): Galaxy S25 / Android16 / Chrome153.0.8010.52. 위치 허용·주소 포함1,000원 저장·Marker 상세 및 권한 거부 후2,000원 위치 없는 저장 수동 확인. Chrome 최신2개 주요 버전 모두의 검증을 의미하지 않으며 iOS 기기 없음으로 Safari 미실행.
