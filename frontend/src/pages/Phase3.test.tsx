import { StrictMode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App } from '../App';
import { api, session, TOKEN_KEY } from '../api/client';
import { mockFetch, renderRoute } from '../test/helpers';
import { mockResponse } from '../api/mock';
import { DeviceTable } from './Devices';
import { Settings } from './Settings';
import type { Device } from '../api/client';

beforeEach(() => {
  mockResponse('/api/devices/AA%3ABB%3ACC%3A00%3A11%3A01/alias', 'DELETE');
  mockResponse('/api/devices/AA%3ABB%3ACC%3A00%3A11%3A01/trusted', 'PUT', { trusted: false });
  mockResponse('/api/labels/SSID/5g', 'DELETE');
});
const devices = () => structuredClone(mockResponse('/api/devices')!.body as Device[]);
function app(path: string, strict = false) {
  return render(<MemoryRouter initialEntries={[path]}>{strict ? <StrictMode><App/></StrictMode> : <App/>}</MemoryRouter>);
}

describe('owner session', () => {
  it('redirects protected dashboard to login', () => { app('/'); expect(screen.getByRole('heading', { name: 'Router Manager' })).toBeInTheDocument(); });
  it('shows error for invalid login', async () => { mockFetch(); app('/login'); fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'demo' } }); fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'bad' } }); fireEvent.click(screen.getByRole('button', { name: 'Log in' })); expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password'); });
  it('logs in with demo credentials and stores token', async () => { mockFetch(); app('/login'); fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'demo' } }); fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'demo' } }); fireEvent.click(screen.getByRole('button', { name: 'Log in' })); expect(await screen.findByRole('button', { name: 'Log out' })).toBeInTheDocument(); expect(localStorage.getItem(TOKEN_KEY)).toBe('demo-local-session'); });
  it('logs out and hides protected page', async () => { mockFetch(); session.set('demo-local-session'); app('/devices'); fireEvent.click(await screen.findByRole('button', { name: 'Log out' })); expect(screen.getByRole('heading', { name: 'Router Manager' })).toBeInTheDocument(); expect(localStorage.getItem(TOKEN_KEY)).toBeNull(); });
  it('waits for successful /me under StrictMode', async () => { session.set('demo-local-session'); let resolve!: (value: Response) => void; const fetcher = vi.fn(() => new Promise<Response>(r => { resolve = r; })); vi.stubGlobal('fetch', fetcher); app('/devices', true); expect(screen.getByText('Checking session…')).toBeInTheDocument(); expect(screen.queryByText('Devices')).not.toBeInTheDocument(); resolve({ ok: true, status: 200, json: async () => ({ username: 'demo' }) } as Response); expect(await screen.findByRole('heading', { name: 'Devices' })).toBeInTheDocument(); });
  it('clears session on /me 401', async () => { session.set('expired'); vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401, headers: { get: () => null }, json: async () => ({ message: 'expired' }) })); app('/devices'); expect(await screen.findByRole('heading', { name: 'Router Manager' })).toBeInTheDocument(); expect(localStorage.getItem(TOKEN_KEY)).toBeNull(); expect(screen.getByRole('status')).toHaveTextContent('Your session expired'); });
  it('shows a retry countdown for 429', async () => { vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 429, headers: { get: () => '20' }, json: async () => ({ message: 'Too many login attempts' }) })); app('/login'); fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'demo' } }); fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } }); fireEvent.click(screen.getByRole('button', { name: 'Log in' })); expect(await screen.findByRole('alert')).toHaveTextContent('Try again in 20s'); expect(screen.getByRole('button', { name: 'Log in' })).toBeDisabled(); });
});

