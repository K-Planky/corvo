package dev.kplanky.othello.game;

import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.Move;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.Player;
import dev.kplanky.othello.engine.Search;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.game.dto.GameStateResponse;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Game orchestration (spec §4/§9). Owns the mapping between the engine and the persisted {@link Game}
 * and is the single place that mutates a game's position. This first slice covers vs-AI game
 * creation; transactional move application, the AI reply, and authorization land in later M4 tasks.
 */
@Service
public class GameService {

    private final GameRepository games;
    private final MoveRepository moves;
    private final GameRules<OthelloState, OthelloMove> rules;
    private final Search<OthelloState, OthelloMove> botSearch;
    private final GameStateMapper mapper;

    public GameService(
            GameRepository games,
            MoveRepository moves,
            GameRules<OthelloState, OthelloMove> rules,
            Search<OthelloState, OthelloMove> botSearch,
            GameStateMapper mapper) {
        this.games = games;
        this.moves = moves;
        this.rules = rules;
        this.botSearch = botSearch;
        this.mapper = mapper;
    }

    /**
     * Creates a {@code HUMAN_VS_AI} game for {@code userId}. The human takes the non-bot side; the
     * bot side keeps a {@code null} player id (bots have no {@code User} row, §5). The board starts at
     * the engine's initial position. When the bot plays Black it moves first, so its opening move is
     * applied here — and recorded as the game's first {@link Move} — leaving the freshly created game
     * already on the human's (White) turn.
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

        OthelloState state = rules.initialState();
        mapper.writeState(game, state);
        game = games.save(game);

        if (botSide == BotSide.BLACK) {
            // Black moves first and here Black is the bot, so play its opening move now. Recording it
            // as move 1 keeps the stored board consistent with the replayable move list (the §5
            // board + move-list redundancy invariant). The opening position always has legal moves.
            OthelloMove opening = botSearch.bestMove(state);
            OthelloState afterOpening = rules.applyMove(state, opening);
            long flipped = state.white() & afterOpening.black(); // white discs the move turned black
            moves.save(Move.placement(game.getId(), 1, Player.BLACK, opening.square(), flipped));
            mapper.writeState(game, afterOpening);
            game.setMoveCount(1);
        }

        return GameStateResponse.from(game);
    }
}
