import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GameView, { STAGE_MS } from './GameView';
import type { GameState, User } from './types';
import type { GameEvent } from './ws';

// Mock the API boundary so the test exercises render + click → submit → re-render without a server.
vi.mock('./api', () => ({
  submitMove: vi.fn(),
  getGame: vi.fn(),
  getUserStats: vi.fn(),
  ApiError: class ApiError extends Error {},
}));
import { getGame, submitMove } from './api';

const USER: User = { id: 'u1', username: 'me', eloRating: 1000 };

// Mock the WebSocket boundary: capture the event callback so the test can simulate server pushes
// (the bot's reply, game over) and assert the client re-renders from them; also capture onReconnect
// so a socket reconnect (which re-GETs authoritative state) can be simulated.
let pushEvent: (event: GameEvent) => void = () => {};
let reconnect: () => void = () => {};
const closeSub = vi.fn();
vi.mock('./ws', () => ({
  subscribeToGame: vi.fn(
    (_gameId: string, onEvent: (event: GameEvent) => void, onReconnect?: () => void) => {
      pushEvent = onEvent;
      reconnect = onReconnect ?? (() => {});
      return { close: closeSub };
    },
  ),
}));

// '.' x 64 with the four centre discs of the opening position set (index = row*8+col).
function emptyCells(): string[] {
  return Array.from({ length: 64 }, () => '.');
}

const OPENING: GameState = {
  id: 'g1',
  opponentType: 'HUMAN_VS_AI',
  blackPlayerId: 'u1',
  whitePlayerId: null,
  botSide: 'WHITE',
  botDifficulty: 'EASY',
  cells: (() => {
    const c = emptyCells();
    c[27] = 'W';
    c[28] = 'B';
    c[35] = 'B';
    c[36] = 'W';
    return c.join('');
  })(),
  currentTurn: 'BLACK', // the human (Black) moves first
  status: 'IN_PROGRESS',
  winnerId: null,
  moveCount: 0,
  blackDiscs: 2,
  whiteDiscs: 2,
  legalMoves: [19, 26, 37, 44], // d3, c4, f5, e6
  blackTimeRemainingMs: null, // vs-AI is unclocked
  whiteTimeRemainingMs: null,
};

// State the move POST returns after Black plays d3 (19): the placement plus the flipped d4 (27). It
// is now the bot's (White) turn, the response carries only the human's move (no synchronous reply).
const AFTER_HUMAN_D3: GameState = {
  ...OPENING,
  cells: (() => {
    const c = OPENING.cells.split('');
    c[19] = 'B';
    c[27] = 'B';
    return c.join('');
  })(),
  currentTurn: 'WHITE',
  moveCount: 1,
  blackDiscs: 4,
  whiteDiscs: 1,
  legalMoves: [], // not the human's turn
};

// What the bot's reply pushes over the socket: White plays, turn returns to the human (Black).
const AFTER_BOT_REPLY: GameState = {
  ...AFTER_HUMAN_D3,
  cells: (() => {
    const c = AFTER_HUMAN_D3.cells.split('');
    c[20] = 'W'; // some bot placement
    c[19] = 'W'; // (illustrative flip, the client only renders what it's told)
    return c.join('');
  })(),
  currentTurn: 'BLACK',
  moveCount: 2,
  blackDiscs: 3,
  whiteDiscs: 3,
  legalMoves: [18, 34],
};