describe('device owner actions', () => {
  it('marks unknown devices and filters them', () => { const list = devices(); list[0].trusted = true; renderRoute(<DeviceTable devices={list}/>); fireEvent.change(screen.getByLabelText('Filter status'), { target: { value: 'unknown' } }); expect(screen.getAllByRole('row')).toHaveLength(6); expect(screen.queryByText('Living room TV')).not.toBeInTheDocument(); });
  it('renames a device with Enter and retains the no-traffic notice elsewhere', async () => { const fetcher = mockFetch(); renderRoute(<DeviceTable devices={devices()}/>); fireEvent.click(screen.getByRole('button', { name: 'Rename Living room TV' })); const input = screen.getByLabelText('Alias for AA:BB:CC:00:11:01'); fireEvent.change(input, { target: { value: 'Family screen' } }); fireEvent.keyDown(input, { key: 'Enter' }); await waitFor(() => expect(fetcher).toHaveBeenCalledWith('/api/devices/AA%3ABB%3ACC%3A00%3A11%3A01/alias', expect.objectContaining({ method: 'PUT' }))); });
  it('cancels rename with Escape', () => { renderRoute(<DeviceTable devices={devices()}/>); fireEvent.click(screen.getByRole('button', { name: 'Rename Living room TV' })); fireEvent.keyDown(screen.getByLabelText('Alias for AA:BB:CC:00:11:01'), { key: 'Escape' }); expect(screen.queryByLabelText('Alias for AA:BB:CC:00:11:01')).not.toBeInTheDocument(); });
  it('limits alias input to 60 characters', () => { renderRoute(<DeviceTable devices={devices()}/>); fireEvent.click(screen.getByRole('button', { name: 'Rename Living room TV' })); expect(screen.getByLabelText('Alias for AA:BB:CC:00:11:01')).toHaveAttribute('maxLength', '60'); });
  it('toggles trusted devices', async () => { const fetcher = mockFetch(); renderRoute(<DeviceTable devices={devices()}/>); fireEvent.click(screen.getByLabelText('Trusted AA:BB:CC:00:11:01')); await waitFor(() => expect(fetcher).toHaveBeenCalledWith('/api/devices/AA%3ABB%3ACC%3A00%3A11%3A01/trusted', expect.objectContaining({ method: 'PUT' }))); });
});

describe('read-only settings', () => {
  it('shows read-only safety banner', async () => { mockFetch(); renderRoute(<Settings/>); expect(screen.getByText(/Read-only: this app never changes your router settings/)).toBeInTheDocument(); expect(await screen.findByText('Home-5G')).toBeInTheDocument(); });
  it('shows LAN link state and speed', async () => { mockFetch(); renderRoute(<Settings/>); expect(await screen.findByText('1000 Mbps')).toBeInTheDocument(); expect(screen.getByText('NoLink')).toBeInTheDocument(); });
  it('edits LAN port labels without changing router status', async () => { const fetcher = mockFetch(); renderRoute(<Settings/>); const input = await screen.findByLabelText('Label for LAN1'); fireEvent.change(input, { target: { value: 'Office switch' } }); fireEvent.click(screen.getAllByRole('button', { name: 'Save label' })[2]); await waitFor(() => expect(fetcher).toHaveBeenCalledWith('/api/labels/LAN_PORT/LAN1', expect.objectContaining({ method: 'PUT' }))); expect(screen.getByText('1000 Mbps')).toBeInTheDocument(); });
  it('sets a 60-character limit on label fields', async () => { mockFetch(); renderRoute(<Settings/>); expect(await screen.findByLabelText('Label for Home-5G')).toHaveAttribute('maxLength', '60'); });
  it('provides a visible unknown device badge', () => { renderRoute(<DeviceTable devices={devices()}/>); expect(screen.getAllByText('Unknown device')).toHaveLength(6); });
  it('saves SSID label to app only', async () => { const fetcher = mockFetch(); renderRoute(<Settings/>); const input = await screen.findByLabelText('Label for Home-5G'); fireEvent.change(input, { target: { value: 'Family network' } }); fireEvent.click(screen.getAllByRole('button', { name: 'Save label' })[0]); await waitFor(() => expect(fetcher).toHaveBeenCalledWith('/api/labels/SSID/5g', expect.objectContaining({ method: 'PUT' }))); expect((await api.summary('a', 'b')).bySsid[0].label).toBe('Family network'); });
});
