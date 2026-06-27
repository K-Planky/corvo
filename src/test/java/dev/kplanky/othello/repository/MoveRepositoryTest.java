package dev.kplanky.othello.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.Move;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.engine.Player;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * M2.3 acceptance (spec §5): {@code Move} ordering integrity. The {@code UNIQUE (game_id,
 * move_number)} constraint rejects two moves sharing a move number within a game, and the ordered
 * history query returns moves in {@code moveNumber} order regardless of insertion order.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class MoveRepositoryTest {

    @Autowired
    GameRepository gameRepository;

    @Autowired
    MoveRepository moveRepository;

    @Test
    void orderedHistoryReturnsMovesInMoveNumberOrder() {
        UUID gameId = persistedGame().getId();

        // Persist out of order to prove the ORDER BY (not insertion order) drives the result.
        moveRepository.save(Move.placement(gameId, 3, Player.BLACK, 20, 0L));
        moveRepository.save(Move.placement(gameId, 1, Player.BLACK, 19, 0L));
        moveRepository.save(Move.pass(gameId, 2, Player.WHITE));

        List<Move> history = moveRepository.findByGameIdOrderByMoveNumberAsc(gameId);

        assertThat(history).extracting(Move::getMoveNumber).containsExactly(1, 2, 3);
    }

    @Test
    void duplicateMoveNumberWithinGameViolatesUniqueConstraint() {
        UUID gameId = persistedGame().getId();

        moveRepository.save(Move.placement(gameId, 1, Player.BLACK, 19, 0L));

        // Same (gameId, moveNumber) → unique-constraint violation, surfaced as Spring's
        // DataIntegrityViolationException on flush.
        Move duplicate = Move.placement(gameId, 1, Player.WHITE, 26, 0L);
        assertThatThrownBy(() -> moveRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Game persistedGame() {
        Game game = new Game();
        game.setOpponentType(OpponentType.HUMAN_VS_AI);
        game.setBotSide(BotSide.WHITE);
        game.setBoardBlack(0x0000000810000000L);
        game.setBoardWhite(0x0000001008000000L);
        game.setCurrentTurn(Player.BLACK);
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setMoveCount(0);
        return gameRepository.save(game);
    }
}
