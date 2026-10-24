package com.bookagent.backend.controller;
import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.model.dto.TaskCallbackDTO;
import com.bookagent.backend.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/internal/tasks") @RequiredArgsConstructor @Tag(name="内部回调")
public class InternalController {
    private final TaskService tasks;
    @PostMapping("/{id}/result") @Operation(summary="提交阶段结果，过期回调不能覆盖任务") public Result<?> result(@PathVariable long id,@Valid @RequestBody TaskCallbackDTO dto){tasks.callback(id,dto);return Result.ok();}
}
