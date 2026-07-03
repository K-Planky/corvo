// Shapes mirrored from the server DTOs (spec §9). The client is a thin view over these — it never
// recomputes game logic, it only renders what the server reports and posts the moves the server
// says are legal.

export type Player = 'BLACK' | 'WHITE';
export type BotSide = 'BLACK' | 'WHITE';
export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD';

export type GameStatus =
  | 'IN_PROGRESS'
  | 'BLACK_WON'
  | 'WHITE_WON'
  | 'DRAW'
  | 'ABANDONED';

export interface User {
  id: string;
  username: string;
  eloRating: number;
}

export interface AuthResponse {
  token: string;
  user: User;
}

/** Result of joining the matchmaking queue (spec §9/§15). `MATCHED` means an opponent was already
 *  waiting and this call created the game (`gameId` set); `QUEUED` means we're now waiting for one. */
export interface MatchmakingStatus {
  status: 'QUEUED' | 'MATCHED';
  gameId: string | null;
}

export interface GameState {
  id: string;
  opponentType: string;
  blackPlayerId: string | null;
  whitePlayerId: string | null;
  botSide: BotSide;
  botDifficulty: Difficulty;
  // `cells` is the render-ready board: 64 chars indexed row*8+col, each 'B' | 'W' | '.'. We read
  // this rather than the raw bitboards, which can't survive JSON's 53-bit number precision.
  cells: string;
  currentTurn: Player;
  status: GameStatus;
  winnerId: string | null;
  moveCount: number;
  blackDiscs: number;
  whiteDiscs: number;
  legalMoves: number[];
}

/** True once the game has reached a terminal result. */
export function isOver(game: GameState): boolean {
  return game.status !== 'IN_PROGRESS';
}

/** The side the human plays — the side the bot does not. */
export function humanSide(game: GameState): Player {
  return game.botSide === 'BLACK' ? 'WHITE' : 'BLACK';
}
