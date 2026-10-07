import { describe, expect, it, vi } from 'vitest';
import { api, ApiError } from './client';
const respond = (body: unknown, status = 200) => vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: status >= 200 && status < 300, status, json: async () => body }));
describe('API client', () => {
  it('uses relative URLs', async () => { respond({ state: 'OK' }); await api.status(); expect(fetch).toHaveBeenCalledWith('/api/status', expect.anything()); });
  it('returns parsed status', async () => { respond({ state: 'LOCKED' }); expect((await api.status()).state).toBe('LOCKED'); });
  it('includes online=true', async () => { respond([]); await api.devices(true); expect(fetch).toHaveBeenCalledWith('/api/devices?online=true', expect.anything()); });
  it('includes online=false', async () => { respond([]); await api.devices(false); expect(fetch).toHaveBeenCalledWith('/api/devices?online=false', expect.anything()); });
  it('requests all devices without filter', async () => { respond([]); await api.devices(); expect(fetch).toHaveBeenCalledWith('/api/devices', expect.anything()); });
  it('encodes MAC paths', async () => { respond([]); await api.events('aa/bb'); expect(fetch).toHaveBeenCalledWith('/api/devices/aa%2Fbb/events', expect.anything()); });
  it('builds summary query', async () => { respond({}); await api.summary('2025-01-01T00:00:00Z', '2025-01-02T00:00:00Z'); expect(vi.mocked(fetch).mock.calls[0][0]).toContain('from=2025-01-01T00%3A00%3A00Z&to='); });
  it('builds timeseries group query', async () => { respond({}); await api.timeseries('day', 'a', 'b', 'ssid'); expect(vi.mocked(fetch).mock.calls[0][0]).toContain('granularity=day&from=a&to=b&groupBy=ssid'); });
  it('returns undefined for 204 without parsing', async () => { const json = vi.fn(); vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 204, json })); expect(await api.resetAuth()).toBeUndefined(); expect(json).not.toHaveBeenCalled(); expect(fetch).toHaveBeenCalledWith('/api/admin/router/reset-auth', expect.objectContaining({ method: 'POST' })); });
  it('exposes server error message and fields', async () => { respond({ status: 400, error: 'Bad Request', message: 'Bad range', path: '/api/usage', fieldErrors: { from: 'Invalid' } }, 400); await expect(api.status()).rejects.toMatchObject({ status: 400, message: 'Bad range', problem: { fieldErrors: { from: 'Invalid' } } }); });
  it('falls back for non-JSON server errors', async () => { vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503, json: async () => { throw Error('not JSON'); } })); await expect(api.status()).rejects.toEqual(expect.objectContaining({ status: 503, message: 'Request failed (503)' })); });
  it('uses ApiError class', () => expect(new ApiError(403, 'Denied')).toBeInstanceOf(Error));
});
