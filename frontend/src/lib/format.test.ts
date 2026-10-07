import { describe, expect, it } from 'vitest';
import { bytes, rate, duration, dateTime, dayRange } from './format';
describe('decimal formatters', () => {
  it.each([[0, '0 B'], [999, '999 B'], [1000, '1.0 KB'], [1000000, '1.0 MB'], [1000000000, '1.0 GB'], [1e12, '1.0 TB'], [-100, '0 B'], [1500000000, '1.5 GB']] as const)('formats %s bytes as %s', (input, output) => expect(bytes(input)).toBe(output));
  it('does not display invalid bytes', () => { expect(bytes(null)).toBe('—'); expect(bytes(Infinity)).toBe('—'); });
  it.each([[0, '0 kbps'], [999, '999 kbps'], [1000, '1.0 Mbps'], [-20, '0 kbps'], [12345, '12.3 Mbps']] as const)('formats %s kbps as %s', (input, output) => expect(rate(input)).toBe(output));
  it('does not display invalid rates', () => { expect(rate(null)).toBe('—'); expect(rate(NaN)).toBe('—'); });
  it('formats uptime', () => expect(duration(90061)).toBe('1d 1h 1m'));
  it('clamps negative uptime', () => expect(duration(-1)).toBe('0d 0h 0m'));
  it('handles invalid timestamps', () => expect(dateTime('not a date')).toBe('—'));
  it('starts today at local midnight', () => { const range = dayRange(1, new Date('2025-06-03T12:00:00')); expect(new Date(range.from).getHours()).toBe(0); expect(range.to).toBe(new Date('2025-06-03T12:00:00').toISOString()); });
  it('starts seven days six calendar days earlier', () => { const range = dayRange(7, new Date('2025-06-10T12:00:00')); expect(new Date(range.from).getDate()).toBe(4); });
});
