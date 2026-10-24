package com.bookagent.backend.service;

import com.bookagent.backend.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name = "book-agent.dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class BookIndexDispatcher {
    private final JdbcTemplate jdbc;
    private final BookService books;
    private final AiClient ai;
    private final @Qualifier("bookIndexExecutor") ExecutorService bookIndexExecutor;
    private final AtomicBoolean busy = new AtomicBoolean();

    @Scheduled(fixedDelayString = "${book-agent.book-dispatch-delay:2000}")
    public void dispatch() {
        jdbc.update("UPDATE book_index_job SET status='QUEUED',error=NULL WHERE operation='DELETE' AND status='FAILED' AND updated_at < ?", Timestamp.from(Instant.now().minusSeconds(60)));
        for (var old : jdbc.queryForList("SELECT id,run_id FROM book_index_job WHERE status='RUNNING' AND started_at < ?", Timestamp.from(Instant.now().minusSeconds(900))))
            books.finishIndex(((Number)old.get("id")).longValue(), (String)old.get("run_id"), 0, "索引执行超时或服务重启，请重试");
        if (!busy.compareAndSet(false, true)) return;
        try {
            var jobs = jdbc.queryForList("SELECT * FROM book_index_job WHERE status='QUEUED' ORDER BY id LIMIT 1");
            if (jobs.isEmpty()) { busy.set(false); return; }
            var job = jobs.getFirst(); long jobId = ((Number)job.get("id")).longValue(), bookId = ((Number)job.get("book_id")).longValue(), revision = ((Number)job.get("revision")).longValue();
            String runId = UUID.randomUUID().toString();
            if (jdbc.update("UPDATE book_index_job SET status='RUNNING',run_id=?,started_at=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED'", runId, jobId) == 0) { busy.set(false); return; }
            jdbc.update("UPDATE book SET index_status='RUNNING',updated_at=updated_at WHERE id=? AND revision=? AND deleted=0", bookId, revision);
            bookIndexExecutor.submit(() -> {
                try {
                    if ("DELETE".equals(job.get("operation"))) ai.call("/internal/books/delete", Map.of("book_id", bookId, "revision", revision));
                    else {
                        var result = ai.call("/internal/books/index", books.manifest(bookId, revision));
                        if (result.path("superseded").asBoolean()) throw new BusinessException(409, "索引版本已过期");
                        books.finishIndex(jobId, runId, result.path("chunk_count").asInt(), null);
                        return;
                    }
                    books.finishIndex(jobId, runId, 0, null);
                } catch (Exception error) {
                    books.finishIndex(jobId, runId, 0, error instanceof BusinessException ? error.getMessage() : "资料解析或向量化失败，请检查 AI 服务并重试");
                    log.warn("Book indexing failed: book={}, revision={}, type={}", bookId, revision, error.getClass().getSimpleName());
                } finally { busy.set(false); }
            });
        } catch (RuntimeException error) { busy.set(false); throw error; }
    }

    @Scheduled(fixedDelay = 30000)
    public void cleanup() { books.cleanupFiles(); }
}
