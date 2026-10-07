export interface Status {
  state: 'NEW' | 'OK' | 'AUTH_FAILED' | 'LOCKED' | 'UNREACHABLE';
  lastOkAt: string | null; lastPollAt: string | null; lastError: string | null;
  model: string | null; firmware: string | null; uptimeS: number | null;
  dslDownCurrentKbps: number | null; dslDownMaxKbps: number | null;
  dslUpCurrentKbps: number | null; dslUpMaxKbps: number | null;
  onlineDevices: number;
}
export interface Device {
  mac: string; name: string | null; vendor: string | null; kind: 'WIFI' | 'WIRED';
  ssid: string | null; ip: string | null; rssi: number | null;
  linkTxKbps: number | null; linkRxKbps: number | null; port: string | null;
  firstSeen: string; lastSeen: string; online: boolean;
  alias?: string | null; displayName?: string; trusted?: boolean;
}
export interface DeviceEvent { at: string; type: 'ONLINE' | 'OFFLINE' | 'IP_CHANGED' | 'NEW_DEVICE'; details: string | null }
export interface Counter { rxBytes: number; txBytes: number }
export interface UsageSummary {
  from: string; to: string; approximate: true; notes: string[]; total: Counter;
  bySsid: (Counter & { id: string; name: string; label?: string | null })[];
  byPort: (Counter & { id: string; name: string; label?: string | null })[];
}
export type Granularity = 'hour' | 'day';
export type GroupBy = 'total' | 'ssid' | 'port';
export interface TimeSeries {
  granularity: Granularity; groupBy: GroupBy;
  series: { id: string; name: string; label?: string | null; points: (Counter & { at: string })[] }[];
}
export interface ApiProblem { status: number; error: string; message: string; path: string; fieldErrors?: Record<string, string> }
const backendUnavailable = 'Cannot reach the Router Manager backend. Make sure it is running (see the README), then try again.';
export class ApiError extends Error {
  constructor(public status: number, message: string, public problem?: ApiProblem, public retryAfter?: number) { super(message); this.name = 'ApiError'; }
}
export const TOKEN_KEY = 'router-manager-token';
export const session = {
  token: () => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => { localStorage.setItem(TOKEN_KEY, token); window.dispatchEvent(new Event('router-session')); },
  clear: () => { localStorage.removeItem(TOKEN_KEY); window.dispatchEvent(new Event('router-session')); },
};
export interface Settings<T> { readOnly: true; note: string; items: T[] }
export interface Wifi { id: string; name: string; enabled: boolean; label: string | null; securityMode: string | null; channel: number | null; band: string | null; connectedDeviceCount: number }
export interface Port { id: string; status: string; linkSpeedMbps: number | null; label: string | null }
async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const token = session.token();
  let response: Response;
  try {
    response = await fetch(path, { ...options, headers: { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...options?.headers } });
  } catch (cause) {
    if (options?.signal?.aborted) throw cause;
    throw new ApiError(0, backendUnavailable);
  }
  if (!response.ok) {
    let problem: ApiProblem | undefined;
    try { problem = await response.json() as ApiProblem; } catch { /* server may not return JSON */ }
    const retryAfter = Number(response.headers?.get('Retry-After'));
    if (response.status === 401 && path !== '/api/auth/login') {
      sessionStorage.setItem('router-session-notice', 'Your session expired. Please log in again.');
      session.clear();
    }
    throw new ApiError(response.status, problem?.message || (response.status >= 500 && response.status <= 599 ? backendUnavailable : `Request failed (${response.status})`), problem,
      Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
export const api = {
  setupStatus: () => request<{ setupRequired: boolean }>('/api/auth/setup-status'),
  setup: (username: string, password: string) => request<{ username: string }>('/api/auth/setup', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }) }),
  login: (username: string, password: string) => request<{ token: string; username: string }>('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }) }),
  me: (signal?: AbortSignal) => request<{ username: string }>('/api/auth/me', { signal }),
  changePassword: (currentPassword: string, newPassword: string) => request<void>('/api/auth/change-password', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ currentPassword, newPassword }) }),
  alias: (mac: string, alias: string) => request<Device>(`/api/devices/${encodeURIComponent(mac)}/alias`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ alias }) }),
  deleteAlias: (mac: string) => request<void>(`/api/devices/${encodeURIComponent(mac)}/alias`, { method: 'DELETE' }),
  trust: (mac: string, trusted: boolean) => request<Device>(`/api/devices/${encodeURIComponent(mac)}/trusted`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ trusted }) }),
  label: (type: 'SSID' | 'LAN_PORT', id: string, label: string) => request<{ label: string }>(`/api/labels/${type}/${encodeURIComponent(id)}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ label }) }),
  deleteLabel: (type: 'SSID' | 'LAN_PORT', id: string) => request<void>(`/api/labels/${type}/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  wifi: (signal?: AbortSignal) => request<Settings<Wifi>>('/api/router/wifi', { signal }),
  ports: (signal?: AbortSignal) => request<Settings<Port>>('/api/router/ports', { signal }),
  status: (signal?: AbortSignal) => request<Status>('/api/status', { signal }),
  devices: (online?: boolean, signal?: AbortSignal) => request<Device[]>(`/api/devices${online === undefined ? '' : `?online=${online}`}`, { signal }),
  events: (mac: string, signal?: AbortSignal) => request<DeviceEvent[]>(`/api/devices/${encodeURIComponent(mac)}/events`, { signal }),
  summary: (from: string, to: string, signal?: AbortSignal) => request<UsageSummary>(`/api/usage/summary?${new URLSearchParams({ from, to })}`, { signal }),
  timeseries: (granularity: Granularity, from: string, to: string, groupBy: GroupBy, signal?: AbortSignal) => request<TimeSeries>(`/api/usage/timeseries?${new URLSearchParams({ granularity, from, to, groupBy })}`, { signal }),
  resetAuth: () => request<void>('/api/admin/router/reset-auth', { method: 'POST' }),
};
