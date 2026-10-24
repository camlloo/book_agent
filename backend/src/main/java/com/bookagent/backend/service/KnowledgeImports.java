package com.bookagent.backend.service;

import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.model.dto.*;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.util.*;

@Service @RequiredArgsConstructor
public class KnowledgeImports {
    private final JdbcTemplate jdbc;private final TaxonomyService keys;private final KnowledgeAccess access;private final BookService books;private final AiClient ai;private final ObjectMapper json;
    @Transactional public long queue(String kind,String url,String branch,String path,boolean organize){
        if(!List.of("WEB","REPO").contains(kind)||url.length()>2000)throw new BusinessException(400,"采集类型或地址不合法");
        try{var uri=URI.create(url.trim());if(!List.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException();url=new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),uri.getPath(),uri.getQuery(),null).toString();}catch(Exception e){throw new BusinessException(400,"请输入完整的 HTTP/HTTPS 公网地址");}
        jdbc.queryForList("SELECT id FROM `user` WHERE id=? FOR UPDATE",access.actor());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_import WHERE user_id=? AND source_url=? AND kind=? AND status IN ('QUEUED','RUNNING')",Long.class,access.actor(),url,kind)>0)throw new BusinessException(409,"该来源已有采集任务，请等待完成");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_import WHERE user_id=? AND status IN ('QUEUED','RUNNING')",Long.class,access.actor())>=10)throw new BusinessException(429,"请先等待已有采集任务完成");
        var old=jdbc.queryForList("SELECT b.id,b.revision FROM knowledge_origin k JOIN book b ON b.id=k.book_id JOIN book_owner o ON o.book_id=b.id WHERE o.user_id=? AND k.source_url=? AND k.kind=? AND b.deleted=0",access.actor(),url,kind);
        try{return keys.insert("INSERT INTO knowledge_import(user_id,kind,source_url,book_id,expected_revision,organize,options_json) VALUES(?,?,?,?,?,?,?)",access.actor(),kind,url,old.isEmpty()?null:old.getFirst().get("id"),old.isEmpty()?null:old.getFirst().get("revision"),organize?1:0,json.writeValueAsString(Map.of("branch",branch==null?"":branch,"path",path==null?"":path)));}catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    public List<Map<String,Object>> list(){return jdbc.queryForList("SELECT id,kind,source_url AS sourceUrl,book_id AS bookId,status,error,created_at AS createdAt,updated_at AS updatedAt FROM knowledge_import WHERE user_id=? ORDER BY id DESC LIMIT 100",access.actor());}
    public void retry(long id){if(jdbc.update("UPDATE knowledge_import SET status='QUEUED',error=NULL WHERE id=? AND user_id=? AND status='FAILED'",id,access.actor())==0)throw new BusinessException(409,"只有失败的本人任务可以重试");}
    public void cancel(long id){if(jdbc.update("UPDATE knowledge_import SET status='CANCELLED',run_id=NULL WHERE id=? AND user_id=? AND status IN ('QUEUED','RUNNING')",id,access.actor())==0)throw new BusinessException(409,"任务已结束");}
    public JsonNode suggest(long id){var read=books.readUnchecked(id);StringBuilder text=new StringBuilder(read.book().content());for(var section:read.sections())text.append('\n').append(section.path("text").asText());return ai.call("/internal/classify",Map.of("title",read.book().title(),"content",text.toString()));}
    @Transactional public Object organize(long id,long expected,JsonNode suggestion){
        var book=books.detail(id);if(book.revision()!=expected)throw new BusinessException(409,"Book 已变化，请重新生成整理建议");
        Long category=label("category",suggestion.path("category").asText());var tags=new ArrayList<Long>();for(var tag:suggestion.path("tags"))tags.add(label("tag",tag.asText()));
        return books.update(id,new BookSaveDTO(book.title(),book.summary(),book.content(),expected,category,tags));
    }
    private long label(String kind,String value){
        if(value.isBlank()||value.length()>64)throw new BusinessException(400,"分类标签为空或过长");
        var ids=jdbc.query("SELECT "+kind+"_id FROM "+kind+"_owner WHERE user_id=? AND label=?",(rs,n)->rs.getLong(1),access.actor(),value);
        return ids.isEmpty()?(kind.equals("category")?keys.saveCategory(null,new TaxonomyDTO(value,null,0)):keys.saveTag(null,new TaxonomyDTO(value,null,null))):ids.getFirst();
    }
    @Transactional public void apply(long id,String run,JsonNode result,JsonNode taxonomy){
        var rows=jdbc.queryForList("SELECT * FROM knowledge_import WHERE id=? AND status='RUNNING' AND run_id=? FOR UPDATE",id,run);if(rows.isEmpty())return;
        var job=rows.getFirst();long owner=((Number)job.get("user_id")).longValue();if(owner!=access.actor())throw new IllegalStateException("Import owner mismatch");access.active();
        Long bookId=job.get("book_id")==null?null:((Number)job.get("book_id")).longValue();
        String title=result.path("title").asText(),body=result.path("content").asText(),hash=result.path("source_hash").asText();
        if(title.isBlank()||title.length()>128||body.isBlank()||body.length()>1_000_000||!hash.matches("[a-f0-9]{64}"))throw new BusinessException(400,"采集结果不合法");
        if(bookId!=null){
            access.bookFor(bookId,owner,false,"OWNER");
            var current=books.detail(bookId);if(current.revision()!=((Number)job.get("expected_revision")).longValue())throw new BusinessException(409,"采集期间 Book 已被编辑，请重新发起采集");
            var hashes=jdbc.queryForList("SELECT source_hash FROM knowledge_origin WHERE book_id=?",bookId);
            if(!hashes.isEmpty()&&hash.equals(hashes.getFirst().get("source_hash"))){jdbc.update("UPDATE knowledge_import SET status='UNCHANGED',error=NULL WHERE id=?",id);jdbc.update("UPDATE knowledge_origin SET fetched_at=CURRENT_TIMESTAMP WHERE book_id=?",bookId);return;}
        }
        Long category=null;var tags=new ArrayList<Long>();
        if(taxonomy!=null){category=label("category",taxonomy.path("category").asText());for(var tag:taxonomy.path("tags"))tags.add(label("tag",tag.asText()));}
        if(bookId==null)bookId=books.create(new BookSaveDTO(title,null,body,null,category,tags)).id();
        else {var old=books.detail(bookId);books.update(bookId,new BookSaveDTO(title,old.summary(),body,old.revision(),taxonomy==null?old.categoryId():category,taxonomy==null?old.tags().stream().map(com.bookagent.backend.model.vo.TagVO::id).toList():tags));}
        try{jdbc.update("DELETE FROM knowledge_origin WHERE book_id=?",bookId);jdbc.update("INSERT INTO knowledge_origin(book_id,kind,source_url,source_hash,metadata_json) VALUES(?,?,?,?,?)",bookId,job.get("kind"),job.get("source_url"),hash,json.writeValueAsString(result.path("metadata")));}
        catch(java.io.IOException e){throw new IllegalStateException(e);}
        jdbc.update("UPDATE knowledge_import SET status='SUCCEEDED',book_id=?,expected_revision=?,error=NULL WHERE id=? AND run_id=?",bookId,books.detail(bookId).revision(),id,run);
    }
}
