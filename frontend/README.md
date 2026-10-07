# Router Manager frontend

Local React 18 + Vite dashboard for a ZTE home router. Requires Node 22 or newer.

```sh
npm install
npm run dev          # http://localhost:5176, /api proxied to localhost:8092
npm run dev:mock     # same UI with built-in mock API, no backend needed
npm run test -- --run
npm run lint
npm run build
```

The router **does not supply per-device traffic bytes**. Usage is available per Wi-Fi network (SSID) and LAN port; the household total is approximate and may be affected by counter resets. Device link rates are negotiated speeds, not traffic. The dashboard does not implement authentication. Mock mode serves six example devices, device events and up to seven days of hourly samples through Vite middleware; it never contacts the router. Docker builds a static SPA and nginx proxies `/api/` to `backend:8092`.
