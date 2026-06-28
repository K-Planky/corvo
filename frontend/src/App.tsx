// Top-level screen switch: Auth → Lobby → Game. State is intentionally tiny — the server is
// authoritative, so the client only tracks who's signed in and which game (if any) is open.

import { useState } from 'react';
import Auth from './Auth';
import Lobby from './Lobby';
import GameView from './GameView';
import { getToken, logout } from './api';
import type { GameState, User } from './types';

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [game, setGame] = useState<GameState | null>(null);

  // A stored JWT means a returning visitor; we still require a fresh sign-in to recover the User
  // view (M4 has no "me" endpoint), so a stale token just means the Auth screen shows first.
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
          onExit={() => setGame(null)}
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
