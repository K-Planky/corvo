import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Lobby from './Lobby';
import type { GameState, MatchmakingStatus, User } from './types';
import type { GameEvent } from './ws';

// Mock the API boundary so the test drives the matchmaking flow without a server. `ApiError` must be
// a real class since Lobby does `instanceof ApiError`.
vi.mock('./api', () => ({
  createGame: vi.fn(),
  getGame: vi.fn(),
  deleteGame: vi.fn(),
  joinQueue: vi.fn(),
  leaveQueue: vi.fn(),
  listGames: vi.fn(),
  ApiError: class ApiError extends Error {},
}));
import { deleteGame, getGame, joinQueue, leaveQueue, listGames } from './api';

// Mock the WS boundary: capture the event + onReady callbacks so the test can fire the "subscribed"
// signal (which triggers the queue join) and simulate a MATCH_FOUND push.
let pushEvent: (event: GameEvent) => void = () => {};
let ready: () => void = () => {};
const closeSub = vi.fn();
vi.mock('./ws', () => ({
  subscribeToNotifications: vi.fn(
    (onEvent: (event: GameEvent) => void, onReady?: () => void) => {
      pushEvent = onEvent;
      ready = onReady ?? (() => {});
      return { close: closeSub };
    },
  ),
}));

const USER: User = { id: 'u1', username: 'alice', eloRating: 1000 };

function makeGame(id: string): GameState {
  return {
    id,
    opponentType: 'HUMAN_VS_HUMAN',
    blackPlayerId: 'u1',
    whitePlayerId: 'u2',
    botSide: 'WHITE',
    botDifficulty: 'EASY',
    cells: '.'.repeat(64),
    currentTurn: 'BLACK',
    status: 'IN_PROGRESS',
    winnerId: null,
    moveCount: 0,
    blackDiscs: 2,
    whiteDiscs: 2,
    legalMoves: [],
  };
}

function makeAiGame(id: string): GameState {
  return { ...makeGame(id), opponentType: 'HUMAN_VS_AI', whitePlayerId: null };
}

beforeEach(() => {
  vi.clearAllMocks();
  pushEvent = () => {};
  ready = () => {};
  vi.mocked(listGames).mockResolvedValue([]); // Lobby lists in-progress games on mount.
  vi.mocked(leaveQueue).mockResolvedValue(undefined);
});

