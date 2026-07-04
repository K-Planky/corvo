-- V7 Elo overhaul (Phase 2). Only PvP (human-vs-human) games are rated; vs-AI is unrated practice
-- (spec §8). Past bot-derived Elo, W/L/D counters, and RatingHistory no longer reflect the rules, so
-- reset every user to a clean slate and discard the old history. Forward-only: new results follow the
-- PvP-only path in GameService.resolveOutcome. No-ops on a fresh (test) database with zero rows.
UPDATE users
   SET elo_rating   = 1200,
       games_played = 0,
       wins         = 0,
       losses       = 0,
       draws        = 0;

DELETE FROM rating_history;
