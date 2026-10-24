package com.bookagent.backend.model.dto;
import jakarta.validation.constraints.*;
public record ChatMessageDTO(@NotNull @Pattern(regexp="user|assistant") String role,
                             @NotBlank @Size(max=4000) String content) {}
