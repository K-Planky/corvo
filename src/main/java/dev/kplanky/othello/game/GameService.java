package dev.kplanky.othello.game;

import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.Move;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.config.BotProperties;
import dev.kplanky.othello.config.PvpClockProperties;
import dev.kplanky.othello.domain.RatingHistory;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.Player;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.game.dto.GameStateResponse;
import dev.kplanky.othello.game.dto.MoveResponse;
import dev.kplanky.othello.rating.Elo;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Game orchestration (spec §4/§9/§11). Owns the mapping between the engine and the persisted
 * {@link Game} and is the single place that mutates a game's position. Move application validates
 * legality through the engine, persists the new board + {@link Move} row + {@code moveCount}, and on
 * terminal resolves the outcome — all in one transaction, so the board and move history can never
 * diverge (§11). Participant/turn authorization (M4.5) and the AI auto-reply (M4.3) wrap this core.
 */
@Service
public class GameService {

    private final GameRepository games;
    private final MoveRepository moves;
    private final UserRepository users;
    private final RatingHistoryRepository ratings;
    private final GameRules<OthelloState, OthelloMove> rules;
    private final BotEngine botEngine;
    private final GameStateMapper mapper;
    private final ApplicationEventPublisher events;
    private final BotProperties botProperties;
    private final PvpClockProperties clockProperties;

    public GameService(
            GameRepository games,
            MoveRepository moves,
            UserRepository users,
            RatingHistoryRepository ratings,
            GameRules<OthelloState, OthelloMove> rules,
            BotEngine botEngine,
            GameStateMapper mapper,
            ApplicationEventPublisher events,
            BotProperties botProperties,
            PvpClockProperties clockProperties) {
        this.games = games;
        this.moves = moves;
        this.users = users;
        this.ratings = ratings;
        this.rules = rules;
        this.botEngine = botEngine;
        this.mapper = mapper;
        this.events = events;
        this.botProperties = botProperties;
        this.clockProperties = clockProperties;
    }

    /**
     * Creates a {@code HUMAN_VS_AI} game for {@code userId}. The human takes the non-bot side; the
     * bot side keeps a {@code null} player id (bots have no {@code User} row, §5). The board starts at
     * the engine's initial position. When the bot plays Black it moves first, so its opening move is
     * applied here through the shared move pipeline — recorded as move 1 — leaving the freshly created
     * game already on the human's (White) turn.
     */
    @Transactional
    public GameStateResponse createVsAiGame(UUID userId, BotDifficulty difficulty, BotSide botSide) {
        if (botSide != BotSide.BLACK && botSide != BotSide.WHITE) {
            throw new InvalidGameRequestException("botSide must be BLACK or WHITE for a vs-AI game");
        }
        if (difficulty == null) {
            throw new InvalidGameRequestException("difficulty is required");
        }

        Game game = new Game();
        game.setOpponentType(OpponentType.HUMAN_VS_AI);
        game.setBotSide(botSide);
        game.setBotDifficulty(difficulty);
        // Capture the bot's fixed rating now so M7's Elo math reads it off the game (§8).
        game.setBotRating(botEngine.ratingFor(difficulty));
        if (botSide == BotSide.BLACK) {
            game.setWhitePlayerId(userId);
        } else {
            game.setBlackPlayerId(userId);
        }
        mapper.writeState(game, rules.initialState());
        game = games.save(game);

        if (botSide == BotSide.BLACK) {
            // Black moves first and here Black is the bot: play its opening move through the same
            // pipeline so it's recorded as move 1 (keeping the board consistent with the replayable
            // move list, §5) and the game lands on the human's (White) turn.
            applyToGame(game, botEngine.searchFor(difficulty).bestMove(mapper.toState(game)));
        }

        return toResponse(game, userId);
    }

