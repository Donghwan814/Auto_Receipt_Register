package com.example.receiptmemo.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Notion 웹훅 등 비동기 처리에 사용하는 TaskExecutor.
 * Notion 웹훅 endpoint 는 즉시 200 OK 를 반환해야 하므로,
 * OCR/다운로드/Notion update 등 무거운 작업은 이 executor 위에서 실행한다.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "notionWebhookExecutor")
    public TaskExecutor notionWebhookExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(16);
        exec.setQueueCapacity(200);
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("notion-webhook-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        return exec;
    }
}
