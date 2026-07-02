import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Auth from './Auth';
import type { AuthResponse } from './types';

// Mock the API boundary so the test exercises the form's client-side logic without a server.
vi.mock('./api', () => ({
  register: vi.fn(),
  login: vi.fn(),
  ApiError: class ApiError extends Error {},
}));
import { register, login } from './api';

const AUTH: AuthResponse = {
  token: 'jwt',
  user: { id: 'u1', username: 'alice', eloRating: 1200 },
};

beforeEach(() => {
  vi.mocked(register).mockReset().mockResolvedValue(AUTH);
  vi.mocked(login).mockReset().mockResolvedValue(AUTH);
});

async function switchToRegister(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Create one' }));
}

describe('Auth registration password confirmation', () => {
  it('blocks submit and shows an error when the passwords do not match', async () => {
    const user = userEvent.setup();
    render(<Auth onAuthenticated={vi.fn()} />);
    await switchToRegister(user);

    await user.type(screen.getByLabelText('Username'), 'alice');
    await user.type(screen.getByLabelText('Password'), 'correcthorse');
    await user.type(screen.getByLabelText('Confirm password'), 'correcthose');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(screen.getByText('Passwords do not match.')).toBeInTheDocument();
    expect(register).not.toHaveBeenCalled();
  });

  it('registers with (username, password) when the passwords match', async () => {
    const user = userEvent.setup();
    const onAuthenticated = vi.fn();
    render(<Auth onAuthenticated={onAuthenticated} />);
    await switchToRegister(user);

    await user.type(screen.getByLabelText('Username'), 'alice');
    await user.type(screen.getByLabelText('Password'), 'correcthorse');
    await user.type(screen.getByLabelText('Confirm password'), 'correcthorse');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(register).toHaveBeenCalledTimes(1);
    expect(register).toHaveBeenCalledWith('alice', 'correcthorse');
    expect(onAuthenticated).toHaveBeenCalledWith(AUTH.user);
    expect(screen.queryByText('Passwords do not match.')).not.toBeInTheDocument();
  });

  it('does not show a confirm field in login mode and logs in normally', async () => {
    const user = userEvent.setup();
    render(<Auth onAuthenticated={vi.fn()} />);

    expect(screen.queryByLabelText('Confirm password')).not.toBeInTheDocument();

    await user.type(screen.getByLabelText('Username'), 'alice');
    await user.type(screen.getByLabelText('Password'), 'correcthorse');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(login).toHaveBeenCalledWith('alice', 'correcthorse');
    expect(register).not.toHaveBeenCalled();
  });
});
