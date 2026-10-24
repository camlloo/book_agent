package com.bookagent.backend.controller;
import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.model.dto.*;
import com.bookagent.backend.model.query.ArticleQuery;
import com.bookagent.backend.service.BookService;
import com.bookagent.backend.service.LegacyBookIds;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Compatibility routes; all writes now target the canonical Book. */
@RestController @RequestMapping("/api/articles") @RequiredArgsConstructor
public class ArticleController {
    private final BookService books;
    private final LegacyBookIds ids;
    private BookSaveDTO convert(ArticleSaveDTO dto,Long revision){return new BookSaveDTO(dto.getTitle(),dto.getSummary(),dto.getContent(),revision,dto.getCategoryId(),dto.getTagIds());}
    @PostMapping public Result<?> create(@Valid @RequestBody ArticleSaveDTO dto){return Result.ok(ids.present(books.create(convert(dto,null))));}
    @GetMapping public Result<?> list(ArticleQuery query){return Result.ok(ids.presentPage(books.list(query,false)));}
    @GetMapping("/{id}") public Result<?> detail(@PathVariable long id){return Result.ok(ids.present(books.detail(books.legacyId(id))));}
    @PutMapping("/{id}") public Result<?> update(@PathVariable long id,@Valid @RequestBody ArticleSaveDTO dto){long target=books.legacyId(id);return Result.ok(ids.present(books.update(target,convert(dto,books.detail(target).revision()))));}
    @DeleteMapping("/{id}") public Result<?> delete(@PathVariable long id){books.delete(books.legacyId(id));return Result.ok();}
    @PutMapping("/{id}/publish") public Result<?> publish(@PathVariable long id){return Result.ok(ids.present(books.publish(books.legacyId(id))));}
    @PutMapping("/{id}/offline") public Result<?> offline(@PathVariable long id){return Result.ok(ids.present(books.offline(books.legacyId(id))));}
}
