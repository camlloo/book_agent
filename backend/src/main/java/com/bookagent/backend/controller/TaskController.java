package com.bookagent.backend.controller;
import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.model.dto.*;
import com.bookagent.backend.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/tasks") @RequiredArgsConstructor @Tag(name="写作任务")
public class TaskController {
    private final TaskService tasks;
    @PostMapping @Operation(summary="创建写作或旧文润色任务") public Result<?> create(@Valid @RequestBody TaskCreateDTO dto){return Result.ok(tasks.create(dto));}
    @GetMapping @Operation(summary="分页查询写作任务") public Result<?> list(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize){return Result.ok(tasks.list(pageNum,pageSize));}
    @GetMapping("/{id}") @Operation(summary="查询任务与当前检查点") public Result<?> detail(@PathVariable long id){return Result.ok(tasks.detail(id));}
    @GetMapping("/{id}/checkpoints") @Operation(summary="查询人工确认历史") public Result<?> history(@PathVariable long id){return Result.ok(tasks.history(id));}
    @PostMapping("/{id}/review") @Operation(summary="确认当前阶段，最终结果另存为草稿") public Result<?> review(@PathVariable long id,@Valid @RequestBody TaskReviewDTO dto){return Result.ok(tasks.review(id,dto));}
    @PostMapping("/{id}/cancel") @Operation(summary="取消写作任务") public Result<?> cancel(@PathVariable long id){tasks.cancel(id);return Result.ok();}
    @PostMapping("/{id}/retry") @Operation(summary="重试失败阶段") public Result<?> retry(@PathVariable long id){tasks.retry(id);return Result.ok();}
    @DeleteMapping("/{id}") @Operation(summary="删除终态任务记录，保留已经保存的草稿") public Result<?> delete(@PathVariable long id){tasks.delete(id);return Result.ok();}
}
