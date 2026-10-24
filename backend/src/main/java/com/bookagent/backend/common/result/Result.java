package com.bookagent.backend.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {
    private int code;
    private String message;
    private T data;
    public Result(T data) {
        this.code = 200;
        this.message = "success";
        this.data = data;
    }
    public Result(T data, boolean success, String message) {
        if (success) {
            this.code = 200;
            this.message = "success";
        } else {
            this.code = 500;
            this.message = message;
        }
        this.data = data;
    }
    public Result(int code, String message) {
        this.code = code;
        this.message = message;
        this.data = null;
    }
    public static <T> Result<T> ok() {
        return new Result<T>(null);
    }
    public static <T> Result<T> ok(T data) {
        return new Result<>(data);
    }
    public static <T> Result<T> error(String message) {
        return new Result<>(500, message);
    }
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message);
    }
}