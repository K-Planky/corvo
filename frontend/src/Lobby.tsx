// Lobby: start a new vs-AI game (pick difficulty + which colour the bot plays) and resume any
// in-progress game. Creating or opening a game hands its state up to App, which switches to the
// board view.

import { useEffect, useState } from 'react';
import { ApiError, createGame, getGame, listGames } from './api';
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
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    listGames('IN_PROGRESS')
      .then(setGames)
      .catch(() => setGames([]));
  }, []);

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

      {games.length > 0 && (
        <div className="card">
          <h2>Resume</h2>
          <ul className="game-list">
            {games.map((g) => (
              <li key={g.id}>
                <button type="button" className="game-row" onClick={() => resume(g.id)}>
                  <span>
                    vs {g.botDifficulty} bot · move {g.moveCount}
                  </span>
                  <span className="score-mini">
                    {g.blackDiscs}–{g.whiteDiscs}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