    /**
     * Creates a {@code HUMAN_VS_HUMAN} game for a matched pair (spec §9/§15, M9.1). Both sides carry a
     * player id; there is no bot ({@code botSide} stays {@code NONE}, {@code botDifficulty}/
     * {@code botRating} null). The two players are assigned to Black/White at random — Black moves
     * first, so a fixed order would systematically favour one queue position. The board starts at the
     * engine's initial position (Black to move) and no opening move is applied (both sides are human).
     * Returns the new game id; the caller reads each player's oriented view via {@link #getGameState}.
     */
    @Transactional
    public UUID createPvpGame(UUID playerA, UUID playerB) {
        Game game = new Game();
        game.setOpponentType(OpponentType.HUMAN_VS_HUMAN);
        // Random side assignment for fairness (Black moves first).
        boolean aIsBlack = ThreadLocalRandom.current().nextBoolean();
        game.setBlackPlayerId(aIsBlack ? playerA : playerB);
        game.setWhitePlayerId(aIsBlack ? playerB : playerA);
        mapper.writeState(game, rules.initialState());
        // Seed both time banks and start Black's clock (Black moves first). PvP-only: vs-AI games leave
        // these null and are never swept for a timeout (spec §15, M10).
        long bank = clockProperties.initialMs();
        game.setBlackTimeMs(bank);
        game.setWhiteTimeMs(bank);
        game.setTurnStartedAt(Instant.now());
        game = games.save(game);
        return game.getId();
    }

    /**
     * Applies {@code move} to game {@code gameId} (spec §9/§11). Validation of legality is delegated
     * to the engine; an illegal placement or illegal pass throws {@link IllegalArgumentException}
     * (M4.5 maps that to 422). Participant/turn checks are layered on in M4.5.
     */
    @Transactional
    public GameStateResponse applyMove(UUID gameId, OthelloMove move) {
        Game game = games.findById(gameId).orElseThrow(() -> new GameNotFoundException(gameId));
        applyToGame(game, move);
        return toResponse(game, null);
    }

    /**
     * The vs-AI human-submit flow (spec §7/§9). Applies the human's {@code move} and returns the
     * resulting state. The bot's reply is then computed <em>asynchronously</em> (M8): this method
     * returns immediately after the human's move and publishes an {@link AiReplyRequested} event, and
     * {@link AiReplyService} computes + applies the reply off the request thread and pushes it over
     * WebSocket — so a multi-second Hard search never holds the HTTP request open. The returned view
     * therefore reflects only the human's move; the bot's move arrives as a {@code MOVE_MADE} push.
     *
     * <p>When {@code bot.async-reply} is off the reply is played inline through the same transactional
     * pipeline (clamped to {@code syncThinkCap}); this keeps the deterministic service tests free of a
     * background worker racing their reads.
     */
    @Transactional
    public GameStateResponse submitMove(UUID gameId, UUID callerId, OthelloMove move) {
        Game game = games.findById(gameId).orElseThrow(() -> new GameNotFoundException(gameId));
        authorizeMove(game, callerId, move);
        boolean async = botProperties.asyncReply() && game.getOpponentType() == OpponentType.HUMAN_VS_AI;
        try {
            applyToGame(game, move);
            if (!async) {
                playBotReplyIfDue(game);
            }
            // Force the write now, inside the transaction, so a lost concurrent-move race surfaces here
            // (and not as an unmapped failure at commit) where we can map it to 409.
            games.flush();
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            // Another submission for this position committed first (§11). Two concurrent submissions on
            // the same game collide as EITHER a stale @Version on the game UPDATE OR a duplicate
            // (game_id, move_number) on the Move insert — whichever the flush hits first (Hibernate
            // orders the insert before the update, so a real two-submission race usually trips the unique
            // index). Both mean the same thing; roll back and surface a 409 so the caller retries against
            // the fresh state. The board is left exactly as the winning move set it.
            throw new ConcurrentMoveException(gameId, e);
        }
        if (async) {
            // Fires after this transaction commits (AFTER_COMMIT), so the worker reads the committed
            // move. Covers both the bot's reply and pushing GAME_OVER when the human's move was terminal.
            events.publishEvent(new AiReplyRequested(gameId, callerId));
        } else if (game.getOpponentType() == OpponentType.HUMAN_VS_HUMAN) {
            // PvP (M9.2): there is no bot reply, but the opponent isn't the one who POSTed, so they learn
            // this move over WebSocket. Published in-tx, handled AFTER_COMMIT (so the push carries the
            // committed board + any terminal Elo), the mirror of the vs-AI reply's push.
            events.publishEvent(new PvpMoveApplied(gameId, callerId));
        }
        return toResponse(game, callerId);
    }

