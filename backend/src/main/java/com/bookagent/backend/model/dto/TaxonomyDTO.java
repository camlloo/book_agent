package com.bookagent.backend.model.dto;

import jakarta.validation.constraints.*;
public record TaxonomyDTO(@NotBlank @Size(max = 64) String name, @Positive Long parentId, @Min(0) Integer sortOrder) {}
