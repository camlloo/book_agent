package com.bookagent.backend.controller;

import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequiredArgsConstructor
public class CollectionController {
    private final KnowledgeImports imports;private final KnowledgeAccess access;private final BookService books;
    public record Collect(@Pattern(regexp="WEB|REPO") String kind,@NotBlank @Size(max=2000) String url,@Size(max=128) String branch,@Size(max=255) String path,Boolean organize){}
    @GetMapping("/api/imports")public Result<?> list(){return Result.ok(imports.list());}
    @PostMapping("/api/imports")public Result<?> collect(@Valid @RequestBody Collect body){return Result.ok(Map.of("id",imports.queue(body.kind()==null?"WEB":body.kind(),body.url(),body.branch(),body.path(),!Boolean.FALSE.equals(body.organize()))));}
    @PostMapping("/api/imports/{id}/retry")public Result<?> retry(@PathVariable long id){imports.retry(id);return Result.ok();}
    @PostMapping("/api/imports/{id}/cancel")public Result<?> cancel(@PathVariable long id){imports.cancel(id);return Result.ok();}
    @PostMapping("/api/books/{id}/suggest")public Result<?> suggest(@PathVariable long id){long revision=books.detail(id).revision();var result=imports.suggest(id);access.book(id,"READ");if(books.detail(id).revision()!=revision)throw new com.bookagent.backend.common.exception.BusinessException(409,"内容已更新，请重试");return Result.ok(Map.of("revision",revision,"suggestion",result));}
    public record Organize(@Positive long revision,@NotNull JsonNode suggestion){}
    @PostMapping("/api/books/{id}/organize")public Result<?> organize(@PathVariable long id,@Valid @RequestBody Organize body){return Result.ok(imports.organize(id,body.revision(),body.suggestion()));}
}
