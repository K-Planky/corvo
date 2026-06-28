package dev.kplanky.othello.web;

/**
 * Minimal error body returned for handled exceptions. The client reads {@code message} to show the
 * user a meaningful reason (Spring's default {@code /error} body strips exception messages, so we
 * surface a curated one here).
 */
public record ApiError(int status, String message) {}
