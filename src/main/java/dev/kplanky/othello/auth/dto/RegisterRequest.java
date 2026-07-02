package dev.kplanky.othello.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Registration payload for {@code POST /api/auth/register} (spec §9/§10). */
public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 30) String username,
        @NotBlank @Size(min = 8, max = 100) String password) {}
