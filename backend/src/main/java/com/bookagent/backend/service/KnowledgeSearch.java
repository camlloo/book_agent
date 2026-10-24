package com.bookagent.backend.service;

import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.model.dto.ChatMessageDTO;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service @RequiredArgsConstructor
public class KnowledgeSearch {
    private final KnowledgeAccess access;private final AiClient ai;
    private final Semaphore capacity=new Semaphore(2);
    public record Query(@NotBlank @Size(max=500) String question,@Size(max=5000) List<@Positive Long> bookIds,Boolean publicOnly,
                        @Pattern(regexp="hybrid|semantic|keyword|fulltext") String searchMode,@Pattern(regexp="qa|summary|compare") String mode,
                        @Min(1) @Max(30) Integer topK,Boolean rerank,@Size(max=20) List<@Valid ChatMessageDTO> history){}
    public JsonNode run(Query query,boolean answer,List<ChatMessageDTO> history){
        return execute(query, answer, history, false, null);
    }
    /** Streams preview events to onEvent and returns the verified final result, or throws. */
    public JsonNode runConversationStream(Query query,List<ChatMessageDTO> history,JsonNode memory,java.util.function.BiConsumer<String,JsonNode> onEvent){
        access.active();
        boolean published=Boolean.TRUE.equals(query.publicOnly());var scopes=access.scopes(published,query.bookIds());
        if(query.bookIds()!=null&&!query.bookIds().isEmpty()&&scopes.size()!=new HashSet<>(query.bookIds()).size())throw new BusinessException(409,"所选 Book 尚未就绪或不在当前有权范围");
        if(!capacity.tryAcquire())throw new BusinessException(429,"知识检索繁忙，请稍后重试");
        try{
            var body=new LinkedHashMap<String,Object>();body.put("question",query.question());body.put("scopes",scopes);body.put("search_mode",query.searchMode()==null?"hybrid":query.searchMode());body.put("mode",query.mode()==null?"qa":query.mode());body.put("top_k",query.topK()==null?8:query.topK());body.put("rerank",!Boolean.FALSE.equals(query.rerank()));body.put("history",history==null?List.of():history);
            if(memory!=null&&!memory.isEmpty())body.put("memory",memory);
            var finalResult=new JsonNode[]{null};
            ai.stream("/internal/knowledge/ask/stream",body,(event,data)->{
                switch(event){
                    case "delta","reset" -> onEvent.accept(event,data);
                    case "error" -> throw new BusinessException(503,data.path("message").asText("AI 服务返回错误，请重试"));
                    case "done" -> finalResult[0]=data;
                    default -> {}
                }
            });
            access.active();
            var ids=scopes.stream().map(s->((Number)s.get("book_id")).longValue()).toList();
            if(!ids.isEmpty()&&!scopes.equals(access.scopes(published,ids)))throw new BusinessException(409,"知识权限、内容或索引在处理期间变化，请重新提问");
            if(finalResult[0]==null)throw new BusinessException(503,"回答流意外结束，请重试");
            return finalResult[0];
        }finally{capacity.release();}
    }
    public JsonNode runConversation(Query query,List<ChatMessageDTO> history,JsonNode memory){
        return execute(query,true,history,false,memory);
    }
    public JsonNode runPublished(Query query){
        if(query.bookIds()==null||query.bookIds().isEmpty())throw new BusinessException(400,"请选择用于问答的 Book 或分类");
        return execute(query,true,query.history(),true,null);
    }
    private JsonNode execute(Query query,boolean answer,List<ChatMessageDTO> history,boolean anonymousPublic,JsonNode memory){
        if(!anonymousPublic)access.active();
        boolean published=anonymousPublic||Boolean.TRUE.equals(query.publicOnly());var scopes=access.scopes(published,query.bookIds());
        if(query.bookIds()!=null&&!query.bookIds().isEmpty()&&scopes.size()!=new HashSet<>(query.bookIds()).size())throw new BusinessException(409,"所选 Book 尚未就绪或不在当前有权范围");
        if(!capacity.tryAcquire())throw new BusinessException(429,"知识检索繁忙，请稍后重试");
        try{
            var body=new LinkedHashMap<String,Object>();body.put("question",query.question());body.put("scopes",scopes);body.put("search_mode",query.searchMode()==null?"hybrid":query.searchMode());body.put("mode",query.mode()==null?"qa":query.mode());body.put("top_k",query.topK()==null?8:query.topK());body.put("rerank",!Boolean.FALSE.equals(query.rerank()));body.put("history",history==null?List.of():history);
            if(memory!=null&&!memory.isEmpty())body.put("memory",memory);
            var result=ai.call(answer?"/internal/knowledge/ask":"/internal/knowledge/search",body);
            if(!anonymousPublic)access.active();
            var ids=scopes.stream().map(s->((Number)s.get("book_id")).longValue()).toList();
            if(!ids.isEmpty()&&!scopes.equals(access.scopes(published,ids)))throw new BusinessException(409,"知识权限、内容或索引在处理期间变化，请重新提问");
            return result;
        }finally{capacity.release();}
    }
}
