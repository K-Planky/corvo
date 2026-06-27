-- V2 core schema (Milestone 2). Implements spec §5.
--
-- The whole schema (tables + constraints + indexes) is authored here in one migration because
-- Flyway migrations are immutable once applied (CLAUDE.md: never edit an applied migration).
-- Later M2 tasks (bitboard round-trip, repositories/uniqueness, migration-applies check) test
-- against this schema rather than each adding a migration.
--
-- Entities map to these tables with spring.jpa.hibernate.ddl-auto=validate. Enums are stored as
-- VARCHAR (Hibernate EnumType.STRING). Bitboards are signed BIGINT — bit 63 (h8) round-trips as a
-- negative value, which is expected (§5 signed-storage caveat).

-- "user" is reserved in Postgres, so the table is "users".
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    username      VARCHAR(255) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    elo_rating    INTEGER     NOT NULL DEFAULT 1200,
    games_played  INTEGER     NOT NULL DEFAULT 0,
    wins          INTEGER     NOT NULL DEFAULT 0,
    losses        INTEGER     NOT NULL DEFAULT 0,
    draws         INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email    UNIQUE (email)
);

CREATE TABLE games (
    id              UUID PRIMARY KEY,
    black_player_id UUID        REFERENCES users (id),
    white_player_id UUID        REFERENCES users (id),
    opponent_type   VARCHAR(32) NOT NULL,
    bot_side        VARCHAR(8)  NOT NULL,
    bot_difficulty  VARCHAR(16),
    board_black     BIGINT      NOT NULL,
    board_white     BIGINT      NOT NULL,
    current_turn    VARCHAR(8)  NOT NULL,
    status          VARCHAR(16) NOT NULL,
    winner_id       UUID        REFERENCES users (id),
    move_count      INTEGER     NOT NULL,
    version         BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE moves (
    id           UUID PRIMARY KEY,
    game_id      UUID        NOT NULL REFERENCES games (id),
    move_number  INTEGER     NOT NULL,
    player       VARCHAR(8)  NOT NULL,
    position     SMALLINT,
    is_pass      BOOLEAN     NOT NULL,
    flipped_mask BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    -- moveNumber is unique per game; this index also serves the ordered-history query
    -- (WHERE game_id = ? ORDER BY move_number) because game_id is its leading column, so no
    -- separate index on game_id is needed.
    CONSTRAINT uq_moves_game_move_number UNIQUE (game_id, move_number)
);

CREATE TABLE rating_history (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id),
    game_id    UUID        NOT NULL REFERENCES games (id),
    old_rating INTEGER     NOT NULL,
    new_rating INTEGER     NOT NULL,
    delta      INTEGER     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
