import { describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render } from '@testing-library/react';
import { Devices, DeviceTable } from './Devices';
import { mockFetch, renderRoute } from '../test/helpers';
import { mockResponse } from '../api/mock';
import type { Device } from '../api/client';
const devices = mockResponse('/api/devices')!.body as Device[];
describe('device table', () => {
  it('renders six devices', () => { renderRoute(<DeviceTable devices={devices}/>); expect(screen.getAllByRole('row')).toHaveLength(7); });
  it('falls back for an unnamed device', () => { renderRoute(<DeviceTable devices={devices}/>); expect(screen.getByText('Unnamed device')).toBeInTheDocument(); });
  it('filters online devices', () => { renderRoute(<DeviceTable devices={devices}/>); fireEvent.change(screen.getByLabelText('Filter status'), { target: { value: 'true' } }); expect(screen.getAllByRole('row')).toHaveLength(5); });
  it('filters offline devices', () => { renderRoute(<DeviceTable devices={devices}/>); fireEvent.change(screen.getByLabelText('Filter status'), { target: { value: 'false' } }); expect(screen.getAllByRole('row')).toHaveLength(3); });
  it('searches name', () => { renderRoute(<DeviceTable devices={devices}/>); fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Printer' } }); expect(screen.getAllByRole('row')).toHaveLength(2); });
  it('searches MAC', () => { renderRoute(<DeviceTable devices={devices}/>); fireEvent.change(screen.getByRole('searchbox'), { target: { value: '11:03' } }); expect(screen.getByText('NAS')).toBeInTheDocument(); });
  it('searches SSID', () => { renderRoute(<DeviceTable devices={devices}/>); fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Home-2G' } }); expect(screen.getAllByRole('row')).toHaveLength(3); });
  it('sorts by last seen', () => { renderRoute(<DeviceTable devices={devices}/>); fireEvent.change(screen.getByLabelText('Sort devices'), { target: { value: 'lastSeen' } }); expect(screen.getAllByRole('row')[1]).toHaveTextContent('Living room TV'); });
  it('shows empty search state', () => { renderRoute(<DeviceTable devices={devices}/>); fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'impossible' } }); expect(screen.getByText(/No devices match/)).toBeInTheDocument(); });
  it('labels link speeds', () => { renderRoute(<DeviceTable devices={devices}/>); expect(screen.getByText('Link speed (TX / RX)')).toBeInTheDocument(); });
  it('shows wired ports and Wi-Fi signal', () => { renderRoute(<DeviceTable devices={devices}/>); expect(screen.getByText('Wired · LAN1')).toBeInTheDocument(); expect(screen.getByLabelText('Signal -55 dBm')).toBeInTheDocument(); });
});
describe('device page', () => {
  it('always shows no per-device traffic notice', async () => { mockFetch(); renderRoute(<Devices/>); expect(screen.getByText(/Per-device traffic is not available/)).toBeInTheDocument(); await screen.findByText('Living room TV'); });
  it('opens device events drawer from link', async () => { mockFetch(); render(<MemoryRouter initialEntries={['/devices']}><Routes><Route path="/devices" element={<Devices/>}/><Route path="/devices/:mac" element={<Devices/>}/></Routes></MemoryRouter>); fireEvent.click(await screen.findByRole('link', { name: 'NAS' })); expect(await screen.findByRole('complementary', { name: 'Device events' })).toBeInTheDocument(); expect(screen.getByText('Event timeline')).toBeInTheDocument(); });
  it('renders event entries', async () => { mockFetch(); render(<MemoryRouter initialEntries={['/devices/AA%3ABB%3ACC%3A00%3A11%3A03']}><Routes><Route path="/devices/:mac" element={<Devices/>}/></Routes></MemoryRouter>); await waitFor(() => expect(screen.getByText('NEW DEVICE')).toBeInTheDocument()); });
});
