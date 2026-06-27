package dev.kplanky.othello.game;

import dev.kplanky.othello.auth.JwtService.JwtPrincipal;
import dev.kplanky.othello.game.dto.CreateGameRequest;
import dev.kplanky.othello.game.dto.GameStateResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Game endpoints (spec §9). Creation lands here in M4.1; the read/move endpoints arrive in M4.4. */
@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GameStateResponse create(
            @AuthenticationPrincipal JwtPrincipal principal,
            @Valid @RequestBody CreateGameRequest request) {
        return gameService.createVsAiGame(principal.userId(), request.difficulty(), request.botSide());
    }
}
