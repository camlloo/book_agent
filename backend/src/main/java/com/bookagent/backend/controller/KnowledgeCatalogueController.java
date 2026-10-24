package com.bookagent.backend.controller;

import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.service.KnowledgeCatalogue;
import com.bookagent.backend.service.KnowledgeSearch;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class KnowledgeCatalogueController {
    private final KnowledgeCatalogue catalogue;
    private final KnowledgeSearch search;

    @GetMapping("/api/knowledge/catalogue")
    public Result<?> personal() { return Result.ok(catalogue.list(false)); }

    @GetMapping("/api/blog/knowledge/catalogue")
    public Result<?> published() { return Result.ok(catalogue.list(true)); }

    @PostMapping("/api/blog/knowledge/ask")
    public Result<?> askPublished(@Valid @RequestBody KnowledgeSearch.Query query) {
        return Result.ok(search.runPublished(query));
    }
}
