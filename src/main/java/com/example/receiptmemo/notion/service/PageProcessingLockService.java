package com.example.receiptmemo.notion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 같은 pageId 에 대한 동시 처리를 방지하기 위한 페이지 단위 락.
 *
 * 1순위 — MySQL named lock (`SELECT GET_LOCK(?, 0)`).
 *   Cloud Run 처럼 인스턴스가 여러 개여도 DB 레벨에서 직렬화된다.
 *   같은 connection 으로 GET_LOCK / RELEASE_LOCK 을 호출해야 하므로
 *   {@link DataSourceUtils} 로 connection 을 직접 잡고 task 가 끝날 때까지 들고 있는다.
 *
 * 2순위 — JVM ReentrantLock (H2 등 GET_LOCK 미지원 DB 또는 SQL 실패 시 fallback).
 *
 * 사용:
 * <pre>
 *   boolean ran = lockService.runWithLock(pageId, () -> ... );
 *   if (!ran) { // busy 였음 — 정상 skip }
 * </pre>
 */
@Slf4j
@Service
public class PageProcessingLockService {

    private final DataSource dataSource;
    private final ConcurrentMap<String, ReentrantLock> jvmLocks = new ConcurrentHashMap<>();
    private volatile boolean dbLockUnsupported = false;

    @Autowired
    public PageProcessingLockService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * pageId 에 대해 lock 을 시도하고, 획득하면 task 실행 후 해제.
     * @return task 를 실제로 실행했으면 true, busy 로 skip 했으면 false.
     */
    public boolean runWithLock(String pageId, Runnable task) {
        if (pageId == null || pageId.isBlank()) {
            // page 식별이 안되면 그냥 실행 (lock 없이).
            task.run();
            return true;
        }
        if (!dbLockUnsupported) {
            Boolean dbResult = tryRunWithDbLock(pageId, task);
            if (dbResult != null) return dbResult;
            // null 이면 DB lock 미지원 → JVM fallback 으로 떨어진다.
        }
        return runWithJvmLock(pageId, task);
    }

    /** @return true=실행, false=busy skip, null=DB lock 사용 불가(JVM 으로 fallback). */
    private Boolean tryRunWithDbLock(String pageId, Runnable task) {
        Connection conn = null;
        String lockName = "receipt-page-" + pageId;
        Boolean acquired = null;
        try {
            conn = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                ps.setString(1, lockName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Object v = rs.getObject(1);
                        if (v == null) {
                            // GET_LOCK 자체 에러 — 보수적으로 미지원 처리.
                            log.warn("[PageLock] GET_LOCK 결과 null. DB lock fallback to JVM. pageId={}", pageId);
                            dbLockUnsupported = true;
                            return null;
                        }
                        long n = ((Number) v).longValue();
                        acquired = n == 1L;
                    }
                }
            }
        } catch (Exception e) {
            // SQL exception (예: H2 GET_LOCK 미지원) → JVM fallback.
            log.warn("[PageLock] DB lock 미지원/실패 → JVM fallback. err={}", e.getMessage());
            dbLockUnsupported = true;
            if (conn != null) DataSourceUtils.releaseConnection(conn, dataSource);
            return null;
        }

        if (acquired == null || !acquired) {
            log.info("[PageLock] busy skip pageId={}", pageId);
            DataSourceUtils.releaseConnection(conn, dataSource);
            return false;
        }

        log.info("[PageLock] acquired pageId={}", pageId);
        try {
            task.run();
            return true;
        } finally {
            try (PreparedStatement ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                ps.setString(1, lockName);
                ps.executeQuery();
            } catch (Exception e) {
                log.warn("[PageLock] RELEASE_LOCK 실패. pageId={}, err={}", pageId, e.getMessage());
            }
            DataSourceUtils.releaseConnection(conn, dataSource);
            log.info("[PageLock] released pageId={}", pageId);
        }
    }

    private boolean runWithJvmLock(String pageId, Runnable task) {
        ReentrantLock lock = jvmLocks.computeIfAbsent(pageId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.info("[PageLock] busy skip pageId={}", pageId);
            return false;
        }
        log.info("[PageLock] acquired pageId={}", pageId);
        try {
            task.run();
            return true;
        } finally {
            lock.unlock();
            log.info("[PageLock] released pageId={}", pageId);
        }
    }
}
