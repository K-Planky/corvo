// One game — vs-AI or human-vs-human (M12.2). The board plus score, turn/status banner, and the Pass
// action that appears only when the server reports the caller has no legal placement. The move POST
// returns the state after the caller's move only; the reply (the bot's, or the PvP opponent's) is
// pushed over WebSocket (M8/M9), so we subscribe to the game's events and replace the whole GameState
// from each push. PvP adds live turn clocks (M10), an opponent-disconnect notice (M11.2), and a
// timeout/forfeit result; the vs-AI path is unchanged.

import { useCallback, useEffect, useRef, useState } from 'react';
import Board from './Board';
import { ApiError, getGame, getUserStats, submitMove } from './api';
import { subscribeToGame, type GameEvent } from './ws';
import { isOver, viewerSide, type GameState, type Player, type User } from './types';

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
  user: User;
  onExit: () => void;
}

export default function GameView({ initial, user, onExit }: GameViewProps) {
  const [game, setGame] = useState<GameState>(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pvp = game.opponentType === 'HUMAN_VS_HUMAN';
  // PvP-only UI state: the opponent's display name (label falls back to "Opponent"), whether their
  // socket is currently down (grace-window notice, M11.2), and whether the game ended without a move
  // — a timeout/disconnect forfeit rather than a played-out finish (see showState).
  const [opponentName, setOpponentName] = useState<string | null>(null);
  const [oppOffline, setOppOffline] = useState(false);
  const [forfeit, setForfeit] = useState(false);
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

  // The moveCount of the last *in-progress* position we displayed. A normal finish advances moveCount
  // past this (the final move); a timeout/disconnect forfeit does not (no move) — so a terminal state
  // whose moveCount equals this is a forfeit. Only in-progress states update it, so it's stable across
  // the redundant MOVE_MADE(N)+GAME_OVER(N) pair a normal PvP finish sends (see showState).
  const lastLiveMoveCount = useRef(initial.moveCount);

  // Turn-clock display (PvP-only, M10). `clockAnchor` marks when the current `game` state (and thus its
  // authoritative remaining-time values) was received; between server pushes we tick the side-to-move
  // down from it for smoothness. `clockTick` just forces a re-render on an interval — the real values
  // are recomputed from the authoritative bank minus the elapsed wall time (see remainingMs).
  const clockAnchor = useRef(performance.now());
  const [, setClockTick] = useState(0);

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
      // Track the forfeit signal off the raw state stream (before the ordering/collapse below), so it
      // sees every arrival: remember the latest in-progress moveCount; a terminal state that didn't
      // advance past it (no accompanying move) is a timeout/disconnect forfeit.
      if (next.status === 'IN_PROGRESS') {
        lastLiveMoveCount.current = Math.max(lastLiveMoveCount.current, next.moveCount);
      } else {
        setForfeit(next.moveCount === lastLiveMoveCount.current);
      }
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

  const you = viewerSide(game, user.id);
  const over = isOver(game);
  // While `staging`, the on-screen `game` is a lagging state we're holding for its animation; don't
  // let it drive interactivity or the Pass prompt, or the stale board flashes as live mid-gap.
  const yourTurn = !over && !staging && game.currentTurn === you;
  const mustPass = yourTurn && game.legalMoves.length === 0;
  // It's the opponent's turn (bot or human) and not over ⇒ we're waiting on their reply.
  const waiting = !over && !yourTurn;
  const clocks = game.blackTimeRemainingMs != null; // PvP games carry turn clocks; vs-AI does not.

  // Remaining ms to show for a side: the authoritative bank, with the *side to move* counted down by
  // the wall time since we received it (the idle side and a finished game stay frozen). Null ⇒ no clock.
  function remainingMs(side: Player): number | null {
    const base = side === 'BLACK' ? game.blackTimeRemainingMs : game.whiteTimeRemainingMs;
    if (base == null) return null;
    if (!over && game.currentTurn === side) {
      return Math.max(0, base - (performance.now() - clockAnchor.current));
    }
    return base;
  }

  // Apply a server state (from a push or a reconnect re-fetch): while a human move is in flight, hold
  // the latest (keeping the higher moveCount) so it can't be shown ahead of the human-move state the
  // POST will return — see moveInFlight/play(); otherwise enqueue it for display.
  const applyIncoming = useCallback(
    (state: GameState) => {
      if (moveInFlight.current) {
        if (!heldPush.current || state.moveCount >= heldPush.current.moveCount) {
          heldPush.current = state;
        }
      } else {
        showState(state);
      }
    },
    [showState],
  );

  // Re-render live from server pushes: the bot's reply (MOVE_MADE) and the terminal result
  // (GAME_OVER) arrive here rather than in the move POST's response. Re-subscribe per game id. On a
  // socket reconnect (M11), re-GET authoritative state so a move applied during the gap — whose push
  // we missed — is caught; showState drops stale/duplicate moveCounts, so a no-op reconnect is safe.
  useEffect(() => {
    const sub = subscribeToGame(
      initial.id,
      (event: GameEvent) => {
        // Presence events (M11.2) carry the *unchanged* state — they only toggle the disconnect
        // notice and must never reach the board-advancing path, or an informational event could
        // collapse/rewind the board.
        if (event.type === 'OPPONENT_DISCONNECTED') {
          setOppOffline(true);
          return;
        }
        if (event.type === 'OPPONENT_RECONNECTED') {
          setOppOffline(false);
          return;
        }
        // MOVE_MADE / GAME_OVER / YOUR_TURN carry the advancing board.
        applyIncoming(event.state);
      },
      () => {
        void getGame(initial.id).then(applyIncoming).catch(() => {});
      },
    );
    return () => sub.close();
  }, [initial.id, applyIncoming]);

  // Reset the countdown anchor whenever the displayed state changes (each push/GET carries fresh
  // authoritative clock values) so between-push ticking starts from the moment we received it.
  useEffect(() => {
    clockAnchor.current = performance.now();
  }, [game]);

  // Re-render ~4×/sec so the side-to-move clock visibly counts down between pushes — only while a
  // live clocked (PvP) game is in progress.
  useEffect(() => {
    if (!clocks || over) return;
    const t = setInterval(() => setClockTick((n) => n + 1), 250);
    return () => clearInterval(t);
  }, [clocks, over]);

  // Label the PvP opponent by username (public stats read, reused from M7.3). Best-effort — the UI
  // falls back to "Opponent" until/unless this resolves.
  useEffect(() => {
    if (!pvp) return;
    const opponentId = game.blackPlayerId === user.id ? game.whitePlayerId : game.blackPlayerId;
    if (!opponentId) return;
    let active = true;
    getUserStats(opponentId)
      .then((s) => {
        if (active) setOpponentName(s.username);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [pvp, game.blackPlayerId, game.whitePlayerId, user.id]);

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
          You play {sideLabel(you)} ·{' '}
          {pvp ? `vs ${opponentName ?? 'Opponent'}` : `${game.botDifficulty} bot`}
        </span>
      </header>

      <div className="scoreboard">
        <Score
          side="BLACK"
          discs={game.blackDiscs}
          active={!over && game.currentTurn === 'BLACK'}
          you={you === 'BLACK'}
          clock={remainingMs('BLACK')}
        />
        <Score
          side="WHITE"
          discs={game.whiteDiscs}
          active={!over && game.currentTurn === 'WHITE'}
          you={you === 'WHITE'}
          clock={remainingMs('WHITE')}
        />
      </div>

      {/* Grace doesn't pause the turn clock (M11 policy), so we deliberately keep the disconnected
          side's clock ticking here — it mirrors the server, which can still forfeit them on time. */}
      {oppOffline && !over && (
        <p className="banner banner-warn">Opponent disconnected — waiting for them to reconnect…</p>
      )}

      <Banner
        game={game}
        you={you}
        waiting={waiting}
        waitingLabel={pvp ? 'Waiting for opponent…' : 'Bot is thinking…'}
        // Forfeit copy is PvP-only — vs-AI has no clocks/disconnect, so it never "forfeits".
        forfeit={pvp && forfeit}
      />

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
  clock,
}: {
  side: Player;
  discs: number;
  active: boolean;
  you: boolean;
  clock: number | null;
}) {
  return (
    <div className={`score ${active ? 'score-active' : ''}`}>
      <span className={`disc disc-${side.toLowerCase()} disc-sm`} />
      <span className="score-count">{discs}</span>
      {clock != null && (
        <span className={`score-clock ${clock < 10_000 ? 'score-clock-low' : ''}`}>
          {formatClock(clock)}
        </span>
      )}
      {you && <span className="score-you">you</span>}
    </div>
  );
}

function Banner({
  game,
  you,
  waiting,
  waitingLabel,
  forfeit,
}: {
  game: GameState;
  you: Player;
  waiting: boolean;
  waitingLabel: string;
  forfeit: boolean;
}) {
  if (isOver(game)) {
    return (
      <p className={`banner banner-result ${resultClass(game, you)}`}>
        {resultText(game, you, forfeit)}
      </p>
    );
  }
  // In-progress: a slim status strip with a swatch of the side to move. When it's the opponent's turn
  // the label reads "Bot is thinking…" (vs-AI) or "Waiting for opponent…" (PvP). The label sits in a
  // fixed-width box so the centered strip doesn't shift as the text length changes.
  const label = waiting ? waitingLabel : 'Your move';
  return (
    <p className="banner">
      <span className={`turn-dot disc disc-${game.currentTurn.toLowerCase()}`} />
      <span className="banner-text">{label}</span>
    </p>
  );
}

// A forfeit (PvP timeout or disconnect lapse) ends the game with no move played; the non-winner is the
// one who forfeited, so the message names them rather than a played-out win/loss.
function resultText(game: GameState, you: Player, forfeit: boolean): string {
  if (game.status === 'DRAW') return "It's a draw.";
  const winner: Player = game.status === 'BLACK_WON' ? 'BLACK' : 'WHITE';
  if (winner === you) return forfeit ? 'You win — opponent forfeited 🎉' : 'You win! 🎉';
  return forfeit ? 'You lose — forfeited' : 'You lose.';
}

function resultClass(game: GameState, you: Player): string {
  if (game.status === 'DRAW') return 'banner-draw';
  const winner: Player = game.status === 'BLACK_WON' ? 'BLACK' : 'WHITE';
  return winner === you ? 'banner-win' : 'banner-lose';
}

/** Format a remaining-time bank (ms) as m:ss, rounding up so a sub-second value still shows 0:01. */
function formatClock(ms: number): string {
  const totalSec = Math.ceil(ms / 1000);
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}:${sec.toString().padStart(2, '0')}`;
}

function sideLabel(side: Player): string {
  return side === 'BLACK' ? 'Black' : 'White';
}
