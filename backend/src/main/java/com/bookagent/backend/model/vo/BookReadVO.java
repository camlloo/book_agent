package com.bookagent.backend.model.vo;
import com.fasterxml.jackson.databind.JsonNode;
/** Original reading response shared by canonical and compatibility endpoints. */
public record BookReadVO(BookVO book, JsonNode sections) {}
