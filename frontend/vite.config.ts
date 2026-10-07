import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vitest/config';
import { mockResponse } from './src/api/mock';

function mockApi(): Plugin {
  return {
    name: 'local-mock-api',
    configureServer(server) {
      if (process.env.VITE_MOCK !== '1') return;
      server.middlewares.use((req, res, next) => {
        if (!req.url?.startsWith('/api/')) { next(); return; }
        let input = '';
        req.on('data', chunk => { input += chunk; });
        req.on('end', () => {
          let body: Record<string, unknown> = {};
          try { body = JSON.parse(input || '{}') as Record<string, unknown>; } catch { /* malformed body handled as empty */ }
          const answer = mockResponse(req.url ?? '', req.method, body);
          if (!answer) { next(); return; }
          res.statusCode = answer.status;
          if (answer.body !== undefined) {
            res.setHeader('Content-Type', 'application/json; charset=utf-8');
            res.end(JSON.stringify(answer.body));
          } else res.end();
        });
      });
    },
  };
}
export default defineConfig({
  plugins: [react(), mockApi()],
  server: { port: 5176, strictPort: true, proxy: process.env.VITE_MOCK === '1' ? undefined : { '/api': { target: 'http://localhost:8092', changeOrigin: false } } },
  preview: { port: 5176, strictPort: true },
  test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], include: ['src/**/*.test.{ts,tsx}'], restoreMocks: true, css: false },
});
