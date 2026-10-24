package com.bookagent.backend.controller;
import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.model.dto.TaxonomyDTO;
import com.bookagent.backend.service.TaxonomyService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api") @RequiredArgsConstructor @Tag(name="分类标签")
public class TaxonomyController {
    private final TaxonomyService service;
    @GetMapping("/categories") @Operation(summary="查询分类树") public Result<?> categories(){return Result.ok(service.categories());}
    @PostMapping("/categories") @Operation(summary="新增分类") public Result<?> addCategory(@Valid @RequestBody TaxonomyDTO dto){return Result.ok(service.saveCategory(null,dto));}
    @PutMapping("/categories/{id}") @Operation(summary="修改分类") public Result<?> editCategory(@PathVariable Long id,@Valid @RequestBody TaxonomyDTO dto){return Result.ok(service.saveCategory(id,dto));}
    @DeleteMapping("/categories/{id}") @Operation(summary="删除分类") public Result<?> deleteCategory(@PathVariable long id){service.deleteCategory(id);return Result.ok();}
    @GetMapping("/tags") @Operation(summary="查询标签") public Result<?> tags(){return Result.ok(service.tags());}
    @PostMapping("/tags") @Operation(summary="新增标签") public Result<?> addTag(@Valid @RequestBody TaxonomyDTO dto){return Result.ok(service.saveTag(null,dto));}
    @PutMapping("/tags/{id}") @Operation(summary="修改标签") public Result<?> editTag(@PathVariable Long id,@Valid @RequestBody TaxonomyDTO dto){return Result.ok(service.saveTag(id,dto));}
    @DeleteMapping("/tags/{id}") @Operation(summary="删除标签") public Result<?> deleteTag(@PathVariable long id){service.deleteTag(id);return Result.ok();}
}
