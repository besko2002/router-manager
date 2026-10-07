import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import type { ReactElement } from 'react';
import { mockResponse } from '../api/mock';
export function mockFetch() {
  const fn = vi.fn(async (url: string, options?: RequestInit) => {
    const answer = mockResponse(url, options?.method, options?.body ? JSON.parse(String(options.body)) as Record<string, unknown> : {});
    if (!answer) throw Error('Unknown URL');
    return { ok: answer.status < 400, status: answer.status, json: async () => answer.body } as Response;
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}
export function renderRoute(component: ReactElement, path = '/') {
  return render(<MemoryRouter initialEntries={[path]}>{component}</MemoryRouter>);
}
