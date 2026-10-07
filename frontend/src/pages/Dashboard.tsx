import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, type Status, type DeviceEvent } from '../api/client';
import { Banner, Modal, Toast } from '../components/UI';
import { Chart } from '../components/Chart';
import { useResource } from '../lib/useResource';
import { bytes, dateTime, dayRange, duration, rate } from '../lib/format';

export function StatusCard({ status, onReset }: { status: Status; onReset?: () => Promise<void> }) {
  const [confirm, setConfirm] = useState(false);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const closeToast = useCallback(() => setMessage(null), []);
  async function reset() {
    if (!onReset) return;
    setPending(true);
    try { await onReset(); setConfirm(false); setMessage('Authentication reset. The router will be retried.'); }
    catch (cause) { setMessage(cause instanceof Error ? cause.message : 'Reset failed'); }
    finally { setPending(false); }
  }
  return <section className="card health"><div className="card-head"><div><span className="eyebrow">ROUTER HEALTH</span><h2>Connection status</h2></div><span className={`chip chip-${status.state.toLowerCase()}`}>{status.state.replace('_', ' ')}</span></div>
    {(status.state === 'AUTH_FAILED' || status.state === 'LOCKED') && <Banner tone="warning"><strong>Router sign-in {status.state === 'LOCKED' ? 'locked' : 'failed'}.</strong> The router may lock logins after failed attempts. Check credentials and wait for any lockout to expire before retrying. {status.lastError && <span>{status.lastError}</span>}<div><button className="button button-primary" onClick={() => setConfirm(true)}>Reset and retry</button></div></Banner>}
    {status.state === 'UNREACHABLE' && <Banner tone="error">Router unreachable. Check its connection. {status.lastError}</Banner>}
    <dl className="details-grid"><div><dt>Model</dt><dd>{status.model || '—'}</dd></div><div><dt>Firmware</dt><dd>{status.firmware || '—'}</dd></div><div><dt>Uptime</dt><dd>{duration(status.uptimeS)}</dd></div><div><dt>Last poll</dt><dd>{dateTime(status.lastPollAt)}</dd></div><div><dt>DSL download / max</dt><dd>{rate(status.dslDownCurrentKbps)} / {rate(status.dslDownMaxKbps)}</dd></div><div><dt>DSL upload / max</dt><dd>{rate(status.dslUpCurrentKbps)} / {rate(status.dslUpMaxKbps)}</dd></div></dl>
    {confirm && <Modal title="Reset router authentication?" onClose={() => setConfirm(false)}><p>The router locks logins after failed attempts. Verify the credentials and wait for any lockout before resetting and retrying. This clears the saved authentication state, not the router configuration.</p><div className="actions"><button className="button" onClick={() => setConfirm(false)}>Cancel</button><button className="button button-primary" disabled={pending} onClick={() => void reset()}>{pending ? 'Resetting…' : 'Confirm reset'}</button></div></Modal>}
    <Toast message={message} onClose={closeToast} />
  </section>;
}
export function Dashboard() {
  const today = dayRange(1);
  const resource = useResource(async signal => {
    const [status, devices, summary, series] = await Promise.all([api.status(signal), api.devices(undefined, signal), api.summary(today.from, today.to, signal), api.timeseries('hour', today.from, today.to, 'total', signal)]);
    const eventLists = await Promise.all(devices.slice(0, 6).map(async device => {
      try { return (await api.events(device.mac, signal)).map(event => ({ ...event, mac: device.mac, device: device.name || 'Unnamed device' })); }
      catch (error) { if (signal.aborted) throw error; return []; }
    }));
    return { status, devices, summary, series, events: eventLists.flat().sort((a, b) => b.at.localeCompare(a.at)).slice(0, 5) as (DeviceEvent & { mac: string; device: string })[] };
  }, 'dashboard', 15000);
  return <><div className="page-title"><div><span className="eyebrow">OVERVIEW</span><h1>Home network</h1><p>Live router health and household traffic</p></div><span className="refresh-note">Refreshes every 15s while visible</span></div>
    {resource.error && <Banner tone="error">{resource.error} <button className="button" onClick={resource.reload}>Retry</button></Banner>}
    {resource.loading && !resource.data ? <p role="status">Loading dashboard…</p> : resource.data && <>
      <StatusCard status={resource.data.status} onReset={async () => { await api.resetAuth(); resource.reload(); }} />
      <div className="metrics"><Link className="card metric" to="/devices"><span className="eyebrow">CONNECTED NOW</span><strong>{resource.data.status.onlineDevices}</strong><span>Online devices →</span></Link><div className="card metric"><span className="eyebrow">TODAY · APPROXIMATE</span><strong>{bytes(resource.data.summary.total.rxBytes)}</strong><span>Download ↓</span></div><div className="card metric"><span className="eyebrow">TODAY · APPROXIMATE</span><strong>{bytes(resource.data.summary.total.txBytes)}</strong><span>Upload ↑</span></div></div>
      <div className="two-col"><section className="card"><div className="card-head"><div><span className="eyebrow">HOURLY TRAFFIC</span><h2>Today's usage</h2></div><Link to="/usage">Explore usage →</Link></div><Chart compact data={resource.data.series} /><p className="muted">Approximate household total. Router counters are per Wi-Fi network (SSID) and LAN port, never per device.</p></section>
      <section className="card"><div className="card-head"><div><span className="eyebrow">LATEST ACTIVITY</span><h2>Device events</h2></div><Link to="/devices">All devices →</Link></div>{resource.data.events.length ? <ul className="event-list">{resource.data.events.map((event, i) => <li key={`${event.mac}-${event.at}-${i}`}><span className="event-dot"/><div><strong>{event.device}</strong><span>{event.type.replace('_', ' ')}</span></div><time>{dateTime(event.at)}</time></li>)}</ul> : <p>No recent events.</p>}</section></div>
    </>}
  </>;
}
