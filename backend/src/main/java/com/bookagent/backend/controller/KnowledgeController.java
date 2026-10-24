package com.bookagent.backend.controller;

import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor
public class KnowledgeController {
    private final KnowledgeSearch knowledge;private final Conversations conversations;
    @PostMapping("/api/knowledge/search")public Result<?> search(@Valid @RequestBody KnowledgeSearch.Query query){return Result.ok(knowledge.run(query,false,null));}
    @PostMapping("/api/knowledge/ask")public Result<?> ask(@Valid @RequestBody KnowledgeSearch.Query query){return Result.ok(knowledge.run(query,true,query.history()));}
    public record NewConversation(@Size(max=128) String title,@Size(max=5000) List<@Positive Long> bookIds,Boolean publicOnly,@Pattern(regexp="qa|summary|compare") String mode,@Pattern(regexp="hybrid|semantic|keyword|fulltext") String searchMode,Boolean rerank){}
    @PostMapping("/api/conversations")public Result<?> create(@Valid @RequestBody NewConversation body){return Result.ok(Map.of("id",conversations.create(body.title(),body.bookIds(),Boolean.TRUE.equals(body.publicOnly()),body.mode()==null?"qa":body.mode(),body.searchMode(),!Boolean.FALSE.equals(body.rerank()))));}
    @GetMapping("/api/conversations")public Result<?> list(@RequestParam(required=false) Long bookId){return Result.ok(conversations.list(bookId));}
    @GetMapping("/api/conversations/{id}")public Result<?> detail(@PathVariable long id){return Result.ok(conversations.detail(id));}
    public record Message(@NotBlank @Size(max=500) String question){}
    @PostMapping("/api/conversations/{id}/messages")public Result<?> send(@PathVariable long id,@Valid @RequestBody Message body){return Result.ok(conversations.send(id,body.question()));}
    @PostMapping(value="/api/conversations/{id}/messages/stream",produces="text/event-stream")
    public void sendStream(@PathVariable long id,@Valid @RequestBody Message body,jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException{conversations.sendStream(id,body.question(),response);}
    @DeleteMapping("/api/conversations/{id}")public Result<?> delete(@PathVariable long id){conversations.delete(id);return Result.ok();}
}
