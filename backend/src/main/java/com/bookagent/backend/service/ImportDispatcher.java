package com.bookagent.backend.service;

import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component @RequiredArgsConstructor @lombok.extern.slf4j.Slf4j
@ConditionalOnProperty(name="book-agent.dispatch-enabled",havingValue="true",matchIfMissing=true)
public class ImportDispatcher {
    private final JdbcTemplate jdbc;private final AiClient ai;private final ObjectMapper json;private final KnowledgeImports imports;private final KnowledgeAccess access;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"knowledge-import");t.setDaemon(true);return t;});
    private final AtomicBoolean busy=new AtomicBoolean();
    @jakarta.annotation.PreDestroy public void close(){worker.shutdownNow();}
    @Scheduled(fixedDelay=2000) public void dispatch(){
        if(!busy.compareAndSet(false,true))return;
        try{
        var jobs=jdbc.queryForList("SELECT * FROM knowledge_import WHERE status='QUEUED' ORDER BY id LIMIT 1");if(jobs.isEmpty()){busy.set(false);return;}
        var job=jobs.getFirst();long id=((Number)job.get("id")).longValue();String run=UUID.randomUUID().toString();
        if(jdbc.update("UPDATE knowledge_import SET status='RUNNING',run_id=? WHERE id=? AND status='QUEUED'",run,id)==0){busy.set(false);return;}
        worker.submit(()->{
            try{
                var options=json.readTree((String)job.get("options_json"));
                var result=ai.call("/internal/collect",Map.of("kind",job.get("kind"),"url",job.get("source_url"),"branch",options.path("branch").asText(""),"path",options.path("path").asText("")));
                JsonNode taxonomy=((Number)job.get("organize")).intValue()==1?ai.call("/internal/classify",Map.of("title",result.path("title").asText(),"content",result.path("content").asText())):null;
                access.asUser(((Number)job.get("user_id")).longValue(),()->{imports.apply(id,run,result,taxonomy);return null;});
            }catch(Exception e){String message=e instanceof com.bookagent.backend.common.exception.BusinessException?e.getMessage():"采集失败，请检查来源并重试";jdbc.update("UPDATE knowledge_import SET status='FAILED',error=? WHERE id=? AND status='RUNNING' AND run_id=?",message.substring(0,Math.min(message.length(),1000)),id,run);}
            finally{busy.set(false);}
        });
        }catch(RuntimeException error){busy.set(false);log.warn("Import scheduler unavailable: {}",error.getClass().getSimpleName());}
    }
}
