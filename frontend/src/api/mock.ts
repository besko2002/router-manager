import type { Device, DeviceEvent, UsageSummary, TimeSeries, GroupBy, Granularity, Status } from './client';

const now = () => new Date();
const ago = (hours: number) => new Date(Date.now() - hours * 3600000).toISOString();
const devices: Device[] = [
  { mac: 'AA:BB:CC:00:11:01', name: 'Living room TV', vendor: 'Samsung', kind: 'WIFI', ssid: 'Home-5G', ip: '192.168.1.12', rssi: -55, linkTxKbps: 433000, linkRxKbps: 390000, port: null, firstSeen: ago(600), lastSeen: ago(0.02), online: true },
  { mac: 'AA:BB:CC:00:11:02', name: 'Work laptop', vendor: 'Apple', kind: 'WIFI', ssid: 'Home-5G', ip: '192.168.1.18', rssi: -47, linkTxKbps: 866000, linkRxKbps: 866000, port: null, firstSeen: ago(400), lastSeen: ago(0.02), online: true },
  { mac: 'AA:BB:CC:00:11:03', name: 'NAS', vendor: 'Synology', kind: 'WIRED', ssid: null, ip: '192.168.1.20', rssi: null, linkTxKbps: 1000000, linkRxKbps: 1000000, port: 'LAN1', firstSeen: ago(1600), lastSeen: ago(0.02), online: true },
  { mac: 'AA:BB:CC:00:11:04', name: null, vendor: null, kind: 'WIFI', ssid: 'Home-2G', ip: '192.168.1.36', rssi: -72, linkTxKbps: 72000, linkRxKbps: 72000, port: null, firstSeen: ago(10), lastSeen: ago(0.02), online: true },
  { mac: 'AA:BB:CC:00:11:05', name: 'Printer', vendor: 'HP', kind: 'WIFI', ssid: 'Home-2G', ip: '192.168.1.40', rssi: -66, linkTxKbps: 144000, linkRxKbps: 144000, port: null, firstSeen: ago(700), lastSeen: ago(28), online: false },
  { mac: 'AA:BB:CC:00:11:06', name: 'Game console', vendor: 'Sony', kind: 'WIRED', ssid: null, ip: '192.168.1.22', rssi: null, linkTxKbps: 1000000, linkRxKbps: 1000000, port: 'LAN2', firstSeen: ago(800), lastSeen: ago(5), online: false },
];
const events: DeviceEvent[] = [
  { at: ago(0.02), type: 'ONLINE', details: 'Connected to the router' },
  { at: ago(5), type: 'IP_CHANGED', details: 'IP address changed' },
  { at: ago(10), type: 'NEW_DEVICE', details: 'First seen on network' },
];
const notes = ['Router counters are per SSID and LAN port, not per device.', 'Household total is approximate; counters may reset when the router restarts.'];
const labels = new Map<string, string>();
const mockToken = 'demo-local-session';
// VITE_MOCK_SETUP=1 or ?mockSetup=1 starts the offline demo without an owner.
let mockOwner: { username: string; password: string } | null = null;
let mockSetupMode = false;
function setupMode(url: URL) {
  const requested = import.meta.env.VITE_MOCK_SETUP === '1' || url.searchParams.get('mockSetup') === '1' || window.location.search.includes('mockSetup=1');
  if (requested !== mockSetupMode) { mockOwner = null; mockSetupMode = requested; }
  return requested;
}
export function mockResponse(rawUrl: string, method = 'GET', body: Record<string, unknown> = {}): { status: number; body?: unknown } | null {
  const url = new URL(rawUrl, 'http://localhost');
  if (!url.pathname.startsWith('/api/')) return null;
  const setup = setupMode(url);
  if (url.pathname === '/api/auth/setup-status') return { status: 200, body: { setupRequired: setup && !mockOwner } };
  if (url.pathname === '/api/auth/setup' && method === 'POST') {
    if (!setup || mockOwner) return { status: 409, body: { status: 409, message: 'Setup has already been completed' } };
    if (!body.username || typeof body.password !== 'string' || body.password.length < 10) return { status: 400, body: { status: 400, message: 'Password must have at least 10 characters' } };
    mockOwner = { username: String(body.username).trim().toLowerCase(), password: body.password };
    return { status: 201, body: { username: mockOwner.username } };
  }
  if (url.pathname === '/api/auth/login' && method === 'POST') return (setup ? mockOwner?.username === body.username && mockOwner?.password === body.password : body.username === 'demo' && body.password === 'demo') ? { status: 200, body: { token: mockToken, username: setup ? mockOwner?.username : 'demo' } } : { status: 401, body: { status: 401, message: 'Invalid username or password' } };
  if (url.pathname === '/api/auth/me') return { status: 200, body: { username: setup ? mockOwner?.username : 'demo' } };
  const deviceMatch = url.pathname.match(/^\/api\/devices\/([^/]+)\/(alias|trusted)$/);
  if (deviceMatch) {
    const device = devices.find(d => d.mac === decodeURIComponent(deviceMatch[1]));
    if (!device) return { status: 404, body: { message: 'Device not found' } };
    if (method === 'PUT' && deviceMatch[2] === 'alias') device.alias = String(body.alias ?? '').trim();
    if (method === 'DELETE' && deviceMatch[2] === 'alias') device.alias = null;
    if (method === 'PUT' && deviceMatch[2] === 'trusted') device.trusted = body.trusted === true;
    device.displayName = device.alias || device.name || 'Unnamed device';
    return { status: method === 'DELETE' ? 204 : 200, body: device };
  }
  const labelMatch = url.pathname.match(/^\/api\/labels\/(SSID|LAN_PORT)\/([^/]+)$/);
  if (labelMatch) {
    const key = `${labelMatch[1]}:${decodeURIComponent(labelMatch[2])}`;
    if (method === 'DELETE') { labels.delete(key); return { status: 204 }; }
    if (method === 'PUT') { labels.set(key, String(body.label ?? '')); return { status: 200, body: { label: labels.get(key) } }; }
  }
  if (method === 'POST' && url.pathname === '/api/admin/router/reset-auth') return { status: 204 };
  if (method !== 'GET') return { status: 405, body: { status: 405, error: 'Method Not Allowed', message: 'Unsupported method', path: url.pathname } };
  if (url.pathname === '/api/router/wifi') return { status: 200, body: { readOnly: true, note: 'Read-only: this app never changes your router settings', items: [
    { id: '5g', name: 'Home-5G', enabled: true, securityMode: 'WPA2', channel: 44, band: '5 GHz', connectedDeviceCount: 2, label: labels.get('SSID:5g') ?? null },
    { id: '2g', name: 'Home-2G', enabled: true, securityMode: 'WPA2', channel: 6, band: '2.4 GHz', connectedDeviceCount: 2, label: labels.get('SSID:2g') ?? null },
  ] } };
  if (url.pathname === '/api/router/ports') return { status: 200, body: { readOnly: true, note: 'Read-only: this app never changes your router settings', items: [
    { id: 'LAN1', status: 'Up', linkSpeedMbps: 1000, label: labels.get('LAN_PORT:LAN1') ?? null },
    { id: 'LAN2', status: 'NoLink', linkSpeedMbps: null, label: labels.get('LAN_PORT:LAN2') ?? null },
  ] } };
  if (url.pathname === '/api/status') {
    const status: Status = { state: 'OK', lastOkAt: ago(0.02), lastError: null, model: 'ZTE ZXHN H298A', firmware: 'V1.0.0', uptimeS: 406422, dslDownCurrentKbps: 89000, dslDownMaxKbps: 105000, dslUpCurrentKbps: 19000, dslUpMaxKbps: 24000, onlineDevices: 4, lastPollAt: ago(0.02) };
    return { status: 200, body: status };
  }
  if (url.pathname === '/api/devices') return { status: 200, body: devices.filter(d => (!url.searchParams.has('online') || d.online === (url.searchParams.get('online') === 'true')) && (!url.searchParams.has('trusted') || !!d.trusted === (url.searchParams.get('trusted') === 'true'))).map(d => ({ ...d, displayName: d.alias || d.name || 'Unnamed device', trusted: !!d.trusted })) };
  if (/^\/api\/devices\/[^/]+\/events$/.test(url.pathname)) return { status: 200, body: events };
  if (url.pathname === '/api/usage/summary') {
    const summary: UsageSummary = { from: url.searchParams.get('from') ?? now().toISOString(), to: url.searchParams.get('to') ?? now().toISOString(), approximate: true, notes, total: { rxBytes: 19420000000, txBytes: 3710000000 }, bySsid: [{ id: '5g', name: 'Home-5G', rxBytes: 12600000000, txBytes: 2300000000 }, { id: '2g', name: 'Home-2G', rxBytes: 3200000000, txBytes: 620000000 }], byPort: [{ id: 'LAN1', name: 'LAN1', rxBytes: 2900000000, txBytes: 690000000 }, { id: 'LAN2', name: 'LAN2', rxBytes: 720000000, txBytes: 100000000 }] };
    summary.bySsid.forEach(row => { row.label = labels.get(`SSID:${row.id}`) ?? null; });
    summary.byPort.forEach(row => { row.label = labels.get(`LAN_PORT:${row.id}`) ?? null; });
    return { status: 200, body: summary };
  }
  if (url.pathname === '/api/usage/timeseries') {
    const granularity: Granularity = url.searchParams.get('granularity') === 'day' ? 'day' : 'hour';
    const groupBy: GroupBy = url.searchParams.get('groupBy') === 'ssid' ? 'ssid' : url.searchParams.get('groupBy') === 'port' ? 'port' : 'total';
    const to = Date.parse(url.searchParams.get('to') ?? '') || Date.now();
    const from = Date.parse(url.searchParams.get('from') ?? '') || to - 7 * 86400000;
    const step = granularity === 'day' ? 86400000 : 3600000;
    const count = Math.min(168, Math.max(1, Math.ceil((to - from) / step)));
    const groups = groupBy === 'ssid' ? [['5g', 'Home-5G', 0.65], ['2g', 'Home-2G', 0.2]] as const : groupBy === 'port' ? [['LAN1', 'LAN1', 0.4], ['LAN2', 'LAN2', 0.15]] as const : [['total', 'Household', 1]] as const;
    const series: TimeSeries = { granularity, groupBy, series: groups.map(([id, name, factor]) => ({ id, name, points: Array.from({ length: count }, (_, i) => ({ at: new Date(from + i * step).toISOString(), rxBytes: Math.round((38000000 + (Math.sin(i * 0.8) + 1) * 53000000) * factor), txBytes: Math.round((9000000 + (Math.cos(i * 0.5) + 1) * 9000000) * factor) })) })) };
    series.series.forEach(row => { row.label = labels.get(`${groupBy === 'ssid' ? 'SSID' : 'LAN_PORT'}:${row.id}`) ?? null; });
    return { status: 200, body: series };
  }
  return { status: 404, body: { status: 404, error: 'Not Found', message: 'No mock endpoint', path: url.pathname } };
}
