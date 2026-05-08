package com.example.receiptmemo.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Springdoc OpenAPI 설정.
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI receiptMemoOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Receipt → Notion Memo API")
                .description("영수증 OCR 결과를 파싱해 Notion 가계부의 메모를 자동 채우는 API")
                .version("v1"));
    }
}
