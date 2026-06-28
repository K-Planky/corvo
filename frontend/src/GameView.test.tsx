import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GameView from './GameView';
import type { GameState } from './types';

// Mock the API boundary so the test exercises render + click → submit → re-render without a server.
vi.mock('./api', () => ({
  submitMove: vi.fn(),
  ApiError: class ApiError extends Error {},
}));
import { submitMove } from './api';

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

// State the server returns after Black plays d3 (19): the placed disc plus the flipped d4 (27)
// give Black four discs to White's one. (We don't model a bot reply here — the test only checks the
// client re-renders whatever state the server returns.)
const AFTER_D3: GameState = {
  ...OPENING,
  cells: (() => {
    const c = OPENING.cells.split('');
    c[19] = 'B'; // the placement
    c[27] = 'B'; // d4 flipped from White to Black
    return c.join('');
  })(),
  currentTurn: 'BLACK',
  moveCount: 2,
  blackDiscs: 4,
  whiteDiscs: 1,
  legalMoves: [18, 34],
};

describe('GameView', () => {
  beforeEach(() => {
    vi.mocked(submitMove).mockReset();
  });

  it('renders the board from the cells string', () => {
    const { container } = render(<GameView initial={OPENING} onExit={() => {}} />);
    // Scope to the board so the scoreboard's mini discs aren't counted.
    expect(container.querySelectorAll('.board .disc-black')).toHaveLength(2);
    expect(container.querySelectorAll('.board .disc-white')).toHaveLength(2);
    expect(screen.getByText('Your move')).toBeInTheDocument();
  });

  it('submits a move on a legal square and re-renders from the response', async () => {
    vi.mocked(submitMove).mockResolvedValue(AFTER_D3);
    render(<GameView initial={OPENING} onExit={() => {}} />);

    // d3 is square 19; the Board labels squares with their algebraic coordinate.
    await userEvent.click(screen.getByLabelText('d3'));

    await waitFor(() =>
      expect(submitMove).toHaveBeenCalledWith('g1', { position: 19 }),
    );
    // The scoreboard reflects the server's post-move state (Black up to 4).
    await waitFor(() => expect(screen.getByText('4')).toBeInTheDocument());
  });

  it('offers a Pass action when the human has no legal move', () => {
    const stuck: GameState = { ...OPENING, legalMoves: [] };
    render(<GameView initial={stuck} onExit={() => {}} />);
    expect(
      screen.getByRole('button', { name: /pass/i }),
    ).toBeInTheDocument();
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