describe('Lobby matchmaking', () => {
  it('subscribes then joins the queue and shows a waiting state', async () => {
    vi.mocked(joinQueue).mockResolvedValue({ status: 'QUEUED', gameId: null });
    render(<Lobby user={USER} onOpenGame={vi.fn()} onLogout={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Find match' }));
    expect(screen.getByText(/waiting for an opponent/i)).toBeInTheDocument();

    await act(async () => {
      ready(); // the socket connected — now the queue join fires.
    });
    expect(joinQueue).toHaveBeenCalledTimes(1);
  });

  it('opens the game from a MATCH_FOUND push after being QUEUED', async () => {
    vi.mocked(joinQueue).mockResolvedValue({ status: 'QUEUED', gameId: null });
    const onOpenGame = vi.fn();
    render(<Lobby user={USER} onOpenGame={onOpenGame} onLogout={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Find match' }));
    await act(async () => {
      ready();
    });
    expect(onOpenGame).not.toHaveBeenCalled();

    const matched = makeGame('g-push');
    act(() => pushEvent({ type: 'MATCH_FOUND', state: matched }));
    expect(onOpenGame).toHaveBeenCalledWith(matched);
  });

  it('opens immediately on a MATCHED response and does not double-open on the push', async () => {
    const matched = makeGame('g-sync');
    vi.mocked(joinQueue).mockResolvedValue({ status: 'MATCHED', gameId: 'g-sync' });
    vi.mocked(getGame).mockResolvedValue(matched);
    const onOpenGame = vi.fn();
    render(<Lobby user={USER} onOpenGame={onOpenGame} onLogout={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Find match' }));
    await act(async () => {
      ready();
    });
    await waitFor(() => expect(onOpenGame).toHaveBeenCalledWith(matched));
    expect(getGame).toHaveBeenCalledWith('g-sync');

    // The joiner also receives a MATCH_FOUND push for the same game — it must not open a second time.
    act(() => pushEvent({ type: 'MATCH_FOUND', state: matched }));
    expect(onOpenGame).toHaveBeenCalledTimes(1);
  });

  it('falls back to the MATCH_FOUND push when the post-MATCHED fetch fails', async () => {
    const matched = makeGame('g-fetchfail');
    vi.mocked(joinQueue).mockResolvedValue({ status: 'MATCHED', gameId: 'g-fetchfail' });
    vi.mocked(getGame).mockRejectedValue(new Error('boom'));
    const onOpenGame = vi.fn();
    render(<Lobby user={USER} onOpenGame={onOpenGame} onLogout={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Find match' }));
    await act(async () => {
      ready();
    });
    await waitFor(() => expect(getGame).toHaveBeenCalledWith('g-fetchfail'));
    expect(onOpenGame).not.toHaveBeenCalled(); // fetch failed — not aborted, still waiting on the push.

    act(() => pushEvent({ type: 'MATCH_FOUND', state: matched }));
    expect(onOpenGame).toHaveBeenCalledWith(matched);
  });

  it('does not open a match that resolves after the user cancels', async () => {
    let resolveJoin!: (v: MatchmakingStatus) => void;
    vi.mocked(joinQueue).mockReturnValue(
      new Promise<MatchmakingStatus>((r) => {
        resolveJoin = r;
      }),
    );
    vi.mocked(getGame).mockResolvedValue(makeGame('g-late'));
    const onOpenGame = vi.fn();
    render(<Lobby user={USER} onOpenGame={onOpenGame} onLogout={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Find match' }));
    await act(async () => {
      ready(); // join POST is now in flight (pending).
    });
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    // The join resolves MATCHED only now — after the cancel; it must not yank the user into a game.
    await act(async () => {
      resolveJoin({ status: 'MATCHED', gameId: 'g-late' });
    });
    expect(onOpenGame).not.toHaveBeenCalled();
    await waitFor(() => expect(leaveQueue).toHaveBeenCalled());
  });

  it('leaves the queue when the user cancels', async () => {
    vi.mocked(joinQueue).mockResolvedValue({ status: 'QUEUED', gameId: null });
    render(<Lobby user={USER} onOpenGame={vi.fn()} onLogout={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Find match' }));
    await act(async () => {
      ready();
    });

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(leaveQueue).toHaveBeenCalledTimes(1));
    expect(screen.getByRole('button', { name: 'Find match' })).toBeInTheDocument();
  });

  it('leaves the queue when the screen unmounts while waiting', async () => {
    vi.mocked(joinQueue).mockResolvedValue({ status: 'QUEUED', gameId: null });
    const { unmount } = render(<Lobby user={USER} onOpenGame={vi.fn()} onLogout={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Find match' }));
    await act(async () => {
      ready();
    });

    unmount();
    await waitFor(() => expect(leaveQueue).toHaveBeenCalledTimes(1));
  });
});

describe('Lobby Resume — delete', () => {
  it('deletes a single-player game after confirmation and removes its row', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.mocked(listGames).mockResolvedValue([makeAiGame('g1')]);
    vi.mocked(deleteGame).mockResolvedValue(undefined);
    render(<Lobby user={USER} onOpenGame={vi.fn()} onLogout={vi.fn()} />);

    const del = await screen.findByRole('button', { name: /delete match/i });
    await userEvent.click(del);

    expect(confirm).toHaveBeenCalled();
    await waitFor(() => expect(deleteGame).toHaveBeenCalledWith('g1'));
    // The row is gone — with no games left, the Resume card disappears too.
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /delete match/i })).not.toBeInTheDocument(),
    );
    expect(screen.queryByText('Resume')).not.toBeInTheDocument();
  });

  it('does nothing when the user cancels the confirmation', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    vi.mocked(listGames).mockResolvedValue([makeAiGame('g1')]);
    render(<Lobby user={USER} onOpenGame={vi.fn()} onLogout={vi.fn()} />);

    const del = await screen.findByRole('button', { name: /delete match/i });
    await userEvent.click(del);

    expect(confirm).toHaveBeenCalled();
    expect(deleteGame).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /delete match/i })).toBeInTheDocument();
  });

  it('offers no delete control for a multiplayer game', async () => {
    vi.mocked(listGames).mockResolvedValue([makeGame('pvp1')]); // HUMAN_VS_HUMAN
    render(<Lobby user={USER} onOpenGame={vi.fn()} onLogout={vi.fn()} />);

    // The resume row renders, but there is no delete affordance for a PvP match.
    await screen.findByText(/vs opponent/i);
    expect(screen.queryByRole('button', { name: /delete match/i })).not.toBeInTheDocument();
  });
});
