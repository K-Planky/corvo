// One vs-AI game: the board plus score, turn/status banner, and the Pass action that appears only
// when the server reports the human has no legal placement. The move POST returns the state after the
// human's move only; the bot's reply is computed off-thread and pushed over WebSocket (M8), so we
// subscribe to the game's events and replace the whole GameState from each push.

import { useCallback, useEffect, useRef, useState } from 'react';
import Board from './Board';
import { ApiError, submitMove } from './api';
import { subscribeToGame } from './ws';
import { humanSide, isOver, type GameState, type Player } from './types';

// Minimum on-screen time for each board state before the next one replaces it. Post-M8 the human's
// move and the bot's reply arrive as two separate updates (the move POST, then the WebSocket push);
// a fast bot pushes its reply within a frame of the human move, so without staging React commits the
// reply DOM over the human-move DOM before it paints and the capture animation never plays. This gap
// must clear the longest disc animation (`disc-flip`, 450ms in index.css) so each move's flip is
// seen; the margin beyond that is a deliberate "bot is thinking" beat so a fast bot doesn't snap back
// the instant the human's flip finishes (~300ms here). States are shown in `moveCount` order, and
// successive states for the *same* board (the bot's MOVE_MADE then its identical YOUR_TURN/GAME_OVER)
// collapse harmlessly — see showState.
export const STAGE_MS = 750;

interface GameViewProps {
  initial: GameState;
  onExit: () => void;
}

export default function GameView({ initial, onExit }: GameViewProps) {
  const [game, setGame] = useState<GameState>(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // True while a board update is staged but not yet shown: the on-screen `game` is intentionally
  // lagging the true state, so the board must not be treated as live (it'd flash the now-stale
  // legal-move hints / Pass button until the staged state lands). See showState.
  const [staging, setStaging] = useState(false);

  // Stage board updates so each state shows for >= STAGE_MS and its capture animation can play.
  // `lastShownAt` seeds to -Infinity so the first update shows immediately regardless of clock origin;
  // `shownMoveCount` tracks the displayed state so out-of-order/duplicate pushes can't rewind it.
  const lastShownAt = useRef(Number.NEGATIVE_INFINITY);
  const shownMoveCount = useRef(initial.moveCount);
  const queue = useRef<GameState[]>([]);
  const stageTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // A human move is mid-flight (its POST is in progress). Near the end of a game the bot's search is
  // near-instant, so its reply (a MOVE_MADE push carrying the board after *both* moves) can reach us
  // over the socket before that POST resolves. Processed as-is it would show the post-bot board in one
  // step — the human's own move never animating, sometimes rewinding when the late POST lands. So
  // while a move is in flight we hold the latest incoming push and replay it only once the human-move
  // state has been queued (see play()), which keeps them in moveCount order.
  const moveInFlight = useRef(false);
  const heldPush = useRef<GameState | null>(null);

  // Show the head of the queue once its STAGE_MS gap since the last shown state has elapsed, then
  // reschedule for the next. `flushRef` lets the timer always call the latest closure.
  const flushRef = useRef<() => void>(() => {});
  const flush = useCallback(() => {
    if (stageTimer.current !== null || queue.current.length === 0) return;
    const wait = lastShownAt.current + STAGE_MS - performance.now();
    if (wait > 0) {
      setStaging(true);
      stageTimer.current = setTimeout(() => {
        stageTimer.current = null;
        flushRef.current();
      }, wait);
      return;
    }
    const next = queue.current.shift()!;
    lastShownAt.current = performance.now();
    shownMoveCount.current = next.moveCount;
    setGame(next);
    const more = queue.current.length > 0;
    setStaging(more);
    if (more) {
      stageTimer.current = setTimeout(() => {
        stageTimer.current = null;
        flushRef.current();
      }, STAGE_MS);
    }
  }, []);
  flushRef.current = flush;

  // Enqueue a server state for display, ordered by moveCount. A state older than what's already shown
  // or queued is dropped (an out-of-order or duplicate push can never rewind the board); a state with
  // the same moveCount is the same move's board (the bot's MOVE_MADE then its identical
  // YOUR_TURN/GAME_OVER) — collapse to the latest, updating in place if it's already on screen so a
  // status-only change like GAME_OVER still lands.
  const showState = useCallback(
    (next: GameState) => {
      const tail = queue.current.length
        ? queue.current[queue.current.length - 1].moveCount
        : shownMoveCount.current;
      if (next.moveCount < tail) return;
      if (next.moveCount === tail) {
        if (queue.current.length) queue.current[queue.current.length - 1] = next;
        else setGame(next);
        return;
      }
      queue.current.push(next);
      flush();
    },
    [flush],
  );

  // Drop any pending staged update when the game unmounts (e.g. back to lobby).
  useEffect(
    () => () => {
      if (stageTimer.current !== null) clearTimeout(stageTimer.current);
    },
    [],
  );

  const you = humanSide(game);
  const over = isOver(game);
  // While `staging`, the on-screen `game` is a lagging state we're holding for its animation; don't
  // let it drive interactivity or the Pass prompt, or the stale board flashes as live mid-gap.
  const yourTurn = !over && !staging && game.currentTurn === you;
  const mustPass = yourTurn && game.legalMoves.length === 0;
  // It's the bot's turn and not over ⇒ the server is computing the reply it will push to us.
  const botThinking = !over && !yourTurn;

  // Re-render live from server pushes: the bot's reply (MOVE_MADE) and the terminal result
  // (GAME_OVER) arrive here rather than in the move POST's response. Re-subscribe per game id. While a
  // human move is in flight we hold the latest push (keeping the higher moveCount) so it can't be
  // shown ahead of the human-move state the POST will return — see moveInFlight/play().
  useEffect(() => {
    const sub = subscribeToGame(initial.id, (event) => {
      if (moveInFlight.current) {
        if (!heldPush.current || event.state.moveCount >= heldPush.current.moveCount) {
          heldPush.current = event.state;
        }
      } else {
        showState(event.state);
      }
    });
    return () => sub.close();
  }, [initial.id, showState]);

  async function play(move: { position: number } | { pass: true }) {
    if (busy) return;
    moveInFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      // The response reflects only our move; the bot's reply follows over the socket.
      showState(await submitMove(game.id, move));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Something went wrong.');
    } finally {
      setBusy(false);
      moveInFlight.current = false;
      // Replay a bot reply that raced in ahead of the POST response — now that the human-move state is
      // queued, this lands after it in moveCount order rather than overwriting/preceding it.
      if (heldPush.current) {
        const held = heldPush.current;
        heldPush.current = null;
        showState(held);
      }
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
