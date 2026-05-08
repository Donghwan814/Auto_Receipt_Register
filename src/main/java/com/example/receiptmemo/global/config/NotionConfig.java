package com.example.receiptmemo.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * application.yaml 의 notion.* 프로퍼티를 매핑하는 설정 클래스.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "notion")
public class NotionConfig {

    /** Notion Internal Integration Secret */
    private String apiKey;

    /** 가계부 데이터베이스 ID */
    private String databaseId;

    /** Notion API 버전 헤더 값 */
    private String version = "2022-06-28";

    /** Notion API base URL */
    private String baseUrl = "https://api.notion.com/v1";

    /** 컬럼명 매핑 (한글 컬럼명을 그대로 사용) */
    private Columns columns = new Columns();

    /** 웹훅 인증 옵션 */
    private Webhook webhook = new Webhook();

    /** (deprecated) 디버그용 raw comments 엔드포인트 활성화 여부. webhook.debugComments 를 사용하세요. */
    private boolean debugComments = false;

    public boolean isDebugComments() {
        return debugComments || (webhook != null && webhook.isDebugComments());
    }

    @Getter
    @Setter
    public static class Columns {
        private String memo = "메모";
        private String amount = "금액";
        private String date = "날짜";
        private String name = "이름";
        /** 카테고리(Select) 컬럼명. null/blank 이면 카테고리 미설정. */
        private String category;
        /** 등록일(date) 컬럼명. null/blank 이면 등록일 미설정. */
        private String createdAt;
    }

    @Getter
    @Setter
    public static class Webhook {
        private String secret;
        private String verificationToken;
        private boolean strictSignatureCheck = false;
        /** 디버그용 raw comments 엔드포인트 활성화 여부 (NOTION_WEBHOOK_DEBUG_COMMENTS) */
        private boolean debugComments = false;
    }
}
