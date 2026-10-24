package com.bookagent.backend.model.vo;

import java.util.List;
public record CategoryVO(Long id, String name, Long parentId, Integer sortOrder, List<CategoryVO> children) {}
