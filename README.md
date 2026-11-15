# DriveLog BE

Java 21 · Spring Boot 4.1.2 · Maven

## 실행

```sh
mvn verify
mvn spring-boot:run
```

- 상태: `GET /api/status`
- [운영 Swagger](https://35.216.33.209/api/swagger-ui.html) · [FE 연동 가이드](https://35.216.33.209/api/frontend.html) · [OpenAPI](https://35.216.33.209/api/v3/api-docs)
- 헬스 체크: `GET /actuator/health`
- 동승자 웹은 API와 같은 HTTPS 호스트의 `/api/companion/index.html`에서 제공됩니다. 초대 URL의 조각에는 운행 ID와 일회용 초대 코드가 들어갑니다. 배포 파일은 `src/main/resources/static/api/companion/`에 있으며 [FE의 passenger-web](https://github.com/DriveLog-Team/DriveLog-FE/tree/fix/companion-session-and-consent/passenger-web)과 함께 갱신합니다.
- 동의 갱신에 `X-DriveLog-Receipt-Version`으로 기존 영수증의 `at`을 전달합니다. 최초 응답은 `none`을 사용하며 충돌 시 409를 받고 다시 조회합니다. 추천·편집 생성 시 같은 요청의 재전송에는 동일한 UUID를 `Idempotency-Key`로 사용합니다.

## 배포

- `develop` → PR → `main`
- PR에서 테스트하고, main 병합 시 GitHub Actions가 GCP 서버에 자동 배포합니다.
- 배포 실패 시 직전 성공 버전으로 복구합니다. 단일 서버이므로 교체 중 잠시 중단될 수 있습니다.
- 서버 주소는 저장소의 `DEPLOY_URL` 변수에서 확인합니다.
- 운영 구성은 [deploy](deploy)에 있습니다. 고정 IP의 HTTPS 인증서는 자동 갱신합니다.
