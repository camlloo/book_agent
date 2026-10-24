package com.bookagent.backend.controller;

import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.model.dto.BookSaveDTO;
import com.bookagent.backend.model.dto.ChatMessageDTO;
import com.bookagent.backend.service.BookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController @RequestMapping("/api/books") @RequiredArgsConstructor @Tag(name = "Book 知识库")
public class BookController {
    private final BookService books;
    public record Question(@NotBlank @Size(max = 500) String question, @Min(1) @Max(10) Integer topK,
        @Size(max=6) java.util.List<@Valid ChatMessageDTO> history) {}
    @GetMapping @Operation(summary = "分页查询 Book 及索引状态")
    public Result<?> list(com.bookagent.backend.model.query.BookQuery query) { return Result.ok(books.list(query,false)); }
    @PostMapping @Operation(summary = "创建 Book 并自动向量化正文")
    public Result<?> create(@Valid @RequestBody BookSaveDTO dto) { return Result.ok(books.create(dto)); }
    @GetMapping("/{id}") @Operation(summary = "读取 Book 正文和附件")
    public Result<?> detail(@PathVariable long id) { return Result.ok(books.detail(id)); }
    @GetMapping("/{id}/read") @Operation(summary = "读取私有 Book 原文，上传文件按页解析用于阅读")
    public Result<?> read(@PathVariable long id) throws Exception { return Result.ok(books.read(id)); }
    @PutMapping("/{id}/publish") public Result<?> publish(@PathVariable long id){return Result.ok(books.publish(id));}
    @PutMapping("/{id}/offline") public Result<?> offline(@PathVariable long id){return Result.ok(books.offline(id));}
    @PutMapping("/{id}") @Operation(summary = "按版本保存 Book 并更新索引")
    public Result<?> update(@PathVariable long id, @Valid @RequestBody BookSaveDTO dto) { return Result.ok(books.update(id, dto)); }
    @DeleteMapping("/{id}") @Operation(summary = "删除 Book 并清理附件和向量")
    public Result<?> delete(@PathVariable long id) { books.delete(id); return Result.ok(); }
    @PostMapping("/upload") @Operation(summary = "上传文件直接创建 Book 并向量化")
    public Result<?> upload(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String title, @RequestParam(required = false) String summary,@RequestParam(required=false) Long categoryId,@RequestParam(required=false) java.util.List<Long> tagIds) throws Exception { return Result.ok(books.createUpload(title, summary, file,categoryId,tagIds)); }
    @PostMapping("/{id}/file") @Operation(summary = "按版本替换文件型 Book 的原文件并更新索引")
    public Result<?> replace(@PathVariable long id, @RequestParam long revision, @RequestParam("file") MultipartFile file) throws Exception { return Result.ok(books.replaceUpload(id, revision, file)); }
    @DeleteMapping("/{id}/files/{fileId}") @Operation(summary = "移除资料并更新索引")
    public Result<?> remove(@PathVariable long id, @PathVariable long fileId) { return Result.ok(books.removeFile(id, fileId)); }
    @GetMapping("/{id}/files/{fileId}/download") @Operation(summary = "获取两分钟私有附件下载链接")
    public Result<?> download(@PathVariable long id, @PathVariable long fileId) throws Exception { return Result.ok(Map.of("url", books.download(id, fileId), "expiresIn", 120)); }
    @PostMapping("/{id}/reindex") @Operation(summary = "重试或重新建立 Book 索引")
    public Result<?> reindex(@PathVariable long id,@RequestParam(defaultValue="semantic") String chunking) { books.reindex(id,chunking); return Result.ok(); }
    @PostMapping("/{id}/ask") @Operation(summary = "在当前 Book 正文与资料中检索并回答")
    public Result<?> ask(@PathVariable long id, @Valid @RequestBody Question dto) { return Result.ok(books.ask(id, dto.question(), dto.topK() == null ? 5 : dto.topK(),dto.history())); }
}
