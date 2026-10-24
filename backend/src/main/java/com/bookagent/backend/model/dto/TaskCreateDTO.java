package com.bookagent.backend.model.dto;
import jakarta.validation.constraints.*;
public record TaskCreateDTO(@NotBlank @Size(max=128) String topic,@Size(max=2000) String instructions,@Positive Long sourceArticleId,@Positive Long bookId) {}
