package dev.kplanky.othello.game;

import dev.kplanky.othello.auth.JwtService.JwtPrincipal;
import dev.kplanky.othello.domain.GameStatus;
import dev.kplanky.othello.game.dto.CreateGameRequest;
import dev.kplanky.othello.game.dto.GameStateResponse;
import dev.kplanky.othello.game.dto.MoveResponse;
import dev.kplanky.othello.game.dto.SubmitMoveRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Game REST endpoints (spec §9). All require a valid JWT; the participant/turn anti-cheat is enforced
 * on move submission (M4.5). {@code POST .../moves} returns the state after the human's move only and
 * returns immediately, the bot's reply is computed off-thread and pushed over WebSocket (M8).
 */
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

    @GetMapping("/{id}")
    public GameStateResponse get(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable UUID id) {
        return gameService.getGameState(id, principal.userId());
    }

    @GetMapping("/{id}/moves")
    public List<MoveResponse> moveHistory(@PathVariable UUID id) {
        return gameService.getMoveHistory(id);
    }

    @PostMapping("/{id}/moves")
    public GameStateResponse submitMove(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody SubmitMoveRequest request) {
        return gameService.submitMove(id, principal.userId(), request.toMove());
    }

    @GetMapping
    public List<GameStateResponse> myGames(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(required = false) GameStatus status) {
        return gameService.listGames(principal.userId(), status);
    }

    /**
     * Discards one of the caller's own in-progress single-player games (Resume list, M12.x). Rejects a
     * game the caller isn't in (403), a multiplayer or finished game (409), or an unknown id (404).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal JwtPrincipal principal, @PathVariable UUID id) {
        gameService.deleteGame(id, principal.userId());
    }
}
