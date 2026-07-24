import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import App from './App';
import type { GameState, User } from './types';

// Mock the API boundary so App's boot flow (rehydrate user → discover in-progress game) runs without
// a server. Child screens are stubbed below so this suite tests only App's top-level routing.
vi.mock('./api', () => ({
  getToken: vi.fn(() => 'tok'),
  me: vi.fn(),
  listGames: vi.fn(),
  getGame: vi.fn(),
  logout: vi.fn(),
}));
import { getGame, listGames, me } from './api';

// Stub the three top-level screens with identifiable markers so we can assert which one App renders.
vi.mock('./Auth', () => ({ default: () => <div>AUTH</div> }));
vi.mock('./Lobby', () => ({ default: () => <div>LOBBY</div> }));
vi.mock('./GameView', () => ({
  default: ({ initial }: { initial: GameState }) => <div>GAMEVIEW:{initial.id}</div>,
}));

const USER: User = { id: 'me', username: 'me', eloRating: 1000 };

function game(id: string, opponentType: GameState['opponentType']): GameState {
  return {
    id,
    opponentType,
    blackPlayerId: 'me',
    whitePlayerId: opponentType === 'HUMAN_VS_HUMAN' ? 'opp' : null,
    botSide: opponentType === 'HUMAN_VS_AI' ? 'WHITE' : null,
    botDifficulty: opponentType === 'HUMAN_VS_AI' ? 'EASY' : null,
    cells: '.'.repeat(64),
    currentTurn: 'BLACK',
    status: 'IN_PROGRESS',
    winnerId: null,
    moveCount: 0,
    blackDiscs: 2,
    whiteDiscs: 2,
    legalMoves: [],
    blackTimeRemainingMs: null,
    whiteTimeRemainingMs: null,
  };
}

beforeEach(() => {
  vi.mocked(me).mockReset().mockResolvedValue(USER);
  vi.mocked(listGames).mockReset();
  vi.mocked(getGame).mockReset();
  sessionStorage.clear();
});

describe('App boot routing', () => {
  it('force-restores a live PvP match on refresh even without a marker (it is inescapable)', async () => {
    const pvp = game('p1', 'HUMAN_VS_HUMAN');
    vi.mocked(listGames).mockResolvedValue([pvp]);
    vi.mocked(getGame).mockResolvedValue(pvp);

    render(<App />);

    await waitFor(() => expect(screen.getByText('GAMEVIEW:p1')).toBeInTheDocument());
    expect(getGame).toHaveBeenCalledWith('p1');
    expect(screen.queryByText('LOBBY')).not.toBeInTheDocument();
  });

  it('prefers a live PvP match over a vs-AI game (that is the one you are locked into)', async () => {
    const ai = game('a1', 'HUMAN_VS_AI');
    const pvp = game('p1', 'HUMAN_VS_HUMAN');
    // List order puts the AI game first; the PvP game must still win.
    vi.mocked(listGames).mockResolvedValue([ai, pvp]);
    vi.mocked(getGame).mockResolvedValue(pvp);

    render(<App />);

    await waitFor(() => expect(screen.getByText('GAMEVIEW:p1')).toBeInTheDocument());
    expect(getGame).toHaveBeenCalledWith('p1');
  });

  it('restores the vs-AI board you were on when a marker points at it', async () => {
    const ai = game('a1', 'HUMAN_VS_AI');
    sessionStorage.setItem('othello.activeGame', 'a1');
    vi.mocked(listGames).mockResolvedValue([ai]);
    vi.mocked(getGame).mockResolvedValue(ai);

    render(<App />);

    await waitFor(() => expect(screen.getByText('GAMEVIEW:a1')).toBeInTheDocument());
    expect(getGame).toHaveBeenCalledWith('a1');
  });

  it('stays in the lobby after leaving a vs-AI game (still IN_PROGRESS but no marker)', async () => {
    // The bot game is still in progress server-side, but the user went back to the lobby (no marker),
    // so a refresh must NOT drag them back into it.
    vi.mocked(listGames).mockResolvedValue([game('a1', 'HUMAN_VS_AI')]);

    render(<App />);

    await waitFor(() => expect(screen.getByText('LOBBY')).toBeInTheDocument());
    expect(getGame).not.toHaveBeenCalled();
  });

  it('clears a stale marker and lands on the lobby when the marked game has finished', async () => {
    sessionStorage.setItem('othello.activeGame', 'a1'); // game since finished, not in the list
    vi.mocked(listGames).mockResolvedValue([]);

    render(<App />);

    await waitFor(() => expect(screen.getByText('LOBBY')).toBeInTheDocument());
    expect(getGame).not.toHaveBeenCalled();
    expect(sessionStorage.getItem('othello.activeGame')).toBeNull();
  });

  it('lands on the lobby when there is no in-progress game', async () => {
    vi.mocked(listGames).mockResolvedValue([]);

    render(<App />);

    await waitFor(() => expect(screen.getByText('LOBBY')).toBeInTheDocument());
    expect(getGame).not.toHaveBeenCalled();
  });
});
