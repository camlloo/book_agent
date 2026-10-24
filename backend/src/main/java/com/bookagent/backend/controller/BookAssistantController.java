package com.bookagent.backend.controller;

import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.service.AssistantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/books/{id}/assistant") @RequiredArgsConstructor
public class BookAssistantController {
    private final AssistantService assistant;
    public record Request(@NotBlank @Pattern(regexp="organize|polish|review|explain|related|duplicates") String operation,
                         @Size(max=2000) String instructions,@Size(max=40) String level,@Size(max=100) List<@Positive Long> bookIds) {}
    public record Apply(@Positive long expectedRevision,@NotBlank @Pattern(regexp="organize|polish") String operation,
                        @Size(max=128) String title,@Size(max=500) String summary,@Size(max=1000000) String content,
                        @Size(max=64) String category,@Size(max=20) List<@Size(max=64) String> tags) {}
    @PostMapping public Result<?> run(@PathVariable long id,@Valid @RequestBody Request form){return Result.ok(assistant.bookAction(id,new AssistantService.BookAction(form.operation(),form.instructions(),form.level(),form.bookIds())));}
    @PostMapping("/apply") public Result<?> apply(@PathVariable long id,@Valid @RequestBody Apply form){return Result.ok(assistant.apply(id,new AssistantService.Apply(form.expectedRevision(),form.operation(),form.title(),form.summary(),form.content(),form.category(),form.tags())));}
}
