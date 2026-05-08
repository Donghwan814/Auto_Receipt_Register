package com.example.receiptmemo.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Notion API 호출용 WebClient 빈 설정.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient notionWebClient(NotionConfig notionConfig) {
        return WebClient.builder()
                .baseUrl(notionConfig.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + notionConfig.getApiKey())
                .defaultHeader("Notion-Version", notionConfig.getVersion())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
