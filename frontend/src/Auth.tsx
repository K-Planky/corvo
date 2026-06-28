// Sign-in / register screen. On success it hands the authenticated user up to App; the JWT itself
// is stashed by the api module. Toggling between the two modes just swaps which fields show.

import { useState } from 'react';
import { ApiError, login, register } from './api';
import type { User } from './types';

interface AuthProps {
  onAuthenticated: (user: User) => void;
}

export default function Auth({ onAuthenticated }: AuthProps) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const registering = mode === 'register';

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const auth = registering
        ? await register(username, email, password)
        : await login(username, password);
      onAuthenticated(auth.user);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not reach the server.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card auth">
      <img className="logo-mark" src="/crow.svg" alt="" width="72" height="72" />
      <h1 className="logo">Corvo</h1>
      <p className="tagline">Outsmart the crow.</p>

      <form onSubmit={submit}>
        <label>
          Username
          <input
            name="username"
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
          />
        </label>

        {registering && (
          <label>
            Email
            <input
              name="email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </label>
        )}

        <label>
          Password
          <input
            name="password"
            type="password"
            autoComplete={registering ? 'new-password' : 'current-password'}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>

        {error && <p className="error">{error}</p>}

        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Please wait…' : registering ? 'Create account' : 'Sign in'}
        </button>
      </form>

      <p className="switch">
        {registering ? 'Already have an account?' : 'New here?'}{' '}
        <button
          type="button"
          className="link"
          onClick={() => {
            setMode(registering ? 'login' : 'register');
            setError(null);
          }}
        >
          {registering ? 'Sign in' : 'Create one'}
        </button>
      </p>
    </section>
  );
}