describe('GameView', () => {
  beforeEach(() => {
    vi.mocked(submitMove).mockReset();
    vi.mocked(getGame).mockReset();
    pushEvent = () => {};
    reconnect = () => {};
  });

  it('renders the board from the cells string', () => {
    const { container } = render(<GameView initial={OPENING} user={USER} onExit={() => {}} />);
    // Scope to the board so the scoreboard's mini discs aren't counted.
    expect(container.querySelectorAll('.board .disc-black')).toHaveLength(2);
    expect(container.querySelectorAll('.board .disc-white')).toHaveLength(2);
    expect(screen.getByText('Your move')).toBeInTheDocument();
  });

  it('submits a move, shows the bot thinking, then re-renders from the staged bot reply push', async () => {
    vi.mocked(submitMove).mockResolvedValue(AFTER_HUMAN_D3);
    render(<GameView initial={OPENING} user={USER} onExit={() => {}} />);

    // d3 is square 19; the Board labels squares with their algebraic coordinate.
    await userEvent.click(screen.getByLabelText('d3'));

    await waitFor(() => expect(submitMove).toHaveBeenCalledWith('g1', { position: 19 }));
    // The move POST response (human move only) re-renders: Black up to 4, and it's the bot's turn.
    await waitFor(() => expect(screen.getByText('Bot is thinking…')).toBeInTheDocument());
    expect(screen.getByText('4')).toBeInTheDocument();

    // The bot's reply arrives over the socket immediately after the human move, but is staged behind
    // its animation window, synchronously after the push the board must NOT have jumped to it yet.
    act(() => pushEvent({ type: 'MOVE_MADE', state: AFTER_BOT_REPLY }));
    expect(screen.queryByText('Your move')).not.toBeInTheDocument();

    // Once the stage window (STAGE_MS) elapses, the client re-renders from the push.
    await waitFor(() => expect(screen.getByText('Your move')).toBeInTheDocument(), {
      timeout: STAGE_MS + 1000,
    });
    // Both scores now read 3 (the pushed state), proving the board came from the push, not the POST.
    expect(screen.getAllByText('3')).toHaveLength(2);
  });

  it('shows the human move first even when a fast bot reply races ahead of the POST response', async () => {
    // Near the end of a game the bot's search is near-instant, so its MOVE_MADE push (the board after
    // BOTH moves, moveCount 2) can arrive over the socket before the move POST (human move only,
    // moveCount 1) resolves. Regression: the post-bot board was shown in one step, skipping the
    // human's own move. Hold the POST resolution so we can deliver the push first.
    let resolveMove!: (s: GameState) => void;
    vi.mocked(submitMove).mockReturnValue(new Promise<GameState>((res) => (resolveMove = res)));
    render(<GameView initial={OPENING} user={USER} onExit={() => {}} />);

    await userEvent.click(screen.getByLabelText('d3'));
    await waitFor(() => expect(submitMove).toHaveBeenCalledWith('g1', { position: 19 }));

    // Bot reply races in while the human-move POST is still pending. It must be held, not shown: the
    // board is still the opening (Black 2), not the post-bot board (Black 3).
    act(() => pushEvent({ type: 'MOVE_MADE', state: AFTER_BOT_REPLY }));
    expect(screen.getAllByText('2')).toHaveLength(2);

    // POST resolves: the human-move state shows first, Black 4, bot's turn, not the bot reply.
    await act(async () => resolveMove(AFTER_HUMAN_D3));
    await waitFor(() => expect(screen.getByText('Bot is thinking…')).toBeInTheDocument());
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.queryByText('Your move')).not.toBeInTheDocument();

    // Only after the stage window does the raced-in bot reply land.
    await waitFor(() => expect(screen.getByText('Your move')).toBeInTheDocument(), {
      timeout: STAGE_MS + 1000,
    });
    expect(screen.getAllByText('3')).toHaveLength(2);
  });

  it('renders the terminal result from a GAME_OVER push', async () => {
    render(<GameView initial={OPENING} user={USER} onExit={() => {}} />);

    act(() =>
      pushEvent({
        type: 'GAME_OVER',
        state: { ...OPENING, status: 'BLACK_WON', legalMoves: [], blackDiscs: 40, whiteDiscs: 24 },
      }),
    );

    // vs-AI has no clocks/disconnect, so a terminal is never a forfeit even when it lands at an
    // unchanged moveCount, the plain win copy, not "opponent forfeited".
    await waitFor(() => expect(screen.getByText('You win! 🎉')).toBeInTheDocument());
    expect(screen.queryByText(/forfeit/i)).not.toBeInTheDocument();
    expect(screen.getByText('40')).toBeInTheDocument(); // final disc count from the push
  });

  it('re-GETs authoritative state on a socket reconnect, catching a move missed during the gap', async () => {
    // The socket dropped and reconnected; a move (the bot's reply, moveCount 2) was applied while it
    // was down and its push missed. On reconnect the client re-GETs state, the board must catch up to
    // it purely from the fetch, no push involved (spec §15 reconnect: GET current state + re-subscribe).
    vi.mocked(getGame).mockResolvedValue(AFTER_BOT_REPLY);
    render(<GameView initial={OPENING} user={USER} onExit={() => {}} />);
    expect(screen.getAllByText('2')).toHaveLength(2); // opening board before reconnect

    await act(async () => reconnect());

    await waitFor(() => expect(getGame).toHaveBeenCalledWith('g1'));
    // Both scores read 3 (AFTER_BOT_REPLY), the board caught up from the re-fetch, and it's the
    // human's turn again, all from GET rather than a push.
    await waitFor(() => expect(screen.getByText('Your move')).toBeInTheDocument());
    expect(screen.getAllByText('3')).toHaveLength(2);
  });

  it('keeps the ← Lobby exit available for an in-progress vs-AI game (only PvP locks in)', () => {
    render(<GameView initial={OPENING} user={USER} onExit={() => {}} />);
    expect(screen.getByRole('button', { name: /lobby/i })).toBeInTheDocument();
  });

  it('offers a Pass action when the human has no legal move', () => {
    const stuck: GameState = { ...OPENING, legalMoves: [] };
    render(<GameView initial={stuck} user={USER} onExit={() => {}} />);
    expect(screen.getByRole('button', { name: /pass/i })).toBeInTheDocument();
  });

  it('shows the result when the game is over', () => {
    const won: GameState = {
      ...OPENING,
      status: 'BLACK_WON',
      legalMoves: [],
      blackDiscs: 40,
      whiteDiscs: 24,
    };
    render(<GameView initial={won} user={USER} onExit={() => {}} />);
    expect(screen.getByText(/you win/i)).toBeInTheDocument();
  });
});