    /**
     * Reads game {@code gameId} after a committed PvP move and describes the WebSocket push owed to the
     * mover's opponent (M9.2): the state oriented to the opponent (their legal moves now that the turn
     * has flipped) and whether the move ended the game. Empty for a non-PvP or missing game. Read-only —
     * the push itself is done by {@link PvpMoveNotifier} after this returns.
     */
    @Transactional(readOnly = true)
    public Optional<PvpMovePush> planPvpPush(UUID gameId, UUID moverId) {
        Game game = games.findById(gameId).orElse(null);
        if (game == null || game.getOpponentType() != OpponentType.HUMAN_VS_HUMAN) {
            return Optional.empty();
        }
        UUID opponentId = moverId.equals(game.getBlackPlayerId())
                ? game.getWhitePlayerId()
                : game.getBlackPlayerId();
        boolean terminal = game.getStatus() != GameStatus.IN_PROGRESS;
        return Optional.of(new PvpMovePush(opponentId, toResponse(game, opponentId), terminal));
    }

    /**
     * Forfeits {@code gameId} if the side to move has run its time bank to zero (spec §15, M10) —
     * called per game by the scheduled sweep. Re-reads and re-checks inside this transaction so a move
     * that committed between the sweep's candidate scan and here (refreshing {@code turnStartedAt}) is
     * seen and no false forfeit is issued. If a move instead wins the race with this write, {@code
     * flush} throws {@link ObjectOptimisticLockingFailureException}, which is deliberately <em>not</em>
     * caught here: swallowing it can't rescue the transaction (the persistence context is already
     * rollback-only, so commit would fail with {@code UnexpectedRollbackException}). It propagates out
     * so the transaction rolls back cleanly and {@link TurnClockService#sweep()} skips this one game —
     * the same "let the lock exception leave the transaction" pattern the human move path uses.
     * Returns the terminal state to push as {@code GAME_OVER} when a forfeit happened, else empty.
     */
    @Transactional
    public Optional<GameStateResponse> forfeitExpiredTurn(UUID gameId) {
        Game game = games.findById(gameId).orElse(null);
        if (game == null
                || game.getOpponentType() != OpponentType.HUMAN_VS_HUMAN
                || game.getStatus() != GameStatus.IN_PROGRESS
                || !isClocked(game)) {
            return Optional.empty();
        }
        Player toMove = game.getCurrentTurn();
        if (effectiveRemainingMs(game, toMove) > 0) {
            return Optional.empty(); // still has time on their clock
        }
        forfeit(game, toMove);
        // Surface a lost race now: if a move committed first, flush throws and the transaction rolls
        // back (see the Javadoc); otherwise this persists the forfeit inside the transaction.
        games.flush();
        // GAME_OVER is a broadcast to the game topic, so orientation is irrelevant (the game has ended
        // and legalMoves are empty for everyone).
        return Optional.of(toResponse(game, null));
    }

