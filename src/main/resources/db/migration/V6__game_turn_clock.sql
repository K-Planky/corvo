-- V6 turn clock (Milestone 10). Implements spec §15.
--
-- Per-player time bank for PvP games plus the timestamp the current side-to-move's clock started
-- ticking. All three columns are nullable and populated only for HUMAN_VS_HUMAN games: vs-AI games
-- (and any game created before this migration) leave them NULL and are never swept for timeout,
-- a human playing an instant bot is not on a clock. The banks are stored in milliseconds; the
-- side-to-move's live remaining is bank - (now - turn_started_at), the idle side's is frozen at its
-- stored bank. Enforcement is server-side (a scheduled sweep), keeping time authoritative like the
-- rest of the game state.
ALTER TABLE games
    ADD COLUMN black_time_ms    BIGINT,
    ADD COLUMN white_time_ms    BIGINT,
    ADD COLUMN turn_started_at  TIMESTAMPTZ;
