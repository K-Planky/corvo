-- V4 (Milestone 6). Records the bot's fixed Elo rating on each vs-AI game.
--
-- Difficulty already maps to a fixed rating (EASY=1000, MEDIUM=1500, HARD=1800), but storing the
-- rating the game was actually played at — rather than re-deriving it from bot_difficulty later —
-- keeps M7's Elo math simple and keeps historical games correct if the difficulty→rating map ever
-- changes. NULL for human-vs-human games (no bot). Spec §5/§8.
ALTER TABLE games ADD COLUMN bot_rating INTEGER;
