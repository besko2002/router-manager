import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, type Device } from '../api/client';
import { Banner } from '../components/UI';
import { useResource } from '../lib/useResource';
import { dateTime, rate } from '../lib/format';

function DeviceActions({ device, onSaved }: { device: Device; onSaved: () => void }) {
  const [editing, setEditing] = useState(false);
  const [alias, setAlias] = useState(device.alias ?? '');
  const [error, setError] = useState('');
  async function save() {
    try {
      if (alias.trim()) await api.alias(device.mac, alias);
      else await api.deleteAlias(device.mac);
      setEditing(false); setError(''); onSaved();
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Rename failed'); }
  }
  async function toggle() {
    try { await api.trust(device.mac, !device.trusted); setError(''); onSaved(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not update trust'); }
  }
  return <><button type="button" aria-label={`Rename ${device.displayName ?? device.name ?? 'Unnamed device'}`} onClick={() => setEditing(true)}>Rename</button>
    {editing && <input aria-label={`Alias for ${device.mac}`} maxLength={60} value={alias} onChange={event => setAlias(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') void save(); if (event.key === 'Escape') { setEditing(false); setAlias(device.alias ?? ''); } }}/>} {editing && <button type="button" onClick={() => void save()}>Save name</button>}
    <label><input type="checkbox" aria-label={`Trusted ${device.mac}`} checked={!!device.trusted} onChange={() => void toggle()}/> Trusted</label>
    {!device.trusted && <span className="unknown-badge">Unknown device</span>}{error && <span role="alert">{error}</span>}</>;
}

export function DeviceTable({ devices, onSaved = () => {} }: { devices: Device[]; onSaved?: () => void }) {
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('all');
  const [sort, setSort] = useState<'name' | 'lastSeen'>('name');
  const shown = useMemo(() => devices.filter(d => (filter === 'all' || filter === 'unknown' && !d.trusted || String(d.online) === filter) && [d.displayName, d.name, d.mac, d.ip, d.vendor, d.ssid, d.port].some(v => v?.toLowerCase().includes(query.toLowerCase()))).sort((a, b) => Number(!!a.trusted) - Number(!!b.trusted) || (sort === 'name' ? (a.displayName ?? a.name ?? 'Unnamed device').localeCompare(b.displayName ?? b.name ?? 'Unnamed device') : b.lastSeen.localeCompare(a.lastSeen))), [devices, filter, query, sort]);
  return <><div className="controls"><label>Search devices<input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="Name, MAC, IP, SSID…"/></label><label>Status<select aria-label="Filter status" value={filter} onChange={e => setFilter(e.target.value)}><option value="all">All devices</option><option value="true">Online</option><option value="false">Offline</option><option value="unknown">Unknown devices</option></select></label><label>Sort by<select aria-label="Sort devices" value={sort} onChange={e => setSort(e.target.value as 'name' | 'lastSeen')}><option value="name">Name</option><option value="lastSeen">Last seen</option></select></label></div>
    {shown.length ? <div className="table-scroll"><table><caption className="sr-only">Router devices and negotiated link speeds</caption><thead><tr><th>Name</th><th>Trust and alias</th><th>MAC</th><th>IP</th><th>Connection</th><th>Link speed (TX / RX)</th><th>Last seen</th></tr></thead><tbody>{shown.map(d => <tr key={d.mac}><td><Link to={`/devices/${encodeURIComponent(d.mac)}`} className="device-name">{d.displayName ?? d.name ?? 'Unnamed device'}</Link><small>{d.vendor || 'Unknown vendor'} · <span className={d.online ? 'online' : 'offline'}>{d.online ? 'Online' : 'Offline'}</span></small></td><td><DeviceActions device={d} onSaved={onSaved}/></td><td className="mono">{d.mac}</td><td className="mono">{d.ip || '—'}</td><td>{d.kind === 'WIFI' ? <>Wi-Fi · {d.ssid || 'Unknown SSID'}<small aria-label={`Signal ${d.rssi ?? 'unknown'} dBm`}>{d.rssi == null ? 'Signal unknown' : <><span className="signal" aria-hidden="true">{'▂'.repeat(Math.max(1, Math.min(4, Math.ceil((d.rssi + 90) / 12))))}</span> {d.rssi} dBm</>}</small></> : `Wired · ${d.port || 'Unknown port'}`}</td><td>{rate(d.linkTxKbps)} / {rate(d.linkRxKbps)}</td><td>{dateTime(d.lastSeen)}</td></tr>)}</tbody></table></div> : <p className="empty">No devices match your search or filter.</p>}
  </>;
}
function DeviceDrawer({ mac, devices }: { mac: string; devices: Device[] }) {
  const device = devices.find(d => d.mac.toLowerCase() === mac.toLowerCase());
  const events = useResource(signal => api.events(mac, signal), mac);
  return <div className="drawer-backdrop"><aside className="drawer" aria-label="Device events"><div className="card-head"><h2>{device?.name || 'Unnamed device'}</h2><Link to="/devices" className="button" aria-label="Close device details">Close</Link></div><p className="mono">{mac}</p><Banner>Per-device traffic is not available from this router. Link rates are negotiated speeds, not traffic.</Banner><h3>Event timeline</h3>{events.loading && !events.data && <p role="status">Loading events…</p>}{events.error && <Banner tone="error">{events.error} <button onClick={events.reload}>Retry</button></Banner>}{events.data && (events.data.length ? <ol className="timeline">{events.data.map((event, i) => <li key={`${event.at}-${i}`}><strong>{event.type.replace('_', ' ')}</strong><time>{dateTime(event.at)}</time><p>{event.details || 'No details'}</p></li>)}</ol> : <p>No events recorded for this device.</p>)}</aside></div>;
}
export function Devices() {
  const { mac } = useParams();
  const resource = useResource(signal => api.devices(undefined, signal), 'all-devices');
  return <><div className="page-title"><div><span className="eyebrow">NETWORK INVENTORY</span><h1>Devices</h1><p>Connected and previously seen devices</p></div></div><Banner>Per-device traffic is not available from this router. Usage is available per Wi-Fi network (SSID) and LAN port. Link speeds are negotiated speeds, not traffic.</Banner><div className="card"><div className="card-head"><h2>Device list</h2><span className="muted">{resource.data?.length ?? 0} known</span></div>{resource.loading && !resource.data && <p role="status">Loading devices…</p>}{resource.error && <Banner tone="error">{resource.error} <button onClick={resource.reload}>Retry</button></Banner>}{resource.data && <DeviceTable devices={resource.data} onSaved={resource.reload}/>}</div>{mac && resource.data && <DeviceDrawer mac={decodeURIComponent(mac)} devices={resource.data}/>}</>;
}
