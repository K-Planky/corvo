// Top-level screen switch: Auth → Lobby → Game. State is intentionally tiny, the server is
// authoritative, so the client only tracks who's signed in and which game (if any) is open. The one
// bit of client-only truth is *which screen you're on* (lobby vs a board): the server can't tell a
// vs-AI game you've left to the lobby from one you're still watching, since both stay IN_PROGRESS. We
// remember the open game's id in sessionStorage so a refresh restores the same screen you were on.

import { useEffect, useState } from 'react';
import Auth from './Auth';
import Lobby from './Lobby';
import GameView from './GameView';
import { getGame, getToken, listGames, logout, me } from './api';
import type { GameState, User } from './types';

// Per-tab marker for the board the user currently has open. sessionStorage (not localStorage) so it
// survives a refresh but not a fresh session, reopening the app later lands in the lobby, not back
// in an old game. A live PvP match is restored from server state regardless (see the boot effect).
const ACTIVE_GAME_KEY = 'othello.activeGame';

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [game, setGame] = useState<GameState | null>(null);
  // A stored JWT outlives a page reload but the User object doesn't, so on load we rehydrate the
  // session from the token via GET /api/auth/me rather than forcing a fresh sign-in. `booting` holds
  // the UI until that resolves so a returning visitor doesn't flash the Auth screen first.
  const [booting, setBooting] = useState(getToken() !== null);

  // Open a game and remember it, so a refresh returns to this board rather than the lobby.
  function openGame(g: GameState): void {
    sessionStorage.setItem(ACTIVE_GAME_KEY, g.id);
    setGame(g);
  }

  // Leave the board back to the lobby and forget it, so a refresh stays in the lobby.
  function closeGame(): void {
    sessionStorage.removeItem(ACTIVE_GAME_KEY);
    setGame(null);
  }

  useEffect(() => {
    if (getToken() === null) return;
    let active = true;
    me()
      .then(async (u) => {
        if (!active) return;
        setUser(u);
        // Restore the screen the user was on before the refresh. A live PvP match is inescapable, so
        // it's always reopened from server state, even in a tab with no marker (e.g. storage cleared
        // mid-match). Otherwise reopen only the board this tab had open (the marker), and only if it's
        // still in progress; a game left to the lobby has no marker, so we stay in the lobby.
        try {
          const open = await listGames('IN_PROGRESS');
          if (!active) return;
          const savedId = sessionStorage.getItem(ACTIVE_GAME_KEY);
          const pick =
            open.find((g) => g.opponentType === 'HUMAN_VS_HUMAN') ??
            open.find((g) => g.id === savedId);
          if (!pick) {
            // Nothing to restore (in the lobby, or the marked game has finished): drop a stale marker.
            sessionStorage.removeItem(ACTIVE_GAME_KEY);
            return;
          }
          // Re-GET for the caller-oriented state (legalMoves/clocks) per the reconnect contract.
          const full = await getGame(pick.id);
          if (active) openGame(full);
        } catch {
          // Lookup failed, fall through to the lobby without disturbing the marker.
        }
      })
      // Token missing/expired/invalid: drop it and fall back to the sign-in screen.
      .catch(() => active && logout())
      .finally(() => active && setBooting(false));
    return () => {
      active = false;
    };
  }, []);

  // Hold the shell until the token check finishes (it's a single fast request).
  if (booting) {
    return <main className="app" />;
  }

  if (!user || !getToken()) {
    return (
      <main className="app">
        <Auth onAuthenticated={setUser} />
      </main>
    );
  }

  if (game) {
    return (
      <main className="app">
        <GameView
          key={game.id}
          initial={game}
          user={user}
          onExit={() => {
            closeGame();
            // A finished game changed our Elo server-side; re-read it so the lobby shows the new
            // rating without a reload. Best-effort, a failure just leaves the prior value on screen.
            me().then(setUser).catch(() => {});
          }}
        />
      </main>
    );
  }

  return (
    <main className="app">
      <Lobby
        user={user}
        onOpenGame={openGame}
        onLogout={() => {
          logout();
          closeGame();
          setUser(null);
        }}
      />
    </main>
  );
}
