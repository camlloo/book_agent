package com.bookagent.backend.controller;
import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.model.query.ArticleQuery;
import com.bookagent.backend.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/blog") @RequiredArgsConstructor @Tag(name="公开博客")
public class BlogController {
    private final BookService books;
    private final TaxonomyService taxonomy;
    private final LegacyBookIds ids;
    @GetMapping("/articles") @Operation(summary="分页浏览已发布文章")
    public Result<?> list(ArticleQuery query){return Result.ok(ids.presentPage(books.list(query,true)));}
    @GetMapping("/articles/{id}") @Operation(summary="阅读已发布文章并增加浏览量")
    public Result<?> detail(@PathVariable long id) throws Exception{
        return Result.ok(ids.present(books.readPublished(books.legacyId(id)).book()));
    }
    @GetMapping("/categories") @Operation(summary="浏览分类树") public Result<?> categories(){return Result.ok(taxonomy.categories());}
    @GetMapping("/articles/{id}/resolve") public Result<?> resolve(@PathVariable long id){long target=books.legacyId(id);books.requirePublished(target);return Result.ok(java.util.Map.of("bookId",target));}
    @GetMapping("/tags") @Operation(summary="浏览标签") public Result<?> tags(){return Result.ok(taxonomy.tags());}
}
