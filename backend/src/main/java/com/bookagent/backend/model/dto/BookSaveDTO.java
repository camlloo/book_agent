package com.bookagent.backend.model.dto;

import jakarta.validation.constraints.*;

public record BookSaveDTO(
    @NotBlank @Size(max = 128) String title,
    @Size(max = 500) String summary,
    @NotNull @Size(max = 1000000) String content,
    @Positive Long revision,
    @Positive Long categoryId,
    @Size(max=20) java.util.List<@Positive Long> tagIds
) {
    public BookSaveDTO(String title,String summary,String content,Long revision){this(title,summary,content,revision,null,java.util.List.of());}
}
