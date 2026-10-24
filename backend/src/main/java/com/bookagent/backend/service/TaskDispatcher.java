package com.bookagent.backend.service;
import com.bookagent.backend.model.dto.TaskCallbackDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Component @RequiredArgsConstructor @Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="book-agent.dispatch-enabled",havingValue="true",matchIfMissing=true)
public class TaskDispatcher {
    private final JdbcTemplate jdbc; private final @org.springframework.beans.factory.annotation.Qualifier("aiExecutor") ExecutorService aiExecutor; private final AiClient ai;
    private final TaskService tasks; private final ObjectMapper json;
    private final BookService books;
    private final KnowledgeAccess access;
    private final AtomicBoolean busy=new AtomicBoolean(false);
    @Scheduled(fixedDelayString="${book-agent.dispatch-delay:2000}")
    public void dispatch(){
        jdbc.update("UPDATE task SET status='FAILED',error='执行超时或服务重启，请重试' WHERE status='RUNNING' AND started_at < ?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(600)));
        if(!busy.compareAndSet(false,true))return;
        try {
        var rows=jdbc.queryForList("SELECT * FROM task WHERE status='QUEUED' ORDER BY id LIMIT 1");
        if(rows.isEmpty()){busy.set(false);return;}
        var row=rows.getFirst();long id=((Number)row.get("id")).longValue();String run=UUID.randomUUID().toString();
        if(jdbc.update("UPDATE task SET status='RUNNING',run_id=?,started_at=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED'",run,id)==0){busy.set(false);return;}
        aiExecutor.submit(()->{
            try{
                var payload=json.readTree((String)row.get("request_json"));
                long actor=jdbc.queryForObject("SELECT user_id FROM task_owner WHERE task_id=?",Long.class,id);
                if(payload.has("source_book_id"))access.bookFor(payload.path("source_book_id").asLong(),actor,false,"READ");
                var body=new HashMap<String,Object>();body.put("topic",payload.path("topic").asText());body.put("instructions",payload.path("instructions").asText());
                body.put("stage",row.get("stage"));body.put("content",Objects.toString(row.get("approved_content"),""));
                if(payload.has("book_id")) {
                    long bookId=payload.path("book_id").asLong();long owner=jdbc.queryForObject("SELECT user_id FROM task_owner WHERE task_id=?",Long.class,id);access.bookFor(bookId,owner,false,"READ");books.requireReady(bookId);
                    body.put("book_id",bookId);body.put("book_revision",books.detail(bookId).revision());
                }
                var result=ai.call("/internal/write",body);String output=result.path("output").asText();
                if(output.isBlank() || output.length()>200000)throw new IllegalStateException("AI 结果为空或过长");
                if(payload.has("book_id")){long owner=jdbc.queryForObject("SELECT user_id FROM task_owner WHERE task_id=?",Long.class,id);access.bookFor(payload.path("book_id").asLong(),owner,false,"READ");}
                if(payload.has("source_book_id"))access.bookFor(payload.path("source_book_id").asLong(),actor,false,"READ");
                tasks.callback(id,new TaskCallbackDTO(run,output));
            }catch(Exception e){
                jdbc.update("UPDATE task SET status='FAILED',error=? WHERE id=? AND status='RUNNING' AND run_id=?",e instanceof com.bookagent.backend.common.exception.BusinessException?e.getMessage():"生成失败，请重试并检查 AI 日志",id,run);
                log.warn("AI task {} failed: {}",id,e.getClass().getSimpleName());
            }finally{busy.set(false);}
        });
        }catch(RuntimeException e){busy.set(false);throw e;}
    }
}
