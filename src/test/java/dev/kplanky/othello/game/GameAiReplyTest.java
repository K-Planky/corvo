package dev.kplanky.othello.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.domain.Move;
import dev.kplanky.othello.domain.OpponentType;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.engine.GameRules;
import dev.kplanky.othello.engine.Player;
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
 * M4.3 acceptance (spec §7): after a human move the bot replies through the same transactional apply
 * pipeline, and in a forced-pass position the bot passes rather than moving. The reply is synchronous
 * in this slice (M8 moves it off-thread + WS push). The bot now plays the M6 difficulty engine
 * (iterative deepening) rather than M4's random rung; these assertions stay behaviour-agnostic (a
 * legal reply / a forced pass), so they hold for either.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GameAiReplyTest {

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

    @BeforeEach
    void setUp() {
        moves.deleteAll();
        ratings.deleteAll();
        games.deleteAll();
        users.deleteAll();
        humanId = users.save(new User("human", "hash")).getId();
    }

    @Test
    void botPlaysLegalReplyAfterHumanMove() {
        // Human is Black, bot is White.
        UUID gameId = gameService.createVsAiGame(humanId, BotDifficulty.EASY, BotSide.WHITE).id();
        OthelloMove humanMove = rules.getLegalMoves(OthelloState.initial()).get(0);

        var response = gameService.submitMove(gameId, humanId, humanMove);

        Game game = games.findById(gameId).orElseThrow();
        assertThat(game.getMoveCount()).isEqualTo(2); // human move + bot reply
        assertThat(game.getCurrentTurn()).isEqualTo(Player.BLACK); // turn back to the human
        assertThat(game.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(response.moveCount()).isEqualTo(2);

        List<Move> history = moves.findByGameIdOrderByMoveNumberAsc(gameId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getPlayer()).isEqualTo(Player.BLACK);
        Move botMove = history.get(1);
        assertThat(botMove.getPlayer()).isEqualTo(Player.WHITE);
        assertThat(botMove.isPass()).isFalse(); // White always has a reply to the opening
        assertThat(botMove.getPosition().intValue()).isBetween(0, 63);
    }

    @Test
    void botRepliesInABotBlackGame() {
        // Bot is Black and already opened at creation (move 1); human is White and moves next.
        UUID gameId = gameService.createVsAiGame(humanId, BotDifficulty.EASY, BotSide.BLACK).id();
        Game created = games.findById(gameId).orElseThrow();
        assertThat(created.getMoveCount()).isEqualTo(1);

        OthelloMove humanMove = rules.getLegalMoves(mapper.toState(created)).get(0);
        gameService.submitMove(gameId, humanId, humanMove);

        Game game = games.findById(gameId).orElseThrow();
        assertThat(game.getMoveCount()).isEqualTo(3); // bot open + human + bot reply
        assertThat(game.getCurrentTurn()).isEqualTo(Player.WHITE); // back to the human (White)

        List<Move> history = moves.findByGameIdOrderByMoveNumberAsc(gameId);
        assertThat(history).hasSize(3);
        assertThat(history.get(1).getPlayer()).isEqualTo(Player.WHITE); // the human's move
        assertThat(history.get(2).getPlayer()).isEqualTo(Player.BLACK); // the bot's reply
    }

    @Test
    void botPassesWhenItHasNoLegalMove() {
        // Crafted live position: Black=a1,b1 ; White=c1. Black's only legal move is d1, which flips c1
        // and wipes White off the board, leaving the bot (White) with no move — it must pass.
        Game crafted = new Game();
        crafted.setOpponentType(OpponentType.HUMAN_VS_AI);
        crafted.setBotSide(BotSide.WHITE);
        crafted.setBotDifficulty(BotDifficulty.EASY);
        crafted.setBlackPlayerId(humanId);
        crafted.setBoardBlack(OthelloState.bit(0) | OthelloState.bit(1));
        crafted.setBoardWhite(OthelloState.bit(2));
        crafted.setCurrentTurn(Player.BLACK);
        crafted = games.save(crafted);
        UUID gameId = crafted.getId();

        // Sanity: the human's only legal move is d1 (square 3).
        assertThat(rules.getLegalMoves(mapper.toState(crafted))).containsExactly(OthelloMove.at(3));

        gameService.submitMove(gameId, humanId, OthelloMove.at(3));

        Game game = games.findById(gameId).orElseThrow();
        assertThat(game.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(game.getCurrentTurn()).isEqualTo(Player.BLACK); // bot passed, back to the human
        assertThat(game.getConsecutivePasses()).isEqualTo(1);
        assertThat(game.getBoardWhite()).isEqualTo(0L); // the bot placed nothing

        List<Move> history = moves.findByGameIdOrderByMoveNumberAsc(gameId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getPlayer()).isEqualTo(Player.BLACK);
        assertThat(history.get(0).isPass()).isFalse();
        Move botPass = history.get(1);
        assertThat(botPass.getPlayer()).isEqualTo(Player.WHITE);
        assertThat(botPass.isPass()).isTrue();
        assertThat(botPass.getPosition()).isNull();
    }

    @Test
    void fullVsAiGamePlaysToTerminalThroughSubmitMove() {
        UUID gameId = gameService.createVsAiGame(humanId, BotDifficulty.EASY, BotSide.WHITE).id();

        // The human (Black) submits a move each turn; the bot (White) replies inside the same call,
        // so after every non-terminal submitMove it is the human's turn again.
        Game game = games.findById(gameId).orElseThrow();
        int guard = 0;
        while (game.getStatus() == GameStatus.IN_PROGRESS) {
            List<OthelloMove> legal = rules.getLegalMoves(mapper.toState(game));
            gameService.submitMove(gameId, humanId, legal.isEmpty() ? OthelloMove.pass() : legal.get(0));
            game = games.findById(gameId).orElseThrow();
            assertThat(++guard).isLessThan(200);
        }

        assertThat(game.getStatus()).isNotEqualTo(GameStatus.IN_PROGRESS);
        // vs-AI is unrated practice (§8): the game finishes but adds nothing to the human's record.
        assertThat(users.findById(humanId).orElseThrow().getGamesPlayed()).isZero();

        // Replaying the recorded history from the initial position reproduces the stored board: the
        // bot's moves went through the same recorded pipeline as the human's.
        OthelloState replay = OthelloState.initial();
        for (Move recorded : moves.findByGameIdOrderByMoveNumberAsc(gameId)) {
            replay = rules.applyMove(
                    replay, recorded.isPass() ? OthelloMove.pass() : OthelloMove.at(recorded.getPosition()));
        }
        assertThat(replay.black()).isEqualTo(game.getBoardBlack());
        assertThat(replay.white()).isEqualTo(game.getBoardWhite());
    }
}
