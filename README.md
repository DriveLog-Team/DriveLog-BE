# DriveLog BE

Java 21 / Spring Boot 4.1.2 기반 DriveLog 백엔드와 GCP 자동 배포 구성입니다.
현재 앱은 배포 상태와 헬스 체크를 제공하는 초기 기반입니다. 주행 기록·사용자 인증 등 서비스 기능과 DB 스키마는 아직 구현하지 않았습니다.

## 실행과 검증

Java 21과 Maven 3.6.3 이상이 필요합니다.

```sh
mvn verify
mvn spring-boot:run
curl http://localhost:8080/api/status
curl http://localhost:8080/actuator/health
python3 -m unittest discover -s deploy -p 'test_*.py' -v
```

## GitHub Actions

- main/develop PR 및 develop push: Spring Boot 테스트와 배포 입력 검증.
- main push: 테스트 → 컨테이너 빌드 → Artifact Registry 업로드 → 릴리스 manifest 발행 → 실제 서버의 배포 커밋 확인.
- main의 `Deploy production` 워크플로를 수동 실행할 수도 있습니다.
- `DEPLOY_URL` 저장소 변수가 실제 서버 주소를 가리킵니다.
- GCP는 GitHub OIDC를 사용합니다. JSON 서비스 계정 키·SSH 개인키·DB 비밀번호를 GitHub Secrets에 등록하지 않습니다.
- 신뢰 범위는 이 저장소의 불변 ID, 조직 ID, main ref, deploy.yml 워크플로, push/workflow_dispatch 이벤트로 제한합니다.

## 서버

- 프로젝트: `drivelog-prod-20261110`, 서울 리전.
- 실제 실행 VM 한 대에 Docker Compose로 backend, PostgreSQL 17, Redis 7, nginx를 운영합니다.
- DB·Redis·8080은 호스트 loopback에만 바인딩합니다. 외부 포트는 초기 상태 점검용 HTTP 80, SSH는 IAP 경로만 허용합니다.
- 초기 앱은 PostgreSQL/Redis 드라이버에 연결하지 않습니다. 서버 DB는 준비되어 있고 앱 기능 개발 시 드라이버와 migration을 추가합니다.
- DB 비밀번호는 VM에서 최초 기동할 때 생성하고 `/opt/drivelog/.env`에 root 전용으로 보관합니다.
- DB 데이터는 Docker 영속 볼륨에 저장합니다. 애플리케이션 배포 시 DB 볼륨을 삭제하지 않습니다.
- 실제 로그인·개인정보 기능을 공개하기 전에 팀 도메인과 HTTPS를 구성해야 합니다.

## 자동 배포·복구

VM의 systemd timer가 전용 릴리스 버킷의 `current.json`을 약 30초 간격으로 읽습니다.
이 버킷에는 이미지 digest와 Git SHA만 저장합니다. 기존 TtoSokcho 백업 버킷과 분리되어 있습니다.

1. 설정된 레지스트리의 고정 digest와 전체 Git SHA만 허용합니다.
2. 이미지를 먼저 받은 뒤 backend 컨테이너만 교체합니다.
3. health와 release SHA가 모두 맞으면 배포 성공으로 기록합니다.
4. 새 버전이 실패하면 직전 성공 이미지로 복구합니다. 첫 배포 전에는 직전 버전이 없습니다.
5. 실패한 동일 manifest는 반복 적용하지 않습니다. 수정한 새 커밋을 배포해야 합니다.

```sh
sudo systemctl status drivelog-deploy.timer
sudo journalctl -u drivelog-deploy.service -n 100 --no-pager
sudo docker compose --project-directory /opt/drivelog ps
```

단일 VM의 컨테이너 교체 방식이므로 짧은 서비스 중단이 발생할 수 있습니다. 고가용성·무중단 배포 구성은 아닙니다.
서버의 compose/agent/nginx/systemd 자체 변경은 현재 앱 이미지 자동 배포와 별개이며 관리자가 검토 후 반영해야 합니다.
