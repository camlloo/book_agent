package com.bookagent.backend.model.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
@Data
public class LoginDTO {
    /**
     * 登录名
     */
    @NotBlank
    private String username;

    /**
     * BCrypt 散列（60 字符）
     */
    @NotBlank
    @Size(min = 6,max = 64)
    private String password;
}
