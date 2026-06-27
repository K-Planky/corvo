import { useEffect, useState } from 'react';

type Health = { status: string };

// M0.3 placeholder: proves the thin client builds and can reach the server's /health endpoint
// across origins in dev (via the Vite proxy). The real game UI arrives in Milestone 4.
export default function App() {
  const [health, setHealth] = useState('checking…');

  useEffect(() => {
    fetch('/health')
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json() as Promise<Health>;
      })
      .then((body) => setHealth(body.status))
      .catch((err: Error) => setHealth(`unreachable (${err.message})`));
  }, []);

  return (
    <main>
      <h1>Othello</h1>
      <p>
        Server <code>/health</code>: <strong>{health}</strong>
      </p>
      <p>Thin client placeholder — the game UI lands in Milestone 4.</p>
    </main>
  );
}
