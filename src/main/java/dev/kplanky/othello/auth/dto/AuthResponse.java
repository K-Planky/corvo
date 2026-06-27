package dev.kplanky.othello.auth.dto;

/** Successful auth result: the signed access token plus the public user view (spec §9/§10). */
public record AuthResponse(String token, UserResponse user) {}
