package com.bookagent.backend.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Date;
import java.util.List;

public record BookVO(Long id, String title, String summary, @JsonInclude(JsonInclude.Include.NON_NULL) String content, String sourceMode, long revision,
                     long indexedRevision, String indexStatus, String indexError, int chunkCount,
                     Date createdAt, Date updatedAt, @JsonInclude(JsonInclude.Include.NON_NULL) List<FileVO> files,
                     String status,Long categoryId,String categoryName,List<TagVO> tags,int viewCount,Date publishedAt, Long ownerId, String accessRole,String originKind,String sourceUrl) {
    public record FileVO(Long id, String originalName, String extension, long size, String sha256, Date createdAt) {}
    public BookVO withTags(List<TagVO> value){return new BookVO(id,title,summary,content,sourceMode,revision,indexedRevision,indexStatus,indexError,chunkCount,createdAt,updatedAt,files,status,categoryId,categoryName,value,viewCount,publishedAt,ownerId,accessRole,originKind,sourceUrl);}
}
