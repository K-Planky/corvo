// Tiny typed wrapper over the REST API (spec §9). Holds the JWT and attaches it as a Bearer token.
// All game logic lives on the server; this module only marshals requests and surfaces errors.

import type {
  AuthResponse,
  Difficulty,
  GameState,
  BotSide,
  MatchmakingStatus,
  User,
  UserStats,
} from './types';

const TOKEN_KEY = 'othello.token';

let token: string | null = localStorage.getItem(TOKEN_KEY);

export function getToken(): string | null {
  return token;
}

function setToken(value: string | null): void {
  token = value;
  if (value) {
    localStorage.setItem(TOKEN_KEY, value);
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

export function logout(): void {
  setToken(null);
}

/** An API error carrying the HTTP status so callers can react to 401/403/409/422 distinctly. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(
  path: string,
  options: { method?: string; body?: unknown; auth?: boolean } = {},
): Promise<T> {
  const { method = 'GET', body, auth = true } = options;
  const headers: Record<string, string> = {};
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (auth && token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const res = await fetch(`/api${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res));
  }
  // 204-less API, but guard against empty bodies anyway.
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

async function errorMessage(res: Response): Promise<string> {
  try {
    const body = await res.json();
    if (body && typeof body.message === 'string') {
      return body.message;
    }
  } catch {
    // fall through to a status-based default
  }
  switch (res.status) {
    case 401:
      return 'Your session has expired — please sign in again.';
    case 403:
      return "You're not a participant in this game.";
    case 409:
      return "That move is no longer valid — it's not your turn.";
    case 422:
      return 'That move is illegal.';
    default:
      return `Request failed (HTTP ${res.status}).`;
  }
}

export async function register(
  username: string,
  password: string,
): Promise<AuthResponse> {
  const auth = await request<AuthResponse>('/auth/register', {
    method: 'POST',
    body: { username, password },
    auth: false,
  });
  setToken(auth.token);
  return auth;
}

export async function login(
  username: string,
  password: string,
): Promise<AuthResponse> {
  const auth = await request<AuthResponse>('/auth/login', {
    method: 'POST',
    body: { username, password },
    auth: false,
  });
  setToken(auth.token);
  return auth;
}

/** The signed-in user behind the stored token. Rehydrates a session on reload (the token persists in
 *  localStorage but the User object doesn't) and re-reads the current Elo after a game. A 401 means
 *  the token is gone/expired — callers clear it and show the sign-in screen. */
export function me(): Promise<User> {
  return request<User>('/auth/me');
}

export function createGame(
  difficulty: Difficulty,
  botSide: BotSide,
): Promise<GameState> {
  return request<GameState>('/games', {
    method: 'POST',
    body: { difficulty, botSide },
  });
}

export function getGame(id: string): Promise<GameState> {
  return request<GameState>(`/games/${id}`);
}

export function listGames(status?: string): Promise<GameState[]> {
  const query = status ? `?status=${status}` : '';
  return request<GameState[]>(`/games${query}`);
}

/** Public per-user stats (spec §9): current rating + W/L/D + rating history. Used to label a PvP
 *  opponent by username and by the stats/leaderboard read UIs. Auth optional server-side. */
export function getUserStats(id: string): Promise<UserStats> {
  return request<UserStats>(`/users/${id}/stats`);
}

/** Join the matchmaking queue (spec §9/§15). Pairs immediately with a waiting player when one is
 *  present (`MATCHED` + `gameId`); otherwise enqueues the caller (`QUEUED`). Either way, a paired
 *  player also gets a `MATCH_FOUND` push on their personal queue (see ws.ts). */
export function joinQueue(): Promise<MatchmakingStatus> {
  return request<MatchmakingStatus>('/matchmaking/queue', { method: 'POST' });
}

/** Leave the matchmaking queue. Idempotent server-side — safe to call even if not currently queued. */
export function leaveQueue(): Promise<void> {
  return request<void>('/matchmaking/queue', { method: 'DELETE' });
}

/** Submit a placement (`position`) or a pass. Returns the state after the human's move only; the
 *  bot's reply is computed off-thread and arrives over the WebSocket as a MOVE_MADE push (see ws.ts). */
export function submitMove(
  id: string,
  move: { position: number } | { pass: true },
): Promise<GameState> {
  return request<GameState>(`/games/${id}/moves`, {
    method: 'POST',
    body: move,
  });
}
