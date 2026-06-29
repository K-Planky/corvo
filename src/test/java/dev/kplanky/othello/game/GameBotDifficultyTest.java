package dev.kplanky.othello.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kplanky.othello.TestcontainersConfiguration;
import dev.kplanky.othello.domain.BotDifficulty;
import dev.kplanky.othello.domain.BotSide;
import dev.kplanky.othello.domain.Game;
import dev.kplanky.othello.domain.User;
import dev.kplanky.othello.repository.GameRepository;
import dev.kplanky.othello.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * M6.6 acceptance (spec §7/§8): creating a vs-AI game records the bot's fixed rating for the chosen
 * difficulty, persisted on the game so M7's Elo math reads it directly.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GameBotDifficultyTest {

    @Autowired
    GameService gameService;

    @Autowired
    GameRepository games;

    @Autowired
    UserRepository users;

    private UUID humanId;

    @BeforeEach
    void setUp() {
        games.deleteAll();
        users.deleteAll();
        humanId = users.save(new User("human", "human@example.com", "hash")).getId();
    }

    @Test
    void eachDifficultyStoresItsFixedBotRating() {
        // The human takes Black (botSide WHITE) so no bot opening move runs at creation.
        assertThat(createGame(BotDifficulty.EASY)).isEqualTo(1000);
        assertThat(createGame(BotDifficulty.MEDIUM)).isEqualTo(1500);
        assertThat(createGame(BotDifficulty.HARD)).isEqualTo(1800);
    }

    /** Creates a vs-AI game at {@code difficulty} and returns the bot rating persisted on it. */
    private Integer createGame(BotDifficulty difficulty) {
        UUID gameId = gameService.createVsAiGame(humanId, difficulty, BotSide.WHITE).id();
        Game game = games.findById(gameId).orElseThrow();
        return game.getBotRating();
    }
}
