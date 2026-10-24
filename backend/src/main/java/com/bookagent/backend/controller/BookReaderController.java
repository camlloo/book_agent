package com.bookagent.backend.controller;
import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.service.BookService;
import com.bookagent.backend.model.query.BookQuery;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/blog/books") @RequiredArgsConstructor
public class BookReaderController {
    private final BookService books;
    @GetMapping public Result<?> list(BookQuery query){return Result.ok(books.list(query,true));}
    @GetMapping("/{id}/read") public Result<?> read(@PathVariable long id) throws Exception{return Result.ok(books.readPublished(id));}
    @GetMapping("/{id}") public Result<?> detail(@PathVariable long id){return Result.ok(books.publishedDetail(id));}
    @GetMapping("/{id}/files/{fileId}/download") public Result<?> download(@PathVariable long id,@PathVariable long fileId) throws Exception{return Result.ok(Map.of("url",books.downloadPublished(id,fileId),"expiresIn",120));}
    @PostMapping("/{id}/ask") public Result<?> ask(@PathVariable long id,@Valid @RequestBody BookController.Question question){return Result.ok(books.askPublished(id,question.question(),question.topK()==null?5:question.topK(),question.history()));}
}
