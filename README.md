# DriveLog BE

Java 21 · Spring Boot 4.1.2 · Maven

## 실행

```sh
mvn verify
mvn spring-boot:run
```

- 상태: `GET /api/status`
- 헬스 체크: `GET /actuator/health`

## 배포

- `develop` → PR → `main`
- PR에서 테스트하고, main 병합 시 GitHub Actions가 GCP 서버에 자동 배포합니다.
- 배포 실패 시 직전 성공 버전으로 복구합니다. 단일 서버이므로 교체 중 잠시 중단될 수 있습니다.
- 서버 주소는 저장소의 `DEPLOY_URL` 변수에서 확인합니다.
- 운영 구성은 [deploy](deploy)에 있습니다. 도메인·HTTPS는 아직 미설정입니다.
