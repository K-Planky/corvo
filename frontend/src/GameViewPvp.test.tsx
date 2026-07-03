import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import GameView from './GameView';
import type { GameState, User } from './types';
import type { GameEvent } from './ws';

// Mock the API + WS boundaries the same way as GameView.test.tsx, but drive a human-vs-human game.
vi.mock('./api', () => ({
  submitMove: vi.fn(),
  getGame: vi.fn(),
  getUserStats: vi.fn(),
  ApiError: class ApiError extends Error {},
}));
import { getUserStats } from './api';

let pushEvent: (event: GameEvent) => void = () => {};
vi.mock('./ws', () => ({
  subscribeToGame: vi.fn((_gameId: string, onEvent: (event: GameEvent) => void) => {
    pushEvent = onEvent;
    return { close: vi.fn() };
  }),
}));

const USER: User = { id: 'me', username: 'me', eloRating: 1000 };

function openingCells(): string {
  const c = Array.from({ length: 64 }, () => '.');
  c[27] = 'W';
  c[28] = 'B';
  c[35] = 'B';
  c[36] = 'W';
  return c.join('');
}

// A human-vs-human game where the signed-in user plays White; Black (the opponent) moves first, so on
// mount it is the opponent's turn. Both time banks seeded at 2:00 (M10). botSide/botDifficulty null.
const PVP: GameState = {
  id: 'p1',
  opponentType: 'HUMAN_VS_HUMAN',
  blackPlayerId: 'opp',
  whitePlayerId: 'me',
  botSide: null,
  botDifficulty: null,
  cells: openingCells(),
  currentTurn: 'BLACK',
  status: 'IN_PROGRESS',
  winnerId: null,
  moveCount: 0,
  blackDiscs: 2,
  whiteDiscs: 2,
  legalMoves: [], // not the viewer's turn
  blackTimeRemainingMs: 120_000,
  whiteTimeRemainingMs: 120_000,
};

// An unclocked vs-AI game (viewer is Black), to prove no clock renders when the banks are null.
const VS_AI: GameState = {
  ...PVP,
  id: 'a1',
  opponentType: 'HUMAN_VS_AI',
  blackPlayerId: 'me',
  whitePlayerId: null,
  botSide: 'WHITE',
  botDifficulty: 'EASY',
  currentTurn: 'BLACK',
  legalMoves: [19, 26, 37, 44],
  blackTimeRemainingMs: null,
  whiteTimeRemainingMs: null,
};

beforeEach(() => {
  vi.mocked(getUserStats).mockReset();
  vi.mocked(getUserStats).mockResolvedValue({
    id: 'opp',
    username: 'bob',
    eloRating: 1000,
    gamesPlayed: 0,
    wins: 0,
    losses: 0,
    draws: 0,
    ratingHistory: [],
  });
  pushEvent = () => {};
});

describe('GameView (PvP)', () => {
  it('derives the viewer side from player ids and labels the opponent', async () => {
    const { container } = render(<GameView initial={PVP} user={USER} onExit={() => {}} />);

    // Viewer plays White (second score); the opponent (Black) is the first score.
    const scores = container.querySelectorAll('.scoreboard .score');
    expect(scores[1]).toHaveTextContent('you');
    expect(scores[0]).not.toHaveTextContent('you');

    // Opponent's turn ⇒ the PvP waiting label (not "Bot is thinking…").
    expect(screen.getByText('Waiting for opponent…')).toBeInTheDocument();

    // Opponent labelled by the fetched username.
    await waitFor(() => expect(getUserStats).toHaveBeenCalledWith('opp'));
    await waitFor(() => expect(screen.getByText(/vs bob/)).toBeInTheDocument());
  });

  it('renders both turn clocks for PvP and none for vs-AI', () => {
    const { container, unmount } = render(<GameView initial={PVP} user={USER} onExit={() => {}} />);
    expect(container.querySelectorAll('.score-clock')).toHaveLength(2);
    expect(screen.getAllByText('2:00')).toHaveLength(2);
    unmount();

    const ai = render(<GameView initial={VS_AI} user={USER} onExit={() => {}} />);
    expect(ai.container.querySelectorAll('.score-clock')).toHaveLength(0);
  });

  it('re-renders from an opponent MOVE_MADE push and flips to the viewer’s turn', async () => {
    render(<GameView initial={PVP} user={USER} onExit={() => {}} />);
    expect(screen.getByText('Waiting for opponent…')).toBeInTheDocument();

    // Opponent (Black) plays; now White (viewer) is to move with a legal placement.
    const afterOpp: GameState = {
      ...PVP,
      cells: (() => {
        const c = PVP.cells.split('');
        c[26] = 'B';
        c[27] = 'B';
        return c.join('');
      })(),
      currentTurn: 'WHITE',
      moveCount: 1,
      blackDiscs: 4,
      whiteDiscs: 1,
      legalMoves: [20],
    };
    act(() => pushEvent({ type: 'MOVE_MADE', state: afterOpp }));

    await waitFor(() => expect(screen.getByText('Your move')).toBeInTheDocument());
  });

  it('surfaces a no-move terminal (timeout/disconnect lapse) as a forfeit result', async () => {
    // Start mid-game at moveCount 5, opponent (Black) to move. Their clock lapses: a GAME_OVER arrives
    // at the SAME moveCount (no move played) with the viewer (White) winning.
    const live: GameState = { ...PVP, moveCount: 5 };
    render(<GameView initial={live} user={USER} onExit={() => {}} />);

    act(() =>
      pushEvent({
        type: 'GAME_OVER',
        state: { ...live, status: 'WHITE_WON', legalMoves: [] },
      }),
    );

    await waitFor(() => expect(screen.getByText(/opponent forfeited/i)).toBeInTheDocument());
  });

  it('surfaces a played-out terminal as a plain win (not a forfeit)', async () => {
    const live: GameState = { ...PVP, moveCount: 5 };
    render(<GameView initial={live} user={USER} onExit={() => {}} />);

    // A real final move advances the board to moveCount 6, then the redundant GAME_OVER follows.
    const finalState: GameState = {
      ...live,
      status: 'WHITE_WON',
      moveCount: 6,
      legalMoves: [],
      blackDiscs: 20,
      whiteDiscs: 44,
    };
    act(() => pushEvent({ type: 'MOVE_MADE', state: finalState }));
    act(() => pushEvent({ type: 'GAME_OVER', state: finalState }));

    await waitFor(() => expect(screen.getByText('You win! 🎉')).toBeInTheDocument());
    expect(screen.queryByText(/forfeit/i)).not.toBeInTheDocument();
  });

  it('shows a disconnect notice on OPPONENT_DISCONNECTED and clears it on OPPONENT_RECONNECTED', async () => {
    render(<GameView initial={PVP} user={USER} onExit={() => {}} />);
    // Let the opponent-name fetch settle first so its state update doesn't fire outside act().
    await waitFor(() => expect(getUserStats).toHaveBeenCalled());
    expect(screen.queryByText(/disconnected/i)).not.toBeInTheDocument();

    // The presence event carries the unchanged state; it must only toggle the notice, not the board.
    act(() => pushEvent({ type: 'OPPONENT_DISCONNECTED', state: PVP }));
    expect(screen.getByText(/opponent disconnected/i)).toBeInTheDocument();
    expect(screen.getAllByText('2')).toHaveLength(2); // board (disc counts) unchanged

    act(() => pushEvent({ type: 'OPPONENT_RECONNECTED', state: PVP }));
    expect(screen.queryByText(/disconnected/i)).not.toBeInTheDocument();
  });
});
