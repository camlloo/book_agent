package com.bookagent.backend.controller;

import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.service.AssistantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/assistant") @RequiredArgsConstructor
public class AssistantController {
    private final AssistantService assistant;

    public record Memory(@NotNull @Size(max=4000) String preferences) {}
    public record PlanStep(@NotBlank @Pattern(regexp="search|summarize|compare|draft|proofread") String tool,
                           @NotBlank @Size(max=100) String title,@NotBlank @Size(max=500) String instruction) {}
    public record Goal(@NotBlank @Size(max=1000) String goal,@NotEmpty @Size(max=50) List<@Positive Long> bookIds,
                       @Size(max=6) List<@Valid PlanStep> steps) {}
    public record StepInstruction(@Pattern(regexp="search|summarize|compare|draft|proofread") String tool,
                                  @Size(max=100) String title,@NotBlank @Size(max=500) String instruction) {}
    public record NewStep(@NotBlank @Pattern(regexp="search|summarize|compare|draft|proofread") String tool,
                          @NotBlank @Size(max=100) String title,@NotBlank @Size(max=500) String instruction,@Positive Long afterStepId) {}
    public record StepPosition(@Min(1) @Max(6) int position) {}
    public record Draft(@NotBlank @Size(max=128) String title,@Size(max=500) String summary,
                        @NotBlank @Size(max=1000000) String content,@NotEmpty @Size(max=50) List<@Positive Long> sourceBookIds) {}
    public record Merge(@NotBlank @Size(max=128) String title,@NotBlank @Size(max=1000) String instructions,
                        @NotEmpty @Size(max=10) List<@Positive Long> bookIds) {}

    @GetMapping("/memory") public Result<?> memory(){return Result.ok(assistant.memory());}
    @PutMapping("/memory") public Result<?> saveMemory(@Valid @RequestBody Memory form){assistant.saveMemory(form.preferences());return Result.ok();}
    @GetMapping("/recommendations") public Result<?> recommendations(){return Result.ok(assistant.recommendations());}
    @PostMapping("/plans") public Result<?> create(@Valid @RequestBody Goal form){return Result.ok(assistant.createPlan(new AssistantService.PlanRequest(form.goal(),form.bookIds(),
        form.steps()==null?null:form.steps().stream().map(s->new AssistantService.PlanStep(s.tool(),s.title(),s.instruction())).toList())));}
    @GetMapping("/plans") public Result<?> plans(){return Result.ok(assistant.plans());}
    @GetMapping("/plans/{id}") public Result<?> plan(@PathVariable long id){return Result.ok(assistant.plan(id));}
    @PostMapping("/plans/{id}/steps") public Result<?> addStep(@PathVariable long id,@Valid @RequestBody NewStep form){return Result.ok(assistant.addPlanStep(id,new AssistantService.NewStep(form.tool(),form.title(),form.instruction(),form.afterStepId())));}
    @PutMapping("/plans/{id}/steps/{stepId}") public Result<?> updateStep(@PathVariable long id,@PathVariable long stepId,@Valid @RequestBody StepInstruction form){return Result.ok(assistant.updateStepInstruction(id,stepId,new AssistantService.StepInstruction(form.tool(),form.title(),form.instruction())));}
    @PutMapping("/plans/{id}/steps/{stepId}/position") public Result<?> moveStep(@PathVariable long id,@PathVariable long stepId,@Valid @RequestBody StepPosition form){return Result.ok(assistant.movePlanStep(id,stepId,new AssistantService.StepPosition(form.position())));}
    @DeleteMapping("/plans/{id}/steps/{stepId}") public Result<?> removeStep(@PathVariable long id,@PathVariable long stepId){return Result.ok(assistant.removePlanStep(id,stepId));}
    @PostMapping("/plans/{id}/steps/{stepId}/reset") public Result<?> resetStep(@PathVariable long id,@PathVariable long stepId){return Result.ok(assistant.resetPlanFromStep(id,stepId));}
    @PostMapping("/plans/{id}/steps/{stepId}/run") public Result<?> run(@PathVariable long id,@PathVariable long stepId){return Result.ok(assistant.executeStep(id,stepId));}
    @DeleteMapping("/plans/{id}") public Result<?> delete(@PathVariable long id){assistant.deletePlan(id);return Result.ok();}
    @PostMapping("/drafts") public Result<?> draft(@Valid @RequestBody Draft form){return Result.ok(assistant.createDraft(new AssistantService.NewDraft(form.title(),form.summary(),form.content(),form.sourceBookIds())));}
    @PostMapping("/merge/preview") public Result<?> merge(@Valid @RequestBody Merge form){return Result.ok(assistant.mergePreview(new AssistantService.MergeRequest(form.title(),form.instructions(),form.bookIds())));}
}
