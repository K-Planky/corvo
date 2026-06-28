// One vs-AI game: the board plus score, turn/status banner, and the Pass action that appears only
// when the server reports the human has no legal placement. State advances by replacing the whole
// GameState with each move response — the synchronous M4 reply already includes the bot's move.

import { useState } from 'react';
import Board from './Board';
import { ApiError, submitMove } from './api';
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

  async function play(move: { position: number } | { pass: true }) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
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

      <Banner game={game} you={you} busy={busy} />

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
  busy,
}: {
  game: GameState;
  you: Player;
  busy: boolean;
}) {
  if (isOver(game)) {
    return <p className={`banner banner-result ${resultClass(game, you)}`}>{resultText(game, you)}</p>;
  }
  // In-progress: a slim status strip with a swatch of the side to move. While the bot is thinking the
  // persisted turn is still yours (the move is mid-flight), so derive the bot's side explicitly. The
  // label sits in a fixed-width box so the centered strip doesn't shift as the text length changes.
  const turnSide: Player = busy ? (you === 'BLACK' ? 'WHITE' : 'BLACK') : game.currentTurn;
  const label = busy ? 'Bot is thinking…' : game.currentTurn === you ? 'Your move' : "Bot's move";
  return (
    <p className="banner">
      <span className={`turn-dot disc disc-${turnSide.toLowerCase()}`} />
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
