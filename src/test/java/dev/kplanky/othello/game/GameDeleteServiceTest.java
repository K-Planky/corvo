package dev.kplanky.othello.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.othello.OthelloMove;
import dev.kplanky.othello.engine.othello.OthelloState;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.MoveRepository;
import dev.kplanky.othello.repository.RatingHistoryRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Deleting a game from the Resume list (M12.x). Deletion is intentionally narrow, only the caller's
 * own in-progress single-player game, so a multiplayer match (shared, rated) and a finished game
 * (rating history / applied Elo) are both refused. The child {@code moves} rows must go with the game.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GameDeleteServiceTest {

    @Autowired
    GameService gameService;

    @Autowired
    GameRepository games;

    @Autowired
    MoveRepository moves;

    @Autowired
    RatingHistoryRepository ratings;

    @Autowired
    UserRepository users;

    @Autowired
    GameRules<OthelloState, OthelloMove> rules;

    @Autowired
    GameStateMapper mapper;

    private UUID humanId;
    private UUID strangerId;

    @BeforeEach
    void setUp() {
        moves.deleteAll();
        ratings.deleteAll();
        games.deleteAll();
        users.deleteAll();
        humanId = users.save(new User("human", "hash")).getId();
        strangerId = users.save(new User("stranger", "hash")).getId();
    }

    @Test
    void deletesInProgressVsAiGameAndItsMoves() {
        UUID gameId = gameService.createVsAiGame(humanId, BotDifficulty.EASY, BotSide.WHITE).id();
        // Play a move so the game has a child moves row to clean up (the FK has no ON DELETE CASCADE).
        OthelloMove first = rules.getLegalMoves(OthelloState.initial()).get(0);
        gameService.applyMove(gameId, first);
        assertThat(moves.findByGameIdOrderByMoveNumberAsc(gameId)).isNotEmpty();

        gameService.deleteGame(gameId, humanId);

        assertThat(games.findById(gameId)).isEmpty();
        assertThat(moves.findByGameIdOrderByMoveNumberAsc(gameId)).isEmpty();
    }

    @Test
    void rejectsDeleteByNonParticipant() {
        UUID gameId = gameService.createVsAiGame(humanId, BotDifficulty.EASY, BotSide.WHITE).id();

        assertThatThrownBy(() -> gameService.deleteGame(gameId, strangerId))
                .isInstanceOf(NotAGameParticipantException.class);
        assertThat(games.findById(gameId)).isPresent();
    }

    @Test
    void rejectsDeleteOfMultiplayerMatch() {
        UUID gameId = gameService.createPvpGame(humanId, strangerId);

        assertThatThrownBy(() -> gameService.deleteGame(gameId, humanId))
                .isInstanceOf(GameNotDeletableException.class);
        assertThat(games.findById(gameId)).isPresent();
    }

    @Test
    void rejectsDeleteOfFinishedGame() {
        UUID gameId = gameService.createVsAiGame(humanId, BotDifficulty.EASY, BotSide.WHITE).id();
        // Drive the game to a terminal state (first legal move each turn; pass only when forced).
        Game game = games.findById(gameId).orElseThrow();
        int guard = 0;
        while (game.getStatus() == GameStatus.IN_PROGRESS) {
            List<OthelloMove> legal = rules.getLegalMoves(mapper.toState(game));
            gameService.applyMove(gameId, legal.isEmpty() ? OthelloMove.pass() : legal.get(0));
            game = games.findById(gameId).orElseThrow();
            assertThat(++guard).isLessThan(200);
        }

        assertThatThrownBy(() -> gameService.deleteGame(gameId, humanId))
                .isInstanceOf(GameNotDeletableException.class);
        assertThat(games.findById(gameId)).isPresent();
    }
}
