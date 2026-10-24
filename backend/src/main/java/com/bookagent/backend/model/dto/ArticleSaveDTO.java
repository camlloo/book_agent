package com.bookagent.backend.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 文章新增/编辑入参（共用；id 走路径参数，状态不在这里——由发布/下线接口管）
 */
@Data
public class ArticleSaveDTO {

    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题最长 128 字")
    private String title;

    /** 摘要可空；空则前端展示时截取正文开头 */
    @Size(max = 500, message = "摘要最长 500 字")
    private String summary;

    @NotBlank(message = "正文不能为空")
    private String content;

    /** 分类可空（S4 接入） */
    private Long categoryId;

    @Size(max = 20, message = "每篇文章最多 20 个标签")
    private java.util.List<@jakarta.validation.constraints.Positive Long> tagIds = java.util.List.of();
}
