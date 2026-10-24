package com.bookagent.backend.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章列表项 VO（不含正文 content——列表要快、要省流量，正文只在详情给）
 */
@Data
public class ArticleVO {

    private Long id;
    private String title;
    private String summary;
    private Long categoryId;
    private String status;
    private Integer viewCount;
    private String categoryName;
    private java.util.List<com.bookagent.backend.model.vo.TagVO> tags = java.util.List.of();

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private java.util.Date publishedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private java.util.Date createdAt;
}
