import { StrictMode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Login } from './Login';
import { App } from '../App';
import { TOKEN_KEY } from '../api/client';
import { mockResponse } from '../api/mock';

const response = (status: number, body: unknown, retry?: string) => ({ ok: status < 400, status, headers: { get: () => retry ?? null }, json: async () => body });
function fetchMode(required: boolean, setupStatus = 201, setupMessage = '') {
  const fn = vi.fn(async (url: string) => {
    if (url === '/api/auth/setup-status') return response(200, { setupRequired: required });
    if (url === '/api/auth/setup') return response(setupStatus, setupStatus === 201 ? { username: 'owner' } : { message: setupMessage }, setupStatus === 429 ? '15' : undefined);
    if (url === '/api/auth/login') return response(200, { token: 'owner-token', username: 'owner' });
    if (url === '/api/auth/me') return response(200, { username: 'owner' });
    const fallback = mockResponse(url);
    return response(fallback?.status ?? 200, fallback?.body ?? []);
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}
function mount(full = false, strict = false) {
  return render(<MemoryRouter initialEntries={['/login']}>{strict ? <StrictMode>{full ? <App/> : <Login/>}</StrictMode> : full ? <App/> : <Login/>}</MemoryRouter>);
}
async function fill(password = 'long-password', confirm = password) {
  fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'owner' } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } });
  fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: confirm } });
  fireEvent.click(screen.getByRole('button', { name: 'Create owner account' }));
}

describe('first-run owner form', () => {
  it('shows loading while status is pending', () => { vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {}))); mount(); expect(screen.getByRole('status')).toHaveTextContent('Checking owner account'); });
  it('shows create form when setup is required', async () => { fetchMode(true); mount(); expect(await screen.findByRole('heading', { name: 'Create your owner account' })).toBeInTheDocument(); });
  it('explains router credentials remain in .env', async () => { fetchMode(true); mount(); expect(await screen.findByText(/Router credentials are NOT entered here/)).toHaveTextContent('.env'); });
  it('shows login when setup is not required', async () => { fetchMode(false); mount(); expect(await screen.findByRole('button', { name: 'Log in' })).toBeInTheDocument(); expect(screen.queryByLabelText('Confirm password')).not.toBeInTheDocument(); });
  it('shows backend unreachable when status fails', async () => { vi.stubGlobal('fetch', vi.fn().mockRejectedValue(Error('offline'))); mount(); expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the Router Manager backend'); });
  it('enforces minimum ten characters without submitting', async () => { const fn = fetchMode(true); mount(); await fill('shortpass'); expect(screen.getByRole('alert')).toHaveTextContent('at least 10'); expect(fn).toHaveBeenCalledTimes(1); });
  it('requires matching passwords', async () => { const fn = fetchMode(true); mount(); await fill('long-password', 'another-long-password'); expect(screen.getByRole('alert')).toHaveTextContent('Passwords do not match'); expect(fn).toHaveBeenCalledTimes(1); });
  it('toggles password visibility', async () => { fetchMode(true); mount(); await screen.findByLabelText('Password'); fireEvent.click(screen.getByRole('button', { name: 'Show password' })); expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'text'); fireEvent.click(screen.getByRole('button', { name: 'Hide password' })); expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password'); });
  it('creates owner, logs in automatically and opens dashboard', async () => { const fn = fetchMode(true); mount(true); await fill(); expect(await screen.findByRole('button', { name: 'Log out' })).toBeInTheDocument(); expect(localStorage.getItem(TOKEN_KEY)).toBe('owner-token'); expect(fn.mock.calls.map(call => call[0])).toEqual(expect.arrayContaining(['/api/auth/setup', '/api/auth/login'])); });
  it('shows server validation errors', async () => { fetchMode(true, 400, 'Invalid username'); mount(); await fill(); expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username'); });
  it('handles conflict by switching to sign in', async () => { fetchMode(true, 409, 'Setup has already been completed'); mount(); await fill(); expect(await screen.findByRole('alert')).toHaveTextContent('already created, please sign in'); expect(screen.getByRole('button', { name: 'Log in' })).toBeInTheDocument(); });
  it('displays 429 with retry countdown', async () => { fetchMode(true, 429, 'Too many setup requests'); mount(); await fill(); expect(await screen.findByRole('alert')).toHaveTextContent('Try again in 15s'); expect(screen.getByRole('button', { name: 'Create owner account' })).toBeDisabled(); });
  it('deduplicates StrictMode status fetch', async () => { const fn = fetchMode(true); mount(false, true); await screen.findByRole('heading', { name: 'Create your owner account' }); expect(fn).toHaveBeenCalledTimes(1); });
  it('prevents duplicate submit while pending', async () => { const fn = fetchMode(true); mount(); await screen.findByLabelText('Username'); await fill(); await waitFor(() => expect(fn.mock.calls.filter(call => call[0] === '/api/auth/setup')).toHaveLength(1)); });
});
