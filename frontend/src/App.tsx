// Top-level screen switch: Auth → Lobby → Game. State is intentionally tiny — the server is
// authoritative, so the client only tracks who's signed in and which game (if any) is open.

import { useEffect, useState } from 'react';
import Auth from './Auth';
import Lobby from './Lobby';
import GameView from './GameView';
import { getToken, logout, me } from './api';
import type { GameState, User } from './types';

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [game, setGame] = useState<GameState | null>(null);
  // A stored JWT outlives a page reload but the User object doesn't, so on load we rehydrate the
  // session from the token via GET /api/auth/me rather than forcing a fresh sign-in. `booting` holds
  // the UI until that resolves so a returning visitor doesn't flash the Auth screen first.
  const [booting, setBooting] = useState(getToken() !== null);

  useEffect(() => {
    if (getToken() === null) return;
    let active = true;
    me()
      .then((u) => active && setUser(u))
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
          onExit={() => {
            setGame(null);
            // A finished game changed our Elo server-side; re-read it so the lobby shows the new
            // rating without a reload. Best-effort — a failure just leaves the prior value on screen.
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
        onOpenGame={setGame}
        onLogout={() => {
          logout();
          setGame(null);
          setUser(null);
        }}
      />
    </main>
  );
}
