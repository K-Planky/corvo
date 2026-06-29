// STOMP-over-WebSocket client (spec §9, M8). The server computes the bot's reply off-thread and
// pushes it here, so the client renders live moves from these events rather than from the move POST's
// response. Auth mirrors REST: the JWT rides in the STOMP CONNECT frame (the handshake is anonymous).

import { Client, type IMessage } from '@stomp/stompjs';
import { getToken } from './api';
import type { GameState } from './types';

export type GameEventType = 'MOVE_MADE' | 'GAME_OVER' | 'YOUR_TURN';

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
 */
export function subscribeToGame(gameId: string, onEvent: (event: GameEvent) => void): GameSubscription {
  const client = new Client({
    brokerURL: socketUrl(),
    connectHeaders: { Authorization: `Bearer ${getToken() ?? ''}` },
    reconnectDelay: 2000,
    onConnect: () => {
      client.subscribe(`/topic/games/${gameId}`, (msg) => deliver(msg, onEvent));
      client.subscribe('/user/queue/notifications', (msg) => deliver(msg, onEvent));
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
