package com.bookagent.backend.model.query;
import lombok.Data;
/** Canonical catalogue filters; HTTP query names remain stable. */
@Data
public class BookQuery {
    private String keyword;
    private String status;
    private String sourceKind;
    private String createdFrom;
    private String createdTo;
    private Long categoryId;
    private Long tagId;
    private Integer pageNum = 1;
    private Integer pageSize = 10;
}
