import { useState } from 'react';
import type { TimeSeries } from '../api/client';
import { bytes, dateTime } from '../lib/format';

export function Chart({ data, compact = false }: { data: TimeSeries; compact?: boolean }) {
  const [hover, setHover] = useState<string | null>(null);
  const all = data.series.flatMap(s => s.points.map((point, index) => ({ ...point, index, name: s.label || s.name })));
  const count = Math.max(0, ...data.series.map(s => s.points.length));
  const max = Math.max(1, ...Array.from({ length: count }, (_, index) => data.series.reduce((sum, series) => sum + (series.points[index]?.rxBytes ?? 0) + (series.points[index]?.txBytes ?? 0), 0)));
  const colors = ['#5b43e0', '#ec4899', '#14b8a6', '#f59e0b', '#0ea5e9'];
  return <div className="chart-wrap">
    {count ? <svg viewBox="0 0 720 210" preserveAspectRatio="none" className={`chart ${compact ? 'chart-small' : ''}`} role="img" aria-label={`Usage chart grouped by ${data.groupBy}`}>
      <line x1="0" y1="199" x2="720" y2="199" stroke="#c6d0de" />
      {Array.from({ length: count }, (_, index) => {
        const width = 710 / count;
        let cursor = 199;
        return <g key={index}>{data.series.flatMap((series, groupIndex) => {
          const point = series.points[index];
          if (!point) return [];
          const height = Math.max(1, (point.rxBytes + point.txBytes) / max * 175);
          cursor -= height;
          const label = `${series.label || series.name}, ${dateTime(point.at)}: down ${bytes(point.rxBytes)}, up ${bytes(point.txBytes)}`;
          return <rect key={series.id} x={5 + index * width} y={cursor} width={Math.max(1, width - 2)} height={height} fill={colors[groupIndex % colors.length]} tabIndex={0} aria-label={label} onFocus={() => setHover(label)} onBlur={() => setHover(null)} onMouseEnter={() => setHover(label)} onMouseLeave={() => setHover(null)}><title>{label}</title></rect>;
        })}</g>;
      })}
    </svg> : <p>No usage samples in this range.</p>}
    {hover && <div className="chart-tooltip" role="status">{hover}</div>}
    {!compact && <>
      <div className="legend">{data.series.map((s, i) => <span key={s.id}><i style={{ background: colors[i % colors.length] }} />{s.label || s.name}</span>)}</div>
      <details className="data-fallback"><summary>Accessible usage data table</summary><div className="table-scroll"><table><caption>Traffic per network or port; never per device</caption><thead><tr><th>Group</th><th>Time</th><th>Download</th><th>Upload</th></tr></thead><tbody>{all.map((point, index) => <tr key={`${point.name}-${point.index}-${index}`}><td>{point.name}</td><td>{dateTime(point.at)}</td><td>{bytes(point.rxBytes)}</td><td>{bytes(point.txBytes)}</td></tr>)}</tbody></table></div></details>
    </>}
  </div>;
}
