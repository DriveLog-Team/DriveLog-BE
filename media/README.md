# 정차 후 미디어 워커

운영 프로필은 `media`다. [프론트 연동 순서와 API](../src/main/resources/static/api/frontend.html)를 참고한다.
팀 합성 운행은 사전 등록한다. 개인 운행은 명단·정차 정보를 먼저 등록하고 전원 동의 후 소유자가 전후방 원본을 업로드한다. 업로드를 사용하려면 VM의 nginx에 이 버전의 deploy/https.conf를 설치하고 설정 검사 후 reload한다. 이 설정이 적용돼야 대용량 본문이 워커 API로 스트리밍된다.

- 등록 입력: `MEDIA_TRIPS/<tripId>/trip.json`, 전·후방 MP4. 추출 시 P단·정차·탑승자 전원 및 보호자 동의와 도로 구간 검토가 필요하다. Gemini에는 별도 전사문 제공 동의가 필요하다.
- 워커: CPU Whisper 한국어 전사 → Gemini 후보 ID·근거 발화 선택 → FFmpeg로 전·후방 영상과 원음·자막 합성. Gemini 오류는 작업 실패이며 임의의 추천으로 대체하지 않는다. 소스·프로필이 같으면 완성본을 재사용한다.
- 비밀은 VM의 root 전용 `/opt/drivelog/media.env`에 보관한다. 팀 BFF에는 MEDIA_TOKEN만 전달한다. 새 합성 운행을 등록할 때 서버 관리자가 매니페스트와 입력 영상을 준비한다. 개인 승객 자료는 이 팀 공용 환경의 입력이 아니다.
- VM 준비: `deploy/enable-media.sh`는 최초 HTTPS 인증·갱신, 합성 운행과 검증된 모델 설치에 사용한다. 앱 이미지는 main 배포로 교체된다. 컨테이너를 재시작하면 작업·QR ID가 초기화된다.

도로 보호는 서버 CPU에서 [Open Model Zoo](https://github.com/openvinotoolkit/open_model_zoo/tree/2023.0.0/models/intel)의 face-detection-retail-0004, vehicle-license-plate-detection-barrier-0106, person-detection-retail-0013 모델을 사용한다. Docker 빌드에서 FP16 가중치와 XML의 SHA-384를 확인한다. `OPEN_MODEL_ZOO_LICENSE`에 모델 고지를 포함한다. 런타임 모델 다운로드는 없다.

10fps로 양쪽 영상을 검사하고 카메라별 offsetMs를 운행 시간에 합산한다. 얼굴·번호판은 검출 상자를 35%씩 넓히고 좌우 시간 여유 250ms를 준다. 보행자가 카메라 패널 높이의 36% 이상이거나 22% 이상이면서 하단 72% 지점에 닿으면 해당 시각 전후 600ms까지 전사·편집에서 제외한다. 이는 시연용 화면 기준으로 실측 거리나 실차 검출률을 보장하지 않는다. 번호판 모델의 학습 도메인은 중국식 정면 번호판이다. 배포 전 보관된 영상은 재인코딩하지 않는다.

웃음·옹알이 검출에는 Google [YAMNet](https://github.com/tensorflow/models/tree/master/research/audioset/yamnet) (Apache-2.0, MediaPipe float32 모델 1)을 사용한다. 서버에서 16kHz 모노 PCM을 분류하고 근접 보행자 구간과 겹치는 입력은 제외한다. score는 모델 출력값이다.
