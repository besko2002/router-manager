import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, act } from '@testing-library/react';
import { StrictMode } from 'react';
import { StatusCard, Dashboard } from './Dashboard';
import { mockFetch, renderRoute } from '../test/helpers';
import type { Status } from '../api/client';
const status = (state: Status['state']): Status => ({ state, lastOkAt: null, lastError: null, model: 'ZTE', firmware: '1.0', uptimeS: 3600, dslDownCurrentKbps: 1000, dslDownMaxKbps: 2000, dslUpCurrentKbps: 500, dslUpMaxKbps: 1000, onlineDevices: 4, lastPollAt: null });
describe('status card', () => {
  it.each(['OK', 'AUTH_FAILED', 'LOCKED', 'UNREACHABLE'] as const)('shows %s state', state => { render(<StatusCard status={status(state)}/>); expect(screen.getByText(state.replace('_', ' '))).toBeInTheDocument(); });
  it('shows DSL and uptime', () => { render(<StatusCard status={status('OK')}/>); expect(screen.getByText('0d 1h 0m')).toBeInTheDocument(); expect(screen.getByText('1.0 Mbps / 2.0 Mbps')).toBeInTheDocument(); });
  it('explains failed login', () => { render(<StatusCard status={status('AUTH_FAILED')}/>); expect(screen.getByText(/router may lock logins/i)).toBeInTheDocument(); });
  it('explains lockout', () => { render(<StatusCard status={status('LOCKED')}/>); expect(screen.getByText(/sign-in locked/i)).toBeInTheDocument(); });
  it('requires confirmation before reset', () => { const reset = vi.fn(); render(<StatusCard status={status('LOCKED')} onReset={reset}/>); fireEvent.click(screen.getByRole('button', { name: 'Reset and retry' })); expect(screen.getByRole('dialog')).toHaveTextContent('locks logins'); expect(reset).not.toHaveBeenCalled(); });
  it('cancels reset', () => { const reset = vi.fn(); render(<StatusCard status={status('LOCKED')} onReset={reset}/>); fireEvent.click(screen.getByRole('button', { name: 'Reset and retry' })); fireEvent.click(screen.getByRole('button', { name: 'Cancel' })); expect(reset).not.toHaveBeenCalled(); });
  it('confirms reset and shows toast', async () => { const reset = vi.fn().mockResolvedValue(undefined); render(<StatusCard status={status('LOCKED')} onReset={reset}/>); fireEvent.click(screen.getByRole('button', { name: 'Reset and retry' })); fireEvent.click(screen.getByRole('button', { name: 'Confirm reset' })); await waitFor(() => expect(reset).toHaveBeenCalledOnce()); expect(await screen.findByText(/Authentication reset/)).toBeInTheDocument(); });
  it('reports failed reset', async () => { render(<StatusCard status={status('LOCKED')} onReset={vi.fn().mockRejectedValue(Error('Try later'))}/>); fireEvent.click(screen.getByRole('button', { name: 'Reset and retry' })); fireEvent.click(screen.getByRole('button', { name: 'Confirm reset' })); expect(await screen.findByText('Try later')).toBeInTheDocument(); });
});
describe('dashboard', () => {
  it('shows router and online count', async () => { mockFetch(); renderRoute(<Dashboard/>); expect(await screen.findByText('ZTE ZXHN H298A')).toBeInTheDocument(); expect(screen.getByText('4')).toBeInTheDocument(); });
  it('labels approximate usage', async () => { mockFetch(); renderRoute(<Dashboard/>); expect(await screen.findByText(/Approximate household total/)).toBeInTheDocument(); });
  it('shows device events', async () => { mockFetch(); renderRoute(<Dashboard/>); expect(await screen.findByText('Device events')).toBeInTheDocument(); expect(screen.getAllByText('ONLINE').length).toBeGreaterThan(0); });
  it('shows loading and API error', async () => { vi.stubGlobal('fetch', vi.fn().mockRejectedValue(Error('Network down'))); renderRoute(<Dashboard/>); expect(await screen.findByRole('alert')).toHaveTextContent('Network down'); });
  it('in StrictMode ultimately loads after first effect cleanup', async () => { mockFetch(); renderRoute(<StrictMode><Dashboard/></StrictMode>); expect(await screen.findByText('ZTE ZXHN H298A')).toBeInTheDocument(); });
  it('refreshes every 15 seconds', async () => { const fn = mockFetch(); vi.useFakeTimers(); renderRoute(<Dashboard/>); await act(async () => { await vi.advanceTimersByTimeAsync(0); }); const before = fn.mock.calls.filter(c => c[0] === '/api/status').length; await act(async () => { await vi.advanceTimersByTimeAsync(15000); }); expect(fn.mock.calls.filter(c => c[0] === '/api/status').length).toBe(before + 1); });
  it('pauses while the tab is hidden', async () => { const fn = mockFetch(); vi.useFakeTimers(); renderRoute(<Dashboard/>); await act(async () => { await vi.advanceTimersByTimeAsync(0); }); const before = fn.mock.calls.length; Object.defineProperty(document, 'hidden', { configurable: true, value: true }); await act(async () => { await vi.advanceTimersByTimeAsync(30000); }); expect(fn.mock.calls.length).toBe(before); Object.defineProperty(document, 'hidden', { configurable: true, value: false }); });
  it('does not overlap pending polls', async () => { const fn = vi.fn(() => new Promise<Response>(() => {})); vi.stubGlobal('fetch', fn); vi.useFakeTimers(); renderRoute(<Dashboard/>); await act(async () => { await vi.advanceTimersByTimeAsync(45000); }); expect(fn).toHaveBeenCalledTimes(4); });
});
