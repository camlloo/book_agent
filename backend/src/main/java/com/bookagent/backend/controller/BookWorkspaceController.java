package com.bookagent.backend.controller;

import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/books/{id}") @RequiredArgsConstructor
public class BookWorkspaceController {
    private final JdbcTemplate jdbc;private final BookVersions versions;private final BookService books;private final KnowledgeAccess access;
    @GetMapping("/versions") public Result<?> versions(@PathVariable long id){return Result.ok(versions.list(id));}
    @GetMapping("/versions/{revision}") public Result<?> version(@PathVariable long id,@PathVariable long revision){return Result.ok(versions.snapshot(id,revision));}
    public record Restore(@Positive long expectedRevision){}
    @PostMapping("/versions/{revision}/restore") public Result<?> restore(@PathVariable long id,@PathVariable long revision,@Valid @RequestBody Restore form){return Result.ok(books.restore(id,revision,form.expectedRevision()));}
    @GetMapping("/shares") public Result<?> shares(@PathVariable long id){return Result.ok(jdbc.queryForList("SELECT s.user_id AS userId,u.username,u.nickname,s.access_role AS accessRole FROM book_share s JOIN `user` u ON u.id=s.user_id WHERE s.book_id=?",id));}
    public record Share(@NotBlank String username,@Pattern(regexp="VIEWER|EDITOR") String accessRole){}
    @PutMapping("/shares") @Transactional public Result<?> share(@PathVariable long id,@Valid @RequestBody Share form){
        access.book(id,"OWNER");
        var users=jdbc.queryForList("SELECT id FROM `user` WHERE username=? AND status=1",form.username());
        if(users.isEmpty())throw new BusinessException(400,"该用户不存在或已停用");
        long user=((Number)users.getFirst().get("id")).longValue();
        if(jdbc.queryForObject("SELECT user_id FROM book_owner WHERE book_id=?",Long.class,id)==user)throw new BusinessException(400,"无需向所有者分享");
        jdbc.update("DELETE FROM book_share WHERE book_id=? AND user_id=?",id,user);
        jdbc.update("INSERT INTO book_share(book_id,user_id,access_role) VALUES(?,?,?)",id,user,form.accessRole()==null?"VIEWER":form.accessRole());return Result.ok();
    }
    @DeleteMapping("/shares/{userId}") public Result<?> revoke(@PathVariable long id,@PathVariable long userId){access.book(id,"OWNER");jdbc.update("DELETE FROM book_share WHERE book_id=? AND user_id=?",id,userId);return Result.ok();}
    @GetMapping("/origin") public Result<?> origin(@PathVariable long id){return Result.ok(jdbc.queryForList("SELECT kind,source_url AS sourceUrl,metadata_json AS metadata,fetched_at AS fetchedAt FROM knowledge_origin WHERE book_id=?",id));}
}
