import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GameView, { STAGE_MS } from './GameView';
import type { GameState } from './types';
import type { GameEvent } from './ws';

// Mock the API boundary so the test exercises render + click → submit → re-render without a server.
vi.mock('./api', () => ({
  submitMove: vi.fn(),
  ApiError: class ApiError extends Error {},
}));
import { submitMove } from './api';

// Mock the WebSocket boundary: capture the event callback so the test can simulate server pushes
// (the bot's reply, game over) and assert the client re-renders from them.
let pushEvent: (event: GameEvent) => void = () => {};
const closeSub = vi.fn();
vi.mock('./ws', () => ({
  subscribeToGame: vi.fn((_gameId: string, onEvent: (event: GameEvent) => void) => {
    pushEvent = onEvent;
    return { close: closeSub };
  }),
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
};

// State the move POST returns after Black plays d3 (19): the placement plus the flipped d4 (27). It
// is now the bot's (White) turn — the response carries only the human's move (no synchronous reply).
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
    c[19] = 'W'; // (illustrative flip — the client only renders what it's told)
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
    pushEvent = () => {};
  });

  it('renders the board from the cells string', () => {
    const { container } = render(<GameView initial={OPENING} onExit={() => {}} />);
    // Scope to the board so the scoreboard's mini discs aren't counted.
    expect(container.querySelectorAll('.board .disc-black')).toHaveLength(2);
    expect(container.querySelectorAll('.board .disc-white')).toHaveLength(2);
    expect(screen.getByText('Your move')).toBeInTheDocument();
  });

  it('submits a move, shows the bot thinking, then re-renders from the staged bot reply push', async () => {
    vi.mocked(submitMove).mockResolvedValue(AFTER_HUMAN_D3);
    render(<GameView initial={OPENING} onExit={() => {}} />);

    // d3 is square 19; the Board labels squares with their algebraic coordinate.
    await userEvent.click(screen.getByLabelText('d3'));

    await waitFor(() => expect(submitMove).toHaveBeenCalledWith('g1', { position: 19 }));
    // The move POST response (human move only) re-renders: Black up to 4, and it's the bot's turn.
    await waitFor(() => expect(screen.getByText('Bot is thinking…')).toBeInTheDocument());
    expect(screen.getByText('4')).toBeInTheDocument();

    // The bot's reply arrives over the socket immediately after the human move, but is staged behind
    // its animation window — synchronously after the push the board must NOT have jumped to it yet.
    act(() => pushEvent({ type: 'MOVE_MADE', state: AFTER_BOT_REPLY }));
    expect(screen.queryByText('Your move')).not.toBeInTheDocument();

    // Once the stage window (STAGE_MS) elapses, the client re-renders from the push.
    await waitFor(() => expect(screen.getByText('Your move')).toBeInTheDocument(), {
      timeout: STAGE_MS + 1000,
    });
    // Both scores now read 3 (the pushed state), proving the board came from the push, not the POST.
    expect(screen.getAllByText('3')).toHaveLength(2);
  });

  it('renders the terminal result from a GAME_OVER push', async () => {
    render(<GameView initial={OPENING} onExit={() => {}} />);

    act(() =>
      pushEvent({
        type: 'GAME_OVER',
        state: { ...OPENING, status: 'BLACK_WON', legalMoves: [], blackDiscs: 40, whiteDiscs: 24 },
      }),
    );

    await waitFor(() => expect(screen.getByText(/you win/i)).toBeInTheDocument());
    expect(screen.getByText('40')).toBeInTheDocument(); // final disc count from the push
  });

  it('offers a Pass action when the human has no legal move', () => {
    const stuck: GameState = { ...OPENING, legalMoves: [] };
    render(<GameView initial={stuck} onExit={() => {}} />);
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
    render(<GameView initial={won} onExit={() => {}} />);
    expect(screen.getByText(/you win/i)).toBeInTheDocument();
  });
});
