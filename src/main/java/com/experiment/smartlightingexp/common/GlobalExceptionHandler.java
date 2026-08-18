package com.experiment.smartlightingexp.common;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器 — 确保所有异常都返回统一的 Result 格式。
 * HTTP 状态码与业务 code 保持一致，避免前端解析混乱。
 */
@Hidden
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常（主动抛出的可预知异常）
     * HTTP 状态码跟随 BusinessException.code，与 body 中 code 一致。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        log.warn("BusinessException: code={}, msg={}", e.getCode(), e.getMessage());
        HttpStatus status = resolveHttpStatus(e.getCode());
        return ResponseEntity.status(status).body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * 参数校验失败（@Valid 校验 DTO）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return Result.error(400, msg);
    }

    /**
     * 参数校验失败（@RequestParam 等单个参数校验）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraint(ConstraintViolationException e) {
        return Result.error(400, e.getMessage());
    }

    /**
     * 静态资源缺失（如 favicon.ico），不记录错误日志。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return Result.error(404, "资源不存在");
    }

    /**
     * 未捕获的未知异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnknown(Exception e) {
        log.error("Unhandled exception: ", e);
        return Result.error(500, "服务器内部错误: " + e.getMessage());
    }

    /**
     * 将业务 code 映射为 HTTP 状态码。
     * 4xx → 客户端错误，5xx → 服务端错误，其余 → 200。
     */
    private static HttpStatus resolveHttpStatus(int code) {
        if (code >= 400 && code < 500) return HttpStatus.valueOf(code);
        if (code >= 500) return HttpStatus.valueOf(code);
        return HttpStatus.OK;
    }
}
