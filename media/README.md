# 정차 후 미디어 워커

운영 프로필은 `media`다. [프론트 연동 순서와 API](../src/main/resources/static/api/frontend.html)를 참고한다.
사전 등록한 팀 합성 운행만 처리한다. 계정별 보관함이나 차량 원본 업로드 기능은 없다.

- 등록 입력: `MEDIA_TRIPS/<tripId>/trip.json`, 전·후방 MP4. 추출 시 P단·정차·탑승자 전원 및 보호자 동의와 도로 구간 검토가 필요하다. Gemini에는 별도 전사문 제공 동의가 필요하다.
- 워커: CPU Whisper 한국어 전사 → Gemini 후보 ID·근거 발화 선택 → FFmpeg로 전·후방 영상과 원음·자막 합성. Gemini 오류는 작업 실패이며 임의의 추천으로 대체하지 않는다. 소스·프로필이 같으면 완성본을 재사용한다.
- 비밀은 VM의 root 전용 `/opt/drivelog/media.env`에 보관한다. 팀 BFF에는 MEDIA_TOKEN만 전달한다. 새 합성 운행을 등록할 때 서버 관리자가 매니페스트와 입력 영상을 준비한다. 개인 승객 자료는 이 팀 공용 환경의 입력이 아니다.
- VM 준비: `deploy/enable-media.sh`는 최초 HTTPS 인증·갱신, 합성 운행과 검증된 모델 설치에 사용한다. 앱 이미지는 main 배포로 교체된다. 컨테이너를 재시작하면 작업·QR ID가 초기화된다.
