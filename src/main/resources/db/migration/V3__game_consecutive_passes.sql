-- V3 (Milestone 4). Adds the consecutive-pass counter to games.
--
-- The denormalized board (board_black/board_white) plus current_turn was meant to be an O(1)
-- snapshot of engine state, but it omitted the consecutive-pass count that double-pass termination
-- (§6/§14) depends on, so recovering the live state otherwise meant inspecting the move tail.
-- Storing it makes the row a *complete* snapshot, matching the §5 "O(1) load of current state" intent.
-- Spec §5 updated to list this field.
ALTER TABLE games ADD COLUMN consecutive_passes INTEGER NOT NULL DEFAULT 0;
