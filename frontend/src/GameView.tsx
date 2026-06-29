// One vs-AI game: the board plus score, turn/status banner, and the Pass action that appears only
// when the server reports the human has no legal placement. The move POST returns the state after the
// human's move only; the bot's reply is computed off-thread and pushed over WebSocket (M8), so we
// subscribe to the game's events and replace the whole GameState from each push.

import { useEffect, useState } from 'react';
import Board from './Board';
import { ApiError, submitMove } from './api';
import { subscribeToGame } from './ws';
import { humanSide, isOver, type GameState, type Player } from './types';

interface GameViewProps {
  initial: GameState;
  onExit: () => void;
}

export default function GameView({ initial, onExit }: GameViewProps) {
  const [game, setGame] = useState<GameState>(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const you = humanSide(game);
  const over = isOver(game);
  const yourTurn = !over && game.currentTurn === you;
  const mustPass = yourTurn && game.legalMoves.length === 0;
  // It's the bot's turn and not over ⇒ the server is computing the reply it will push to us.
  const botThinking = !over && !yourTurn;

  // Re-render live from server pushes: the bot's reply (MOVE_MADE) and the terminal result
  // (GAME_OVER) arrive here rather than in the move POST's response. Re-subscribe per game id.
  useEffect(() => {
    const sub = subscribeToGame(initial.id, (event) => setGame(event.state));
    return () => sub.close();
  }, [initial.id]);

  async function play(move: { position: number } | { pass: true }) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      // The response reflects only our move; the bot's reply follows over the socket.
      setGame(await submitMove(game.id, move));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="game">
      <header className="game-bar">
        <button type="button" className="link" onClick={onExit}>
          ← Lobby
        </button>
        <span className="game-meta">
          You play {sideLabel(you)} · {game.botDifficulty} bot
        </span>
      </header>

      <div className="scoreboard">
        <Score
          side="BLACK"
          discs={game.blackDiscs}
          active={!over && game.currentTurn === 'BLACK'}
          you={you === 'BLACK'}
        />
        <Score
          side="WHITE"
          discs={game.whiteDiscs}
          active={!over && game.currentTurn === 'WHITE'}
          you={you === 'WHITE'}
        />
      </div>

      <Banner game={game} you={you} botThinking={botThinking} />

      <Board
        cells={game.cells}
        legalMoves={game.legalMoves}
        turn={game.currentTurn}
        interactive={yourTurn && !busy}
        onPlay={(square) => play({ position: square })}
      />

      {error && <p className="error">{error}</p>}

      <div className="actions">
        {mustPass && (
          <button
            type="button"
            className="btn"
            disabled={busy}
            onClick={() => play({ pass: true })}
          >
            No legal move — Pass
          </button>
        )}
        {over && (
          <button type="button" className="btn" onClick={onExit}>
            Back to lobby
          </button>
        )}
      </div>
    </section>
  );
}

function Score({
  side,
  discs,
  active,
  you,
}: {
  side: Player;
  discs: number;
  active: boolean;
  you: boolean;
}) {
  return (
    <div className={`score ${active ? 'score-active' : ''}`}>
      <span className={`disc disc-${side.toLowerCase()} disc-sm`} />
      <span className="score-count">{discs}</span>
      {you && <span className="score-you">you</span>}
    </div>
  );
}

function Banner({
  game,
  you,
  botThinking,
}: {
  game: GameState;
  you: Player;
  botThinking: boolean;
}) {
  if (isOver(game)) {
    return <p className={`banner banner-result ${resultClass(game, you)}`}>{resultText(game, you)}</p>;
  }
  // In-progress: a slim status strip with a swatch of the side to move. When it's the bot's turn it is
  // computing the reply it will push back, so the label reads "Bot is thinking…". The label sits in a
  // fixed-width box so the centered strip doesn't shift as the text length changes.
  const label = botThinking ? 'Bot is thinking…' : 'Your move';
  return (
    <p className="banner">
      <span className={`turn-dot disc disc-${game.currentTurn.toLowerCase()}`} />
      <span className="banner-text">{label}</span>
    </p>
  );
}

function resultText(game: GameState, you: Player): string {
  if (game.status === 'DRAW') return "It's a draw.";
  const winner: Player = game.status === 'BLACK_WON' ? 'BLACK' : 'WHITE';
  return winner === you ? 'You win! 🎉' : 'You lose.';
}

function resultClass(game: GameState, you: Player): string {
  if (game.status === 'DRAW') return 'banner-draw';
  const winner: Player = game.status === 'BLACK_WON' ? 'BLACK' : 'WHITE';
  return winner === you ? 'banner-win' : 'banner-lose';
}

function sideLabel(side: Player): string {
  return side === 'BLACK' ? 'Black' : 'White';
}
