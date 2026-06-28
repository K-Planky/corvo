package dev.kplanky.othello.game;

import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.Move;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.Player;
import dev.kplanky.othello.engine.Search;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.game.dto.GameStateResponse;
import dev.kplanky.othello.game.dto.MoveResponse;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    private final GameRules<OthelloState, OthelloMove> rules;
    private final Search<OthelloState, OthelloMove> botSearch;
    private final GameStateMapper mapper;

    public GameService(
            GameRepository games,
            MoveRepository moves,
            UserRepository users,
            GameRules<OthelloState, OthelloMove> rules,
            Search<OthelloState, OthelloMove> botSearch,
            GameStateMapper mapper) {
        this.games = games;
        this.moves = moves;
        this.users = users;
        this.rules = rules;
        this.botSearch = botSearch;
        this.mapper = mapper;
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
            applyToGame(game, botSearch.bestMove(mapper.toState(game)));
        }

        return toResponse(game, userId);
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
     * The vs-AI human-submit flow (spec §7/§9): applies the human's {@code move} and then, if it is
     * now the bot's turn and the game is still live, plays the bot's reply through the same
     * transactional pipeline — so terminal handling and persistence are identical for human and bot
     * moves. The bot plays a uniformly random legal move (ladder rung 1, {@link Search}/RandomBot),
     * or passes when it has no legal move.
     *
     * <p>The reply is synchronous here: a deliberate single-player-slice simplification. M8 moves it
     * off-thread and pushes it over WebSocket so a slow ({@code HARD}) search never holds the request
     * open.
     */
    @Transactional
    public GameStateResponse submitMove(UUID gameId, UUID callerId, OthelloMove move) {
        Game game = games.findById(gameId).orElseThrow(() -> new GameNotFoundException(gameId));
        authorizeMove(game, callerId, move);
        try {
            applyToGame(game, move);
            playBotReplyIfDue(game);
            // Force the @Version check now, inside the transaction, so a lost optimistic-lock race
            // surfaces here (and not as an unmapped failure at commit) where we can map it to 409.
            games.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            // Another submission committed against this game first (§11); our version is stale. The
            // transaction rolls back — the board is left exactly as the winning move set it.
            throw new ConcurrentMoveException(gameId, e);
        }
        return toResponse(game, callerId);
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
     * now on the bot's side. The bot makes exactly one move — a random legal move, or a pass when it
     * has none — through {@link #applyToGame}. After the bot moves the turn returns to the human, so
     * at most one reply is ever owed per human submission.
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
        OthelloMove botMove = legal.isEmpty() ? OthelloMove.pass() : botSearch.bestMove(state);
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

    /** Resolves a terminal game: sets {@code status}/{@code winnerId} and the humans' W/L/D counters. */
    private void finish(Game game, OthelloState terminal) {
        Optional<Player> winner = rules.winner(terminal);
        game.setStatus(winner.map(GameService::wonStatus).orElse(GameStatus.DRAW));
        // winnerId is the winning *human*; null when a bot wins or it's a draw (§5, Appendix C A1).
        game.setWinnerId(winner.map(side -> playerId(game, side)).orElse(null));

        recordResult(game, Player.BLACK, winner);
        recordResult(game, Player.WHITE, winner);
    }

    /** Updates the human on {@code side}'s denormalized counters; a no-op for the bot (null) side. */
    private void recordResult(Game game, Player side, Optional<Player> winner) {
        UUID userId = playerId(game, side);
        if (userId == null) {
            return; // bot side — no User row to update
        }
        User user = users.findById(userId).orElseThrow();
        if (winner.isEmpty()) {
            user.recordDraw();
        } else if (winner.get() == side) {
            user.recordWin();
        } else {
            user.recordLoss();
        }
    }

    private static GameStatus wonStatus(Player winner) {
        return winner == Player.BLACK ? GameStatus.BLACK_WON : GameStatus.WHITE_WON;
    }

    /** The player id seated on {@code side}, or {@code null} when that side is the bot. */
    private static UUID playerId(Game game, Player side) {
        return side == Player.BLACK ? game.getBlackPlayerId() : game.getWhitePlayerId();
    }

    /** Builds the state view for {@code callerId}, including the moves they may currently play. */
    private GameStateResponse toResponse(Game game, UUID callerId) {
        return GameStateResponse.of(game, legalMovesFor(game, callerId));
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
}
