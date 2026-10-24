package com.bookagent.backend.service;

import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.model.dto.ChatMessageDTO;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;

@Service @RequiredArgsConstructor
public class Conversations {
    private final JdbcTemplate jdbc;private final KnowledgeAccess access;private final TaxonomyService keys;private final KnowledgeSearch search;private final ObjectMapper json;private final PlatformTransactionManager tx;private final ConversationMemory memory;
    private Map<String,Object> require(long id){var rows=jdbc.queryForList("SELECT * FROM knowledge_conversation WHERE id=? AND user_id=?",id,access.actor());if(rows.isEmpty())throw new BusinessException(404,"对话不存在");return rows.getFirst();}
    private JsonNode parse(String value){try{return json.readTree(value);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    public List<Map<String,Object>> list(Long bookId){
        var rows=jdbc.queryForList("SELECT id,title,mode,scope_json AS scope,updated_at AS updatedAt FROM knowledge_conversation WHERE user_id=? ORDER BY updated_at DESC,id DESC LIMIT 200",access.actor());
        return rows.stream().filter(row->{if(bookId==null)return true;var scope=parse((String)row.get("scope"));return scope.path("bookIds").size()==1&&scope.path("bookIds").get(0).asLong()==bookId;}).map(row->{row.put("scope",parse((String)row.get("scope")));return row;}).toList();
    }
    public long create(String title,List<Long> bookIds,boolean publicOnly,String mode,String searchMode,boolean rerank){
        if(title==null||title.isBlank())title="新的知识对话";if(title.length()>128)title=title.substring(0,128);
        if(bookIds!=null&&bookIds.size()>5000)throw new BusinessException(400,"Book 选择过多");
        if(!List.of("qa","summary","compare").contains(mode))throw new BusinessException(400,"对话模式不合法");
        try{return keys.insert("INSERT INTO knowledge_conversation(user_id,title,scope_json,mode) VALUES(?,?,?,?)",access.actor(),title,json.writeValueAsString(Map.of("bookIds",bookIds==null?List.of():bookIds,"publicOnly",publicOnly,"searchMode",searchMode==null?"hybrid":searchMode,"rerank",rerank)),mode);}catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    public ObjectNode detail(long id){
        var conversation=require(id);ObjectNode result=json.createObjectNode();result.put("id",id);result.put("title",(String)conversation.get("title"));result.put("mode",(String)conversation.get("mode"));result.set("scope",parse((String)conversation.get("scope_json")));result.put("busy",conversation.get("run_id")!=null);
        var messages=result.putArray("messages");
        var rows=jdbc.queryForList("SELECT id,role,content,sources_json,status,created_at FROM knowledge_message WHERE conversation_id=? ORDER BY id",id);
        var sourceIds=new HashSet<Long>();for(var row:rows)for(var source:parse((String)row.get("sources_json")))sourceIds.add(source.path("bookId").asLong());
        var allowed=access.readableIds(sourceIds);
        for(var row:rows){
            ObjectNode message=json.createObjectNode();message.put("id",((Number)row.get("id")).longValue());message.put("role",(String)row.get("role"));message.put("content",(String)row.get("content"));message.put("status",(String)row.get("status"));
            var sources=parse((String)row.get("sources_json"));boolean visible=true;
            for(var source:sources)if(!allowed.contains(source.path("bookId").asLong())){visible=false;break;}
            message.put("redacted",!visible);if(!visible){message.put("content","相关知识已撤销访问，原回答已隐藏。");sources=json.createArrayNode();}
            message.set("sources",sources);messages.add(message);
        }
        return result;
    }
    public JsonNode send(long id,String question){
        var conversation=require(id);var visible=detail(id);var history=new ArrayList<ChatMessageDTO>();
        for(var message:visible.path("messages"))if(message.path("status").asText().equals("SUCCEEDED")&&!message.path("redacted").asBoolean()){
            String content=message.path("content").asText();history.add(new ChatMessageDTO(message.path("role").asText(),content.substring(0,Math.min(4000,content.length()))));
        }
        if(visible.path("messages").size()>1000)throw new BusinessException(400,"该对话已满，请新建对话");
        var scope=parse((String)conversation.get("scope_json"));var ids=new ArrayList<Long>();for(var value:scope.path("bookIds"))ids.add(value.asLong());
        var initialScopes=access.scopes(scope.path("publicOnly").asBoolean(),ids);
        String scopeStamp=initialScopes.toString();
        var eligible=memory.eligible(visible);
        String historyStamp=memory.fingerprint(eligible,Long.MAX_VALUE,scopeStamp);
        String run=UUID.randomUUID().toString();
        long message=new TransactionTemplate(tx).execute(status->{
            if(jdbc.update("UPDATE knowledge_conversation SET run_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND run_id IS NULL",run,id,access.actor())==0)throw new BusinessException(409,"该对话已有问题正在处理");
            return keys.insert("INSERT INTO knowledge_message(conversation_id,role,content,sources_json,status) VALUES(?,'user',?,'[]','RUNNING')",id,question);
        });
        try{
            var prepared=memory.prepare(id,eligible,scopeStamp);
            ensureContext(id,initialScopes,ids,scope.path("publicOnly").asBoolean(),historyStamp,scopeStamp);
            var query=new KnowledgeSearch.Query(question,ids,scope.path("publicOnly").asBoolean(),scope.path("searchMode").asText("hybrid"),(String)conversation.get("mode"),10,scope.path("rerank").asBoolean(true),null);
            var result=search.runConversation(query,history,prepared.memory());
            ensureContext(id,initialScopes,ids,scope.path("publicOnly").asBoolean(),historyStamp,scopeStamp);
            if(result.isObject()){
                var stats=((ObjectNode)result).withObject("/context");
                stats.put("summaryCoveredMessageId",prepared.covered());stats.put("summaryUpdated",prepared.changed());stats.put("summaryFallback",prepared.failed());
            }
            new TransactionTemplate(tx).executeWithoutResult(status->{
                if(jdbc.update("UPDATE knowledge_conversation SET run_id=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND run_id=?",id,access.actor(),run)==0)throw new BusinessException(409,"对话已删除或取消");
                jdbc.update("UPDATE knowledge_message SET status='SUCCEEDED' WHERE id=?",message);
                keys.insert("INSERT INTO knowledge_message(conversation_id,role,content,sources_json,status) VALUES(?,'assistant',?,?,'SUCCEEDED')",id,result.path("answer").asText(),result.path("sources").toString());
                memory.save(id,prepared);
            });return result;
        }catch(RuntimeException error){jdbc.update("UPDATE knowledge_message SET status='FAILED' WHERE id=?",message);jdbc.update("UPDATE knowledge_conversation SET run_id=NULL WHERE id=? AND run_id=?",id,run);throw error;}
    }
    private void ensureContext(long id,List<Map<String,Object>> scopes,List<Long> ids,boolean published,String expected,String stamp){
        access.active();
        if(!scopes.equals(access.scopes(published,ids))||!expected.equals(memory.fingerprint(memory.eligible(detail(id)),Long.MAX_VALUE,stamp)))
            throw new BusinessException(409,"对话引用权限或知识版本已变化，请重新提问");
    }
    public void delete(long id){require(id);jdbc.update("DELETE FROM knowledge_conversation WHERE id=? AND user_id=?",id,access.actor());}
}
