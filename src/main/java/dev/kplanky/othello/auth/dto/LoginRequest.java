package dev.kplanky.othello.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Login payload for {@code POST /api/auth/login} (spec §10). */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
