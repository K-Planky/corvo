package dev.kplanky.othello.domain;

/**
 * Whether a game is human-versus-AI or human-versus-human (spec §5, Appendix A).
 * Phase 2 introduces {@link #HUMAN_VS_HUMAN}; Core ships only {@link #HUMAN_VS_AI}.
 */
public enum OpponentType {
    HUMAN_VS_AI,
    HUMAN_VS_HUMAN
}
