package com.example.receiptmemo.notion.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 는 GET_LOCK 을 지원하지 않으므로 PageProcessingLockService 가 자동으로
 * JVM ReentrantLock 으로 fallback 하는지 검증한다. 동작 자체(같은 pageId 동시 처리 차단)는
 * fallback 경로에서도 보장돼야 한다.
 */
class PageProcessingLockServiceTest {

    private DataSource h2DataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:locktest;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }

    @Test
    void 락_획득시_task_실행하고_true_반환() {
        PageProcessingLockService svc = new PageProcessingLockService(h2DataSource());
        AtomicInteger calls = new AtomicInteger();
        boolean ran = svc.runWithLock("page-1", calls::incrementAndGet);
        assertThat(ran).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void 같은_pageId_가_이미_처리중이면_false_반환_후_skip() throws Exception {
        PageProcessingLockService svc = new PageProcessingLockService(h2DataSource());
        AtomicInteger inner = new AtomicInteger();
        AtomicInteger outerRan = new AtomicInteger();

        // outer task 안에서 inner runWithLock 을 다시 시도 (same pageId 동시 처리 시뮬레이션).
        boolean outer = svc.runWithLock("page-X", () -> {
            outerRan.incrementAndGet();
            // 다른 스레드에서 같은 페이지에 대해 다시 락 획득 시도
            Thread t = new Thread(() -> {
                boolean innerOk = svc.runWithLock("page-X", inner::incrementAndGet);
                if (innerOk) inner.set(999);
            });
            t.start();
            try { t.join(); } catch (InterruptedException ignored) { }
        });

        assertThat(outer).isTrue();
        assertThat(outerRan.get()).isEqualTo(1);
        assertThat(inner.get()).isEqualTo(0); // busy 라서 실행되지 않아야 함
    }

    @Test
    void 락_해제후_같은_pageId_재획득_가능() {
        PageProcessingLockService svc = new PageProcessingLockService(h2DataSource());
        AtomicInteger calls = new AtomicInteger();
        svc.runWithLock("page-Y", calls::incrementAndGet);
        boolean second = svc.runWithLock("page-Y", calls::incrementAndGet);
        assertThat(second).isTrue();
        assertThat(calls.get()).isEqualTo(2);
    }
}
