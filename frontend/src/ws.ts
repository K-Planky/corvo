// STOMP-over-WebSocket client (spec §9, M8). The server computes the bot's reply off-thread and
// pushes it here, so the client renders live moves from these events rather than from the move POST's
// response. Auth mirrors REST: the JWT rides in the STOMP CONNECT frame (the handshake is anonymous).

import { Client, type IMessage } from '@stomp/stompjs';
import { getToken } from './api';
import type { GameState } from './types';

export type GameEventType =
  | 'MOVE_MADE'
  | 'GAME_OVER'
  | 'YOUR_TURN'
  | 'MATCH_FOUND'
  | 'OPPONENT_DISCONNECTED'
  | 'OPPONENT_RECONNECTED';

/** A server→client push: the event kind plus the full post-event state to re-render from. */
export interface GameEvent {
  type: GameEventType;
  state: GameState;
}

/** A live subscription; call {@link GameSubscription.close} to disconnect (e.g. on unmount). */
export interface GameSubscription {
  close(): void;
}

/**
 * Connect and subscribe to a game's live events: the per-game topic ({@code MOVE_MADE}/{@code
 * GAME_OVER}) and the personal queue ({@code YOUR_TURN}). Every event carries the latest state, so the
 * caller just re-renders from {@code event.state}. The client auto-reconnects on a dropped socket.
 *
 * <p>{@code onReconnect} fires on every connect <em>after</em> the first (spec §15, M11): a move may
 * have been applied while the socket was down and its push missed, so the caller re-`GET`s
 * authoritative state to catch up — the board is server-authoritative, so a returning client just
 * re-fetches and re-subscribes (no client resync). The first connect needs no fetch: the mount already
 * loaded state.
 */
export function subscribeToGame(
  gameId: string,
  onEvent: (event: GameEvent) => void,
  onReconnect?: () => void,
): GameSubscription {
  let connectedBefore = false;
  const client = new Client({
    brokerURL: socketUrl(),
    connectHeaders: { Authorization: `Bearer ${getToken() ?? ''}` },
    reconnectDelay: 2000,
    onConnect: () => {
      client.subscribe(`/topic/games/${gameId}`, (msg) => deliver(msg, onEvent));
      client.subscribe('/user/queue/notifications', (msg) => deliver(msg, onEvent));
      if (connectedBefore) onReconnect?.();
      connectedBefore = true;
    },
  });
  client.activate();
  return {
    close: () => {
      void client.deactivate();
    },
  };
}

/**
 * Connect and subscribe to the caller's personal queue only ({@code /user/queue/notifications}),
 * with no game topic — used by the lobby to wait for a {@code MATCH_FOUND} push before any game
 * exists (spec §9/§15, M12.1). {@code onReady} fires on the first connect: the caller joins the
 * matchmaking queue only once subscribed, so a pairing pushed right after joining can't be missed
 * (the simple broker has no durable queue for an unsubscribed destination). A reconnect
 * re-subscribes but does not re-fire {@code onReady} — a dropped socket doesn't remove the caller
 * from the server-side queue, so re-joining would be wrong.
 *
 * <p>Known limitation (same non-durable-broker class as the M9 single-instance note): if the socket
 * is down at the instant the server pairs this caller, the {@code MATCH_FOUND} push is dropped and
 * there is no queue-status endpoint to re-poll on reconnect, so the waiter can miss its match. The
 * grace/turn-clock forfeit bounds the fallout; a durable broker or a "my pending match" query would
 * close it (revisit with the PvP reconnect work in M12.2).
 */
export function subscribeToNotifications(
  onEvent: (event: GameEvent) => void,
  onReady?: () => void,
): GameSubscription {
  let readyFired = false;
  const client = new Client({
    brokerURL: socketUrl(),
    connectHeaders: { Authorization: `Bearer ${getToken() ?? ''}` },
    reconnectDelay: 2000,
    onConnect: () => {
      client.subscribe('/user/queue/notifications', (msg) => deliver(msg, onEvent));
      if (!readyFired) {
        readyFired = true;
        onReady?.();
      }
    },
  });
  client.activate();
  return {
    close: () => {
      void client.deactivate();
    },
  };
}

function deliver(message: IMessage, onEvent: (event: GameEvent) => void): void {
  onEvent(JSON.parse(message.body) as GameEvent);
}

/** Same-origin WebSocket URL (wss in prod behind the reverse proxy, ws in dev through the Vite proxy). */
function socketUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${protocol}://${window.location.host}/ws`;
}
