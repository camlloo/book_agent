package com.bookagent.backend.model.dto;
import jakarta.validation.constraints.*;
public record TaskReviewDTO(@NotBlank String runId,@NotBlank @Size(max=200000) String content) {}
