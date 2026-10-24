package com.bookagent.backend.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Date;

/**
 * 文章详情 VO（列表 VO 的全部字段 + 正文 + 更新时间）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ArticleDetailVO extends ArticleVO {

    /** 正文 Markdown */
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date updatedAt;
}
