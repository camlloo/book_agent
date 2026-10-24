package com.bookagent.backend.controller;

import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.service.LearningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/learning/plans")
@RequiredArgsConstructor
public class LearningController {
    private final LearningService learning;

    public record Create(@NotBlank @Size(max=1000) String goal, @NotEmpty @Size(max=50) List<@Positive Long> bookIds,
                         @NotBlank @Pattern(regexp="初学者|有一些基础|可以独立实践|熟练") String currentLevel,
                         @Min(1) @Max(5) int selfConfidence, @Min(1) @Max(8) int durationWeeks,
                         @Min(1) @Max(7) int sessionsPerWeek, @Min(10) @Max(180) int minutesPerSession,
                         LocalDate startDate, @Size(max=1000) String knownTopics) {}
    public record CheckIn(@NotBlank @Pattern(regexp="PLANNED|COMPLETED|NEEDS_REVIEW|SKIPPED") String status,
                          @Min(1) @Max(5) Integer confidence, @Size(max=2000) String reflection) {}

    @PostMapping public Result<?> create(@Valid @RequestBody Create form) {
        return Result.ok(learning.create(new LearningService.CreateRequest(form.goal(), form.bookIds(), form.currentLevel(),
            form.selfConfidence(), form.durationWeeks(), form.sessionsPerWeek(), form.minutesPerSession(), form.startDate(), form.knownTopics())));
    }
    @GetMapping public Result<?> list() { return Result.ok(learning.list()); }
    @GetMapping("/{id}") public Result<?> detail(@PathVariable @Positive long id) { return Result.ok(learning.detail(id)); }
    @PutMapping("/{id}/sessions/{sessionId}") public Result<?> checkIn(@PathVariable @Positive long id, @PathVariable @Positive long sessionId,
                                                                         @Valid @RequestBody CheckIn form) {
        return Result.ok(learning.checkIn(id, sessionId, new LearningService.CheckIn(form.status(), form.confidence(), form.reflection())));
    }
    @PostMapping("/{id}/report") public Result<?> report(@PathVariable @Positive long id) { return Result.ok(learning.report(id)); }
    @DeleteMapping("/{id}") public Result<?> delete(@PathVariable @Positive long id) { learning.delete(id); return Result.ok(); }
}
