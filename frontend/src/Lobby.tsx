// Lobby: start a new vs-AI game (pick difficulty + which colour the bot plays) and resume any
// in-progress game. Creating or opening a game hands its state up to App, which switches to the
// board view.

import { useEffect, useRef, useState } from 'react';
import { ApiError, createGame, deleteGame, getGame, joinQueue, leaveQueue, listGames } from './api';
import { subscribeToNotifications, type GameSubscription } from './ws';
import type { BotSide, Difficulty, GameState, User } from './types';

interface LobbyProps {
  user: User;
  onOpenGame: (game: GameState) => void;
  onLogout: () => void;
}

const DIFFICULTIES: Difficulty[] = ['EASY', 'MEDIUM', 'HARD'];

export default function Lobby({ user, onOpenGame, onLogout }: LobbyProps) {
  const [difficulty, setDifficulty] = useState<Difficulty>('EASY');
  const [botSide, setBotSide] = useState<BotSide>('WHITE');
  const [games, setGames] = useState<GameState[]>([]);
  const [busy, setBusy] = useState(false);
  const [matching, setMatching] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Matchmaking is event-driven (a WS push can open the game), so lifecycle decisions live in refs,
  // not state. `settledRef` opens the matched game exactly once and, once set, blocks a late open,
  // both the joiner's MATCHED response and its MATCH_FOUND push arrive, and a join can also resolve
  // MATCHED *after* the user cancels. `queuedRef` records that we may still be sitting in the server
  // queue (⇒ owe a DELETE). `joinRef` is the in-flight join POST, awaited before any DELETE so the
  // DELETE can't overtake the enqueue and strand us in the queue.
  const subRef = useRef<GameSubscription | null>(null);
  const joinRef = useRef<Promise<void> | null>(null);
  const settledRef = useRef(false);
  const queuedRef = useRef(false);

  useEffect(() => {
    listGames('IN_PROGRESS')
      .then(setGames)
      .catch(() => setGames([]));
  }, []);

  // Leaving the lobby while still queued must DELETE the queue (spec §9/§15) so we aren't paired into
  // a game we've walked away from.
  useEffect(() => {
    return () => {
      settledRef.current = true; // block a late open/error setState after unmount.
      subRef.current?.close();
      void leaveQueueWhenSettled();
    };
  }, []);

  function teardown() {
    subRef.current?.close();
    subRef.current = null;
  }

  // DELETE the queue once any in-flight join has completed (so the DELETE lands after the enqueue,
  // never before it), but only if we might still be queued, a completed match already removed us.
  async function leaveQueueWhenSettled() {
    try {
      await joinRef.current;
    } catch {
      // join failed ⇒ we were never enqueued.
    }
    if (queuedRef.current) {
      queuedRef.current = false;
      void leaveQueue().catch(() => {});
    }
  }

  function openMatched(game: GameState) {
    if (settledRef.current) return; // open once; also ignores a MATCHED that lands after a cancel.
    settledRef.current = true;
    queuedRef.current = false; // pairing already removed us from the server queue.
    teardown();
    onOpenGame(game);
  }

  function findMatch() {
    if (matching || busy) return;
    setError(null);
    setMatching(true);
    settledRef.current = false;
    queuedRef.current = true;
    // Subscribe first, then join in onReady: a pairing pushed right after we join can't be missed.
    subRef.current = subscribeToNotifications(
      (event) => {
        if (event.type === 'MATCH_FOUND') openMatched(event.state);
      },
      () => {
        joinRef.current = joinAndOpen();
      },
    );
  }

  async function joinAndOpen() {
    try {
      const res = await joinQueue();
      if (res.status === 'MATCHED' && res.gameId) {
        // The game exists and its MATCH_FOUND push also carries the state, so a failed fetch isn't
        // fatal, fall back to the push rather than aborting a match the server already made.
        try {
          openMatched(await getGame(res.gameId));
        } catch {
          // opened by the MATCH_FOUND push instead.
        }
      }
      // QUEUED: stay waiting; the MATCH_FOUND push will open the game.
    } catch (e) {
      if (settledRef.current) return; // cancelled/unmounted while joining, ignore.
      settledRef.current = true;
      queuedRef.current = false;
      teardown();
      setMatching(false);
      setError(e instanceof ApiError ? e.message : 'Could not find a match.');
    }
  }

  function cancelMatch() {
    settledRef.current = true; // block a late MATCHED open from the in-flight join.
    teardown();
    setMatching(false);
    void leaveQueueWhenSettled();
  }

  async function start() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      onOpenGame(await createGame(difficulty, botSide));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not start a game.');
      setBusy(false);
    }
  }

  async function resume(id: string) {
    try {
      onOpenGame(await getGame(id));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not open that game.');
    }
  }

  // Discard a resumable game. Confirm first (a delete is unrecoverable), then drop it from the list
  // locally, no re-fetch needed since we know exactly which row went.
  async function remove(id: string) {
    if (!window.confirm('Delete this match? This cannot be undone.')) return;
    try {
      await deleteGame(id);
      setGames((gs) => gs.filter((g) => g.id !== id));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not delete that game.');
    }
  }

  return (
    <section className="lobby">
      <header className="topbar">
        <span className="brand">
          <img className="logo-mark-sm" src="/crow.svg" alt="" width="38" height="38" />
          <h1 className="logo logo-sm">Corvo</h1>
        </span>
        <span className="who">
          {user.username}
          <span className="rating">{user.eloRating} Elo</span>
          <button type="button" className="link" onClick={onLogout}>
            Sign out
          </button>
        </span>
      </header>

      <div className="card">
        <h2>New game</h2>

        <fieldset className="choice">
          <legend>Difficulty</legend>
          {DIFFICULTIES.map((d) => (
            <label key={d} className={`chip ${difficulty === d ? 'chip-on' : ''}`}>
              <input
                type="radio"
                name="difficulty"
                value={d}
                checked={difficulty === d}
                onChange={() => setDifficulty(d)}
              />
              {d.charAt(0) + d.slice(1).toLowerCase()}
            </label>
          ))}
        </fieldset>

        <fieldset className="choice">
          <legend>You play</legend>
          {/* The human takes the side the bot doesn't. Bot=White ⇒ you're Black and move first. */}
          <label className={`chip ${botSide === 'WHITE' ? 'chip-on' : ''}`}>
            <input
              type="radio"
              name="botSide"
              checked={botSide === 'WHITE'}
              onChange={() => setBotSide('WHITE')}
            />
            Black (first)
          </label>
          <label className={`chip ${botSide === 'BLACK' ? 'chip-on' : ''}`}>
            <input
              type="radio"
              name="botSide"
              checked={botSide === 'BLACK'}
              onChange={() => setBotSide('BLACK')}
            />
            White (second)
          </label>
        </fieldset>

        {error && <p className="error">{error}</p>}

        <button type="submit" className="btn btn-primary" disabled={busy} onClick={start}>
          {busy ? 'Starting…' : 'Start game'}
        </button>
      </div>

      <div className="card">
        <h2>Play a person</h2>
        {matching ? (
          <>
            <p className="waiting">Waiting for an opponent…</p>
            <button type="button" className="btn" onClick={cancelMatch}>
              Cancel
            </button>
          </>
        ) : (
          <button type="button" className="btn btn-primary" onClick={findMatch}>
            Find match
          </button>
        )}
      </div>

      {games.length > 0 && (
        <div className="card">
          <h2>Resume</h2>
          <ul className="game-list">
            {games.map((g) => (
              <li key={g.id} className="game-list-row">
                <button type="button" className="game-row" onClick={() => resume(g.id)}>
                  <span>
                    {g.opponentType === 'HUMAN_VS_HUMAN' ? 'vs opponent' : `vs ${g.botDifficulty} bot`} ·
                    move {g.moveCount}
                  </span>
                  <span className="score-mini">
                    {g.blackDiscs}–{g.whiteDiscs}
                  </span>
                </button>
                {/* Only single-player games are deletable, a multiplayer match is rated and shared
                    with an opponent (and, being locked, never appears here anyway). */}
                {g.opponentType === 'HUMAN_VS_AI' && (
                  <button
                    type="button"
                    className="game-delete"
                    aria-label="Delete match"
                    onClick={() => remove(g.id)}
                  >
                    ✕
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
