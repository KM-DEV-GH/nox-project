package com.nox.adapter.in.web.response;

/**
 * Standard success envelope (constitution Principle V).
 *
 * @param data the payload
 * @param meta request metadata
 * @param <T> payload type
 */
public record ApiResponse<T>(T data, Meta meta) {}
