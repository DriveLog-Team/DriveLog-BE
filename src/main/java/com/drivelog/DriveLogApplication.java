package com.drivelog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@io.swagger.v3.oas.annotations.OpenAPIDefinition(servers=@io.swagger.v3.oas.annotations.servers.Server(url="/"), info=@io.swagger.v3.oas.annotations.info.Info(title="DriveLog API", version="2026-11", description="등록 운행의 정차 후 추천·편집·휴대폰 저장. /api/frontend.html 참고."))
@io.swagger.v3.oas.annotations.security.SecurityScheme(name="operatorToken",type=io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP,scheme="bearer",description="팀 BFF 서버 전용 토큰. 공개 앱에 포함하지 않는다.")
@io.swagger.v3.oas.annotations.security.SecurityScheme(name="personToken",type=io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP,scheme="bearer",description="해당 개인의 accessToken. 팀 BFF는 로그인한 사용자에게 대응하는 토큰만 사용한다.")
public class DriveLogApplication {
    public static void main(String[] args) {
        SpringApplication.run(DriveLogApplication.class, args);
    }
}