    /**
     * Plans the async bot reply for {@code gameId} as seen by {@code humanId} (M8), read-only so the
     * search in {@link AiReplyService} runs outside a transaction. Returns {@link BotReplyPlan.GameOver}
     * when the human's move already ended the game, {@link BotReplyPlan.Reply} with the engine snapshot
     * when it is the bot's turn, or {@link BotReplyPlan.Nothing} otherwise.
     */
    @Transactional(readOnly = true)
    public BotReplyPlan planBotReply(UUID gameId, UUID humanId) {
        Game game = games.findById(gameId).orElse(null);
        if (game == null || game.getOpponentType() != OpponentType.HUMAN_VS_AI) {
            return new BotReplyPlan.Nothing();
        }
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            return new BotReplyPlan.GameOver(toResponse(game, humanId));
        }
        Player bot = botPlayer(game.getBotSide());
        if (bot == null || game.getCurrentTurn() != bot) {
            return new BotReplyPlan.Nothing();
        }
        return new BotReplyPlan.Reply(mapper.toState(game), game.getBotDifficulty());
    }

    /**
     * Applies the asynchronously-computed bot {@code move} through the shared pipeline (M8) and returns
     * the new state oriented to {@code humanId}. Empty when it is no longer the bot's turn — the game
     * was resolved or a concurrent write won the optimistic-lock race — so the worker pushes nothing.
     */
    @Transactional
    public Optional<GameStateResponse> applyBotReply(UUID gameId, OthelloMove move, UUID humanId) {
        Game game = games.findById(gameId).orElseThrow(() -> new GameNotFoundException(gameId));
        Player bot = botPlayer(game.getBotSide());
        if (game.getStatus() != GameStatus.IN_PROGRESS || bot == null || game.getCurrentTurn() != bot) {
            return Optional.empty();
        }
        try {
            applyToGame(game, move);
            games.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            return Optional.empty();
        }
        return Optional.of(toResponse(game, humanId));
    }

    /**
     * The per-game/per-turn anti-cheat (§9/§10), checked in the spec's exact order: (1) the caller is
     * a participant (else 403); (2) the game is live and it is the caller's turn (else 409); (3) the
     * move is in the server-computed legal-move set (else 422). The server — never the client —
     * decides each verdict, so a forged or illegal move is rejected at the source.
     */
    private void authorizeMove(Game game, UUID callerId, OthelloMove move) {
        Player side = sideOf(game, callerId);
        if (side == null) {
            throw new NotAGameParticipantException(game.getId());
        }
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            throw new GameNotInProgressException(game.getId());
        }
        if (game.getCurrentTurn() != side) {
            throw new NotYourTurnException(game.getId());
        }
        // (3) Legality against the server's own legal-move set. A pass is legal only when the side to
        // move has no placement available; any other move must be one of the generated legal squares.
        List<OthelloMove> legal = rules.getLegalMoves(mapper.toState(game));
        boolean ok = move.isPass() ? legal.isEmpty() : legal.contains(move);
        if (!ok) {
            throw new IllegalMoveException(
                    game.getId(), move.isPass() ? "illegal pass (legal moves existed)" : "square " + move.square());
        }
    }

    /** Current state of {@code gameId} as seen by {@code callerId} (their legal moves included). */
    @Transactional(readOnly = true)
    public GameStateResponse getGameState(UUID gameId, UUID callerId) {
        Game game = games.findById(gameId).orElseThrow(() -> new GameNotFoundException(gameId));
        return toResponse(game, callerId);
    }

    /** The full ordered move history of {@code gameId} (spec §9 replay). */
    @Transactional(readOnly = true)
    public List<MoveResponse> getMoveHistory(UUID gameId) {
        if (!games.existsById(gameId)) {
            throw new GameNotFoundException(gameId);
        }
        return moves.findByGameIdOrderByMoveNumberAsc(gameId).stream()
                .map(MoveResponse::from)
                .toList();
    }

    /** The caller's games, optionally filtered by {@code status} ({@code null} ⇒ all) — newest first. */
    @Transactional(readOnly = true)
    public List<GameStateResponse> listGames(UUID callerId, GameStatus status) {
        return games.findForUser(callerId, status).stream()
                .map(game -> toResponse(game, callerId))
                .toList();
    }

    /**
     * Plays the bot's reply when it is due: a {@code HUMAN_VS_AI} game that is still in progress and
     * now on the bot's side. The bot makes exactly one move — its difficulty's chosen move, or a pass
     * when it has none — through {@link #applyToGame}. After the bot moves the turn returns to the
     * human, so at most one reply is ever owed per human submission.
     */
    private void playBotReplyIfDue(Game game) {
        if (game.getOpponentType() != OpponentType.HUMAN_VS_AI
                || game.getStatus() != GameStatus.IN_PROGRESS) {
            return;
        }
        Player botPlayer = botPlayer(game.getBotSide());
        if (botPlayer == null || game.getCurrentTurn() != botPlayer) {
            return; // not the bot's turn (e.g. the human's move was terminal, or it's the human's turn)
        }
        OthelloState state = mapper.toState(game);
        List<OthelloMove> legal = rules.getLegalMoves(state);
        OthelloMove botMove = legal.isEmpty()
                ? OthelloMove.pass()
                : botEngine.searchFor(game.getBotDifficulty()).bestMove(state);
        applyToGame(game, botMove);
    }

    /**
     * Core move primitive (shared by creation's opening move, human moves, and the AI reply): reads
     * the engine state from {@code game}, applies {@code move} (the engine validates legality),
     * persists the {@link Move} row and the new board/{@code moveCount}, and resolves the outcome if
     * the move ended the game. Runs in the caller's transaction.
     */
    void applyToGame(Game game, OthelloMove move) {
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            // Refuse to act on an ended game: re-resolving it would double-count W/L/D. M4.5 layers
            // the participant/turn checks ahead of this; this guard stands on its own for integrity.
            throw new GameNotInProgressException(game.getId());
        }
        OthelloState before = mapper.toState(game);
        Player mover = before.toMove();
        OthelloState after = rules.applyMove(before, move); // throws on an illegal move/pass
        int moveNumber = game.getMoveCount() + 1;

        // Server-authoritative turn clock (spec §15, M10): charge the mover the time their turn took and
        // restart the clock for the side to move next. PvP-only — a vs-AI game has null banks (the
        // opening move and bot replies must not touch clocks). A pass consumes time too (it flows here).
        if (isClocked(game)) {
            Instant now = Instant.now();
            long elapsed = Duration.between(game.getTurnStartedAt(), now).toMillis();
            long remaining = Math.max(0, bankFor(game, mover) - elapsed);
            setBankFor(game, mover, remaining);
            game.setTurnStartedAt(now);
        }

        if (move.isPass()) {
            moves.save(Move.pass(game.getId(), moveNumber, mover));
        } else {
            // Discs that changed owner: opponent discs before the move that are the mover's after it.
            // The placed square was empty before, so it is excluded — these are exactly the flips.
            long flipped = before.discs(mover.opponent()) & after.discs(mover);
            moves.save(Move.placement(game.getId(), moveNumber, mover, move.square(), flipped));
        }

        mapper.writeState(game, after);
        game.setMoveCount(moveNumber);

        if (rules.isTerminal(after)) {
            finish(game, after);
        }
    }

    /** Resolves a terminal game by disc count (double-pass/full board): winner = higher disc count. */
    private void finish(Game game, OthelloState terminal) {
        resolveOutcome(game, rules.winner(terminal));
    }

    /**
     * Applies a resolved outcome to {@code game}: sets {@code status}/{@code winnerId} from
     * {@code winner} ({@link Optional#empty()} ⇒ draw) and updates both humans' W/L/D counters + Elo.
     * Shared by {@link #finish} (winner from the board) and {@link #forfeit} (winner forced by a
     * timeout), so both reach the identical rating path.
     */
    private void resolveOutcome(Game game, Optional<Player> winner) {
        game.setStatus(winner.map(GameService::wonStatus).orElse(GameStatus.DRAW));
        // winnerId is the winning *human*; null when a bot wins or it's a draw (§5, Appendix C A1).
        game.setWinnerId(winner.map(side -> playerId(game, side)).orElse(null));

        // Snapshot each side's opponent rating BEFORE either side's rating is updated, so a PvP
        // symmetric update is order-independent (§8): otherwise WHITE would be scored against BLACK's
        // already-updated rating (and their deltas wouldn't sum to zero). vs-AI is unaffected — the
        // bot's rating is fixed and read from the game, and only the human side is recorded.
        int blackOpponentRating = opponentRatingFor(game, Player.BLACK);
        int whiteOpponentRating = opponentRatingFor(game, Player.WHITE);
        recordResult(game, Player.BLACK, winner, blackOpponentRating);
        recordResult(game, Player.WHITE, winner, whiteOpponentRating);
    }

    /**
     * Forfeits the game against {@code offender} (spec §15, M10): a flag-fall is a competitive loss, so
     * the opponent is awarded a rated win — {@code status} = the opponent's {@code *_WON}, {@code
     * winnerId} = the opponent human, and symmetric Elo applied — reusing the terminal outcome path.
     * ({@code ABANDONED} is reserved for M11's disconnect policy, not a timeout.)
     */
    private void forfeit(Game game, Player offender) {
        resolveOutcome(game, Optional.of(offender.opponent()));
    }

    /**
     * Updates the human on {@code side}: their denormalized W/L/D counters <em>and</em> their Elo
     * rating (spec §5/§8), scored against {@code opponentRating} (snapshotted before any update). A
     * no-op for the bot (null) side — the bot has no {@code User} row and its rating is fixed.
     */
    private void recordResult(Game game, Player side, Optional<Player> winner, int opponentRating) {
        UUID userId = playerId(game, side);
        if (userId == null) {
            return; // bot side — no User row to update
        }
        User user = users.findById(userId).orElseThrow();
        double score;
        if (winner.isEmpty()) {
            user.recordDraw();
            score = Elo.DRAW;
        } else if (winner.get() == side) {
            user.recordWin();
            score = Elo.WIN;
        } else {
            user.recordLoss();
            score = Elo.LOSS;
        }
        updateRating(game, user, opponentRating, score);
    }

    /**
     * Applies the Elo change for {@code user} from this terminal game and records it in
     * {@link RatingHistory} for the stats/graph endpoint (spec §8/§9). {@code opponentRating} is the
     * pre-game snapshot (the bot's fixed rating in a vs-AI game, or the other human's pre-update rating
     * in PvP).
     */
    private void updateRating(Game game, User user, int opponentRating, double score) {
        int oldRating = user.getEloRating();
        int newRating = Elo.updatedRating(oldRating, opponentRating, score);
        user.setEloRating(newRating);
        ratings.save(new RatingHistory(user.getId(), game.getId(), oldRating, newRating));
    }

    /**
     * The rating the player on {@code side} is scored against. In a vs-AI game the opponent is the
     * bot, whose fixed rating was captured on the game at creation (§8). In PvP it is the other human's
     * rating — read here <em>before</em> any update (see {@link #finish}) so the symmetric update is
     * order-independent.
     */
    private int opponentRatingFor(Game game, Player side) {
        UUID opponentId = playerId(game, side.opponent());
        if (opponentId != null) {
            return users.findById(opponentId).orElseThrow().getEloRating();
        }
        return game.getBotRating();
    }

    private static GameStatus wonStatus(Player winner) {
        return winner == Player.BLACK ? GameStatus.BLACK_WON : GameStatus.WHITE_WON;
    }

    /** The player id seated on {@code side}, or {@code null} when that side is the bot. */
    private static UUID playerId(Game game, Player side) {
        return side == Player.BLACK ? game.getBlackPlayerId() : game.getWhitePlayerId();
    }

    /**
     * Builds the state view for {@code callerId}, including the moves they may currently play and — for
     * a clocked PvP game — each side's live remaining time (§15). Remaining is {@code null} on an
     * unclocked (vs-AI) game.
     */
    private GameStateResponse toResponse(Game game, UUID callerId) {
        return GameStateResponse.of(
                game,
                legalMovesFor(game, callerId),
                effectiveRemainingMs(game, Player.BLACK),
                effectiveRemainingMs(game, Player.WHITE));
    }

    /**
     * The square indices {@code callerId} may legally play right now — empty unless the game is
     * {@code IN_PROGRESS} and it is the caller's turn (an empty list then means they must pass).
     */
    private List<Integer> legalMovesFor(Game game, UUID callerId) {
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            return List.of();
        }
        Player side = sideOf(game, callerId);
        if (side == null || game.getCurrentTurn() != side) {
            return List.of();
        }
        return rules.getLegalMoves(mapper.toState(game)).stream().map(OthelloMove::square).toList();
    }

    /** The side {@code userId} plays in {@code game}, or {@code null} if they are not a participant. */
    private static Player sideOf(Game game, UUID userId) {
        if (userId == null) {
            return null;
        }
        if (userId.equals(game.getBlackPlayerId())) {
            return Player.BLACK;
        }
        if (userId.equals(game.getWhitePlayerId())) {
            return Player.WHITE;
        }
        return null;
    }

    /** The {@link Player} the bot plays, or {@code null} when there is no bot ({@code BotSide.NONE}). */
    private static Player botPlayer(BotSide botSide) {
        return switch (botSide) {
            case BLACK -> Player.BLACK;
            case WHITE -> Player.WHITE;
            case NONE -> null;
        };
    }

    /** Whether {@code game} runs a turn clock — true only once its banks/start have been seeded (PvP). */
    static boolean isClocked(Game game) {
        return game.getTurnStartedAt() != null
                && game.getBlackTimeMs() != null
                && game.getWhiteTimeMs() != null;
    }

    /**
     * The live remaining bank in milliseconds for {@code side} (spec §15, M10). The side to move counts
     * down from {@code turnStartedAt} (clamped at 0); the idle side's bank is frozen at its stored
     * value. Returns {@code null} for an unclocked (vs-AI) game — callers surface that as "no clock".
     */
    Long effectiveRemainingMs(Game game, Player side) {
        if (!isClocked(game)) {
            return null;
        }
        long bank = bankFor(game, side);
        if (game.getStatus() == GameStatus.IN_PROGRESS && side == game.getCurrentTurn()) {
            long elapsed = Duration.between(game.getTurnStartedAt(), Instant.now()).toMillis();
            return Math.max(0, bank - elapsed);
        }
        return bank;
    }

    /** The stored time bank for {@code side} (only meaningful on a clocked game). */
    private static long bankFor(Game game, Player side) {
        return side == Player.BLACK ? game.getBlackTimeMs() : game.getWhiteTimeMs();
    }

    /** Sets the stored time bank for {@code side}. */
    private static void setBankFor(Game game, Player side, long ms) {
        if (side == Player.BLACK) {
            game.setBlackTimeMs(ms);
        } else {
            game.setWhiteTimeMs(ms);
        }
    }
}
