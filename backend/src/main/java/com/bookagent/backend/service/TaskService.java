package com.bookagent.backend.service;
import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.model.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor @Slf4j
public class TaskService {
    private final JdbcTemplate jdbc;
    private final TaxonomyService keys;
    private final ArticleService articles;
    private final BookService books;
    private final ObjectMapper json;
    private final KnowledgeAccess access;
    @Transactional public Map<String,Object> create(TaskCreateDTO dto) {
        if(dto.bookId()!=null && dto.sourceArticleId()!=null) throw new BusinessException(400,"Book 知识库和旧博客润色不能同时选择");
        if(dto.bookId()!=null){access.book(dto.bookId(),"READ");books.requireReady(dto.bookId());}
        if(dto.sourceArticleId()!=null)access.book(books.legacyId(dto.sourceArticleId()),"READ");
        String source=dto.sourceArticleId()==null?"":books.detail(books.legacyId(dto.sourceArticleId())).content();
        try {
            Map<String,Object> payload=new HashMap<>(Map.of("topic",dto.topic(),"instructions",dto.instructions()==null?"":dto.instructions(),"source",source));
            if(dto.sourceArticleId()!=null)payload.put("source_book_id",books.legacyId(dto.sourceArticleId()));
            if(dto.bookId()!=null){payload.put("book_id",dto.bookId());payload.put("book_revision",books.detail(dto.bookId()).revision());}
            String request=json.writeValueAsString(payload);
            long id=keys.insert("INSERT INTO task(topic,stage,status,request_json,approved_content) VALUES(?,?,'QUEUED',?,?)",dto.topic(),source.isBlank()?"OUTLINE":"POLISH",request,source);
            jdbc.update("INSERT INTO task_owner(task_id,user_id) VALUES(?,?)",id,access.actor());
            if(dto.bookId()!=null) jdbc.update("INSERT INTO task_book(task_id,knowledge_book_id) VALUES(?,?)",id,dto.bookId());
            return detail(id);
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    public Map<String,Object> detail(long id) {
        var rows=jdbc.queryForList("SELECT id,topic,stage,status,output,approved_content AS `approvedContent`,article_id AS `articleId`,error,run_id AS `runId`,created_at AS `createdAt`,updated_at AS `updatedAt` FROM task WHERE id=?",id);
        if(rows.isEmpty())throw new BusinessException(404,"任务不存在");
        var result=normalize(rows.getFirst());
        var context=jdbc.queryForList("SELECT knowledge_book_id,result_book_id FROM task_book WHERE task_id=?",id);
        if(!context.isEmpty()){result.put("bookId",context.getFirst().get("knowledge_book_id"));result.put("resultBookId",context.getFirst().get("result_book_id"));}
        var saved=jdbc.queryForList("SELECT result_book_id FROM task_book_result WHERE task_id=?",id);
        if(!saved.isEmpty())result.put("resultBookId",saved.getFirst().get("result_book_id"));
        else if(result.get("articleId")!=null)result.put("resultBookId",books.legacyId(((Number)result.get("articleId")).longValue()));
        return result;
    }
    public Map<String,Object> list(int pageNum,int pageSize) {
        int page=Math.max(1,pageNum),size=Math.clamp(pageSize,1,50);
        String filter=" WHERE EXISTS(SELECT 1 FROM task_owner o WHERE o.task_id=task.id AND o.user_id="+access.actor()+")";
        return Map.of("total",jdbc.queryForObject("SELECT COUNT(*) FROM task"+filter,Long.class),"pageNum",page,"pageSize",size,"list",
            jdbc.queryForList("SELECT id,topic,stage,status,article_id AS `articleId`,error,created_at AS `createdAt`,updated_at AS `updatedAt` FROM task"+filter+" ORDER BY id DESC LIMIT ? OFFSET ?",size,(long)(page-1)*size).stream().map(this::normalize).toList());
    }
    @Transactional public Map<String,Object> review(long id,TaskReviewDTO dto) {
        var row=jdbc.queryForList("SELECT * FROM task WHERE id=? FOR UPDATE",id);
        if(row.isEmpty())throw new BusinessException(404,"任务不存在");
        var t=row.getFirst();
        if(!"WAITING_REVIEW".equals(t.get("status")) || !Objects.equals(dto.runId(),t.get("run_id")))throw new BusinessException(409,"任务检查点已变化，请刷新后确认");
        String stage=(String)t.get("stage");
        jdbc.update("INSERT INTO task_checkpoint(task_id,stage,run_id,generated_content,approved_content) VALUES(?,?,?,?,?)",id,stage,dto.runId(),t.get("output"),dto.content());
        if("POLISH".equals(stage)) {
            var context=jdbc.queryForList("SELECT knowledge_book_id FROM task_book WHERE task_id=?",id);
            long owner=jdbc.queryForObject("SELECT user_id FROM task_owner WHERE task_id=?",Long.class,id);long bookId;
            try{bookId=access.asUser(owner,()->books.create(new BookSaveDTO((String)t.get("topic"),null,dto.content(),null)).id());}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}
            jdbc.update("INSERT INTO task_book_result(task_id,result_book_id) VALUES(?,?)",id,bookId);
            if(!context.isEmpty())jdbc.update("UPDATE task_book SET result_book_id=? WHERE task_id=?",bookId,id);
            jdbc.update("UPDATE task SET status='COMPLETED',approved_content=?,error=NULL WHERE id=?",dto.content(),id);
        }else {
            jdbc.update("UPDATE task SET stage=?,status='QUEUED',approved_content=?,output=NULL,error=NULL WHERE id=?","OUTLINE".equals(stage)?"DRAFT":"POLISH",dto.content(),id);
        }
        return detail(id);
    }
    public void callback(long id,TaskCallbackDTO dto) {
        if(jdbc.update("UPDATE task SET status='WAITING_REVIEW',output=?,error=NULL WHERE id=? AND status='RUNNING' AND run_id=?",dto.output(),id,dto.runId())==0)
            throw new BusinessException(409,"任务已结束或回调版本已过期");
    }
    public void cancel(long id) {
        detail(id);
        if(jdbc.update("UPDATE task SET status='CANCELLED' WHERE id=? AND status IN ('QUEUED','RUNNING','WAITING_REVIEW','FAILED')",id)==0)throw new BusinessException(409,"已完成的任务无法取消");
    }
    public void retry(long id) {
        detail(id);
        if(jdbc.update("UPDATE task SET status='QUEUED',error=NULL,output=NULL WHERE id=? AND status='FAILED'",id)==0)throw new BusinessException(409,"只有失败任务可重试");
    }
    @Transactional public void delete(long id){
        var rows=jdbc.queryForList("SELECT status FROM task WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new BusinessException(404,"任务不存在");
        if(!List.of("COMPLETED","FAILED","CANCELLED").contains(rows.getFirst().get("status")))throw new BusinessException(409,"请先取消正在进行的任务");
        jdbc.update("DELETE FROM task_checkpoint WHERE task_id=?",id);
        jdbc.update("DELETE FROM task_book WHERE task_id=?",id);
        jdbc.update("DELETE FROM task_book_result WHERE task_id=?",id);
        jdbc.update("DELETE FROM task WHERE id=?",id);
    }
    public List<Map<String,Object>> history(long id){detail(id);return jdbc.queryForList("SELECT stage,generated_content AS `generatedContent`,approved_content AS `approvedContent`,created_at AS `createdAt` FROM task_checkpoint WHERE task_id=? ORDER BY id",id).stream().map(this::normalize).toList();}
    private Map<String,Object> normalize(Map<String,Object> row){
        Map<String,Object> result=new LinkedHashMap<>();
        row.forEach((key,value)->result.put(switch(key.toLowerCase(Locale.ROOT)){
            case "approvedcontent" -> "approvedContent"; case "generatedcontent" -> "generatedContent";
            case "articleid" -> "articleId"; case "runid" -> "runId";
            case "createdat" -> "createdAt"; case "updatedat" -> "updatedAt"; default -> key;
        },value));return result;
    }
}
