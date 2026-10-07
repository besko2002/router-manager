import { useState } from 'react';
import { api, type GroupBy } from '../api/client';
import { Banner } from '../components/UI';
import { Chart } from '../components/Chart';
import { useResource } from '../lib/useResource';
import { bytes, dayRange } from '../lib/format';

export function Usage() {
  const [preset, setPreset] = useState('today');
  const [openedAt] = useState(() => new Date());
  const [groupBy, setGroupBy] = useState<GroupBy>('total');
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');
  const days = preset === '30 days' ? 30 : preset === '7 days' ? 7 : 1;
  const dates = dayRange(days, openedAt);
  const from = preset === 'custom' && customFrom ? new Date(`${customFrom}T00:00:00`).toISOString() : dates.from;
  const to = preset === 'custom' && customTo ? new Date(`${customTo}T23:59:59`).toISOString() : dates.to;
  const invalid = preset === 'custom' && (!customFrom || !customTo || from > to);
  const resource = useResource(async signal => {
    if (invalid) return null;
    const [summary, series] = await Promise.all([api.summary(from, to, signal), api.timeseries((preset === 'today' || preset === 'custom' && Date.parse(to) - Date.parse(from) <= 14 * 86400000) ? 'hour' : 'day', from, to, groupBy, signal)]);
    return { summary, series };
  }, `${from}:${to}:${groupBy}:${invalid}`);
  return <><div className="page-title"><div><span className="eyebrow">TRAFFIC ANALYTICS</span><h1>Internet usage</h1><p>Network and port counters — not per-device usage</p></div></div>
    <Banner tone="warning"><strong>Approximate household usage.</strong> The router does not provide bytes per device. Counters are available only per Wi-Fi network (SSID) and LAN port. Link rates are negotiated speeds, not traffic.{resource.data?.summary && <ul>{resource.data.summary.notes.map((note, i) => <li key={i}>{note}</li>)}</ul>}</Banner>
    <section className="card"><div className="controls"><fieldset><legend>Time range</legend><div className="segmented">{['today', '7 days', '30 days', 'custom'].map(item => <button type="button" key={item} aria-pressed={preset === item} onClick={() => setPreset(item)}>{item === 'today' ? 'Today' : item === 'custom' ? 'Custom' : item}</button>)}</div></fieldset><label>Group by<select aria-label="Group by" value={groupBy} onChange={e => setGroupBy(e.target.value as GroupBy)}><option value="total">Household total</option><option value="ssid">Wi-Fi network (SSID)</option><option value="port">LAN port</option></select></label></div>
      {preset === 'custom' && <div className="controls"><label>From<input type="date" value={customFrom} onChange={e => setCustomFrom(e.target.value)}/></label><label>To<input type="date" value={customTo} onChange={e => setCustomTo(e.target.value)}/></label></div>}
      {invalid && <p role="status">Choose a valid start and end date.</p>}
    </section>
    {resource.error && <Banner tone="error">{resource.error} <button onClick={resource.reload}>Retry</button></Banner>}
    {resource.loading && !resource.data && !invalid && <p role="status">Loading usage…</p>}
    {resource.data && !invalid && <><div className="metrics"><div className="card metric"><span className="eyebrow">DOWNLOAD · APPROXIMATE</span><strong>{bytes(resource.data.summary.total.rxBytes)}</strong><span>Household ↓</span></div><div className="card metric"><span className="eyebrow">UPLOAD · APPROXIMATE</span><strong>{bytes(resource.data.summary.total.txBytes)}</strong><span>Household ↑</span></div><div className="card metric"><span className="eyebrow">TOTAL · APPROXIMATE</span><strong>{bytes(resource.data.summary.total.rxBytes + resource.data.summary.total.txBytes)}</strong><span>Combined traffic</span></div></div><section className="card"><div className="card-head"><div><span className="eyebrow">TRAFFIC OVER TIME</span><h2>{groupBy === 'ssid' ? 'By Wi-Fi network' : groupBy === 'port' ? 'By LAN port' : 'Household total'}</h2></div></div><Chart data={resource.data.series}/></section><section className="card"><h2>Breakdown</h2><div className="two-col"><div><h3>Wi-Fi networks (SSID)</h3>{resource.data.summary.bySsid.length ? <ul className="breakdown">{resource.data.summary.bySsid.map(row => <li key={row.id}>{row.label || row.name}<span>↓ {bytes(row.rxBytes)} · ↑ {bytes(row.txBytes)}</span></li>)}</ul> : <p>No Wi-Fi counter data.</p>}</div><div><h3>LAN ports</h3>{resource.data.summary.byPort.length ? <ul className="breakdown">{resource.data.summary.byPort.map(row => <li key={row.id}>{row.label || row.name}<span>↓ {bytes(row.rxBytes)} · ↑ {bytes(row.txBytes)}</span></li>)}</ul> : <p>No port counter data.</p>}</div></div></section></>}
  </>;
}
