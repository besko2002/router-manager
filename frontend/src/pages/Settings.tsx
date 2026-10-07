import { useState } from 'react';
import { api, type Port, type Wifi } from '../api/client';
import { Banner } from '../components/UI';
import { useResource } from '../lib/useResource';

function LabelEditor({ label, name, type, id, onSaved }: { label: string | null; name: string; type: 'SSID' | 'LAN_PORT'; id: string; onSaved: () => void }) {
  const [value, setValue] = useState(label ?? '');
  const [error, setError] = useState('');
  async function save() {
    try {
      if (value.trim()) await api.label(type, id, value);
      else await api.deleteLabel(type, id);
      setError(''); onSaved();
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not save label'); }
  }
  return <div><label className="sr-only" htmlFor={`${type}-${id}`}>Label for {name}</label><input id={`${type}-${id}`} aria-label={`Label for ${name}`} maxLength={60} value={value} onChange={event => setValue(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') void save(); }}/><button type="button" onClick={() => void save()}>Save label</button>{error && <span role="alert">{error}</span>}</div>;
}
export function Settings() {
  const wifi = useResource(signal => api.wifi(signal), 'wifi-settings');
  const ports = useResource(signal => api.ports(signal), 'port-settings');
  return <><div className="page-title"><div><span className="eyebrow">ROUTER SETTINGS</span><h1>Settings</h1></div></div>
    <Banner tone="warning">Read-only: this app never changes your router settings. Labels are stored in this app only.</Banner>
    {(wifi.error || ports.error) && <Banner tone="error">{wifi.error || ports.error}</Banner>}
    {(wifi.loading || ports.loading) && <p role="status">Loading router settings…</p>}
    <section className="card"><h2>Wi-Fi networks</h2><div className="table-scroll"><table><thead><tr><th>Name</th><th>Label</th><th>Enabled</th><th>Security</th><th>Channel</th><th>Connected devices</th></tr></thead><tbody>{wifi.data?.items.map((row: Wifi) => <tr key={row.id}><td>{row.name}</td><td><LabelEditor key={`${row.id}-${row.label}`} label={row.label} name={row.name} type="SSID" id={row.id} onSaved={wifi.reload}/></td><td>{row.enabled ? 'Yes' : 'No'}</td><td>{row.securityMode ?? 'Unknown'}</td><td>{row.channel ?? 'Unknown'}</td><td>{row.connectedDeviceCount}</td></tr>)}</tbody></table></div></section>
    <section className="card"><h2>LAN ports</h2><div className="table-scroll"><table><thead><tr><th>Port</th><th>Status</th><th>Speed</th><th>Label</th></tr></thead><tbody>{ports.data?.items.map((row: Port) => <tr key={row.id}><td>{row.id}</td><td>{row.status}</td><td>{row.linkSpeedMbps == null ? 'Unknown' : `${row.linkSpeedMbps} Mbps`}</td><td><LabelEditor key={`${row.id}-${row.label}`} label={row.label} name={row.id} type="LAN_PORT" id={row.id} onSaved={ports.reload}/></td></tr>)}</tbody></table></div></section></>;
}
