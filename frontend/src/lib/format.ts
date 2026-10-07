export function bytes(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—';
  const n = Math.max(0, value);
  if (n < 1000) return `${Math.round(n)} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  const exponent = Math.min(4, Math.floor(Math.log10(n) / 3));
  return `${(n / 1000 ** exponent).toFixed(1)} ${units[exponent - 1]}`;
}
export function rate(kbps: number | null | undefined): string {
  if (kbps == null || !Number.isFinite(kbps)) return '—';
  const n = Math.max(0, kbps);
  return n < 1000 ? `${Math.round(n)} kbps` : `${(n / 1000).toFixed(1)} Mbps`;
}
export function duration(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds)) return '—';
  const n = Math.max(0, Math.floor(seconds));
  return `${Math.floor(n / 86400)}d ${Math.floor(n % 86400 / 3600)}h ${Math.floor(n % 3600 / 60)}m`;
}
export function dateTime(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString();
}
export function dayRange(days: number, now = new Date()): { from: string; to: string } {
  const end = new Date(now);
  const start = new Date(now);
  start.setHours(0, 0, 0, 0);
  start.setDate(start.getDate() - days + 1);
  return { from: start.toISOString(), to: end.toISOString() };
}
