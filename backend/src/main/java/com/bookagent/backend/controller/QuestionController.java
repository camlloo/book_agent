package com.bookagent.backend.controller;
import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.model.dto.ChatMessageDTO;
import com.bookagent.backend.service.BookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor
public class QuestionController {
    private final BookService books;
    public record Question(@NotBlank @Size(max=500) String question,@Positive Long articleId,@Positive Long bookId,@Size(max=6) List<@Valid ChatMessageDTO> history){}
    @PostMapping({"/api/blog/ask","/api/ask"}) public Result<?> ask(@Valid @RequestBody Question question){
        Long id=question.bookId()!=null?question.bookId():question.articleId()==null?null:books.legacyId(question.articleId());
        if(id==null)return Result.ok(Map.of("answer","请先选择一本已发布的 Book，再针对内容提问。","sources",List.of(),"mode","hybrid"));
        return Result.ok(books.askPublished(id,question.question(),5,question.history()));
    }
}
