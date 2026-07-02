package com.experiment.smartlightingexp.common;

/**
 * 业务异常 — 用于 Controller / Service 层主动抛出的可预知异常，
 * 由 GlobalExceptionHandler 统一捕获并返回 Result 格式。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
