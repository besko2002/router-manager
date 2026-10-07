import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, session, TOKEN_KEY } from './client';
import { mockResponse } from './mock';
import { mockFetch } from '../test/helpers';

beforeEach(() => { mockResponse('/api/labels/SSID/5g', 'DELETE'); mockResponse('/api/devices/AA%3ABB%3ACC%3A00%3A11%3A01/alias', 'DELETE'); mockResponse('/api/devices/AA%3ABB%3ACC%3A00%3A11%3A01/trusted', 'PUT', { trusted: false }); });

describe('typed authenticated API', () => {
  it('saves bearer token to localStorage', () => { session.set('demo'); expect(localStorage.getItem(TOKEN_KEY)).toBe('demo'); });
  it('deletes bearer token on logout', () => { session.set('demo'); session.clear(); expect(localStorage.getItem(TOKEN_KEY)).toBeNull(); });
  it('sends bearer token for protected status', async () => { session.set('demo'); const fetcher = mockFetch(); await api.status(); expect(fetcher).toHaveBeenCalledWith('/api/status', expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer demo' }) })); });
  it('does not send bearer header without token', async () => { const fetcher = mockFetch(); await api.status(); expect(fetcher.mock.calls[0][1]?.headers).not.toHaveProperty('Authorization'); });
  it('logs in using JSON credentials', async () => { const fetcher = mockFetch(); expect((await api.login('demo', 'demo')).token).toBe('demo-local-session'); expect(fetcher.mock.calls[0][1]?.body).toBe(JSON.stringify({ username: 'demo', password: 'demo' })); });
  it('reports invalid login without storing a token', async () => { mockFetch(); await expect(api.login('demo', 'invalid')).rejects.toMatchObject({ status: 401 }); expect(session.token()).toBeNull(); });
  it('retrieves current username', async () => { mockFetch(); expect((await api.me()).username).toBe('demo'); });
  it('sets JSON content type for alias', async () => { const fetcher = mockFetch(); await api.alias('AA:BB:CC:00:11:01', 'My TV'); expect(fetcher.mock.calls[0][1]?.headers).toMatchObject({ 'Content-Type': 'application/json' }); });
  it('encodes slash in MAC for alias', async () => { const fetcher = mockFetch(); await api.alias('aa/bb', 'Test').catch(() => {}); expect(fetcher.mock.calls[0][0]).toBe('/api/devices/aa%2Fbb/alias'); });
  it('sends trimmed alias for device', async () => { mockFetch(); expect((await api.alias('AA:BB:CC:00:11:01', ' Family TV ')).alias).toBe('Family TV'); });
  it('deletes alias using DELETE', async () => { const fetcher = mockFetch(); await api.deleteAlias('AA:BB:CC:00:11:01'); expect(fetcher.mock.calls[0][1]?.method).toBe('DELETE'); });
  it('marks a device trusted with a boolean', async () => { const fetcher = mockFetch(); await api.trust('AA:BB:CC:00:11:01', true); expect(fetcher.mock.calls[0][1]?.body).toBe('{"trusted":true}'); });
  it('removes trust without deleting the device', async () => { mockFetch(); expect((await api.trust('AA:BB:CC:00:11:01', false)).trusted).toBe(false); });
  it('saves SSID label using JSON body', async () => { const fetcher = mockFetch(); await api.label('SSID', '5g', 'Family'); expect(fetcher.mock.calls[0][1]?.body).toBe('{"label":"Family"}'); });
  it('retrieves saved SSID label in summary', async () => { mockFetch(); await api.label('SSID', '5g', 'Family'); expect((await api.summary('a', 'b')).bySsid[0].label).toBe('Family'); });
  it('retrieves saved SSID label in timeseries', async () => { mockFetch(); await api.label('SSID', '5g', 'Family'); expect((await api.timeseries('day', 'a', 'b', 'ssid')).series[0].label).toBe('Family'); });
  it('deletes SSID label', async () => { mockFetch(); await api.label('SSID', '5g', 'Family'); await api.deleteLabel('SSID', '5g'); expect((await api.summary('a', 'b')).bySsid[0].label).toBeNull(); });
  it('encodes source ID in label path', async () => { const fetcher = mockFetch(); await api.label('SSID', 'guest 5g', 'Guest'); expect(fetcher.mock.calls[0][0]).toBe('/api/labels/SSID/guest%205g'); });
  it('retrieves Wi-Fi settings without router write', async () => { const fetcher = mockFetch(); expect((await api.wifi()).readOnly).toBe(true); expect(fetcher.mock.calls[0][1]?.method).toBeUndefined(); });
  it('retrieves LAN ports with link speed', async () => { mockFetch(); expect((await api.ports()).items[0].linkSpeedMbps).toBe(1000); });
  it('provides connected-device count per Wi-Fi network', async () => { mockFetch(); expect((await api.wifi()).items[0].connectedDeviceCount).toBe(2); });
  it('retains Wi-Fi security mode', async () => { mockFetch(); expect((await api.wifi()).items[0].securityMode).toBe('WPA2'); });
  it('retains LAN link status', async () => { mockFetch(); expect((await api.ports()).items[1].status).toBe('NoLink'); });
  it('retains last seen and trust metadata', async () => { mockFetch(); expect((await api.devices())[0]).toMatchObject({ online: true, trusted: false, displayName: 'Living room TV' }); });
  it('clears session and records notice on protected 401', async () => { session.set('expired'); vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401, headers: { get: () => null }, json: async () => ({ message: 'Expired' }) })); await expect(api.me()).rejects.toBeInstanceOf(ApiError); expect(session.token()).toBeNull(); expect(sessionStorage.getItem('router-session-notice')).toMatch(/expired/i); });
  it('does not clear existing session on incorrect login', async () => { session.set('another-token'); mockFetch(); await expect(api.login('demo', 'wrong')).rejects.toBeInstanceOf(ApiError); expect(session.token()).toBe('another-token'); });
  it('exposes Retry-After as a number', async () => { vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 429, headers: { get: () => '42' }, json: async () => ({ message: 'Wait' }) })); await expect(api.login('demo', 'bad')).rejects.toMatchObject({ status: 429, retryAfter: 42 }); });
});
