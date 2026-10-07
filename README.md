[![CI](https://github.com/besko2002/router-manager/actions/workflows/ci.yml/badge.svg)](https://github.com/besko2002/router-manager/actions/workflows/ci.yml)

# Router Manager

A local Spring Boot dashboard backend for a ZTE ZXHN H188A. It reads the router web UI, tracks device presence and rollups of router traffic in PostgreSQL. Java 21, Flyway, Testcontainers and a self-contained router simulator are included. Swagger UI is at `http://127.0.0.1:8092/swagger-ui.html`.

## Honest limits and safety

The router exposes **no per-device byte counters and no dedicated WAN byte counter**. Device records show identity, presence, IP, RSSI and negotiated link speeds, **not usage**. Traffic comes from cumulative per-SSID and per-LAN-port counters. The approximate household total adds both: Wi-Fi-to-wired traffic can be counted twice and local transfers count too. These are router-side traffic figures, **not the ISP-metered figure**. Readings reset at reboot; declining WAN uptime and counter decreases are accounted for. Missing polls credit the traffic to the hour when the next reading arrives. Raw readings are kept 14 days; hourly/daily rollups are retained.

The router locks failed logins. Credential rejection stops automatic login until an operator fixes the credentials and calls `POST /api/admin/router/reset-auth`; locked logins observe the client's minimum relogin interval, and network errors back off. Never brute-force credentials. The app requires an owner login: the server binds to `127.0.0.1` by default, and Compose publishes only on loopback. Keep the service on your own computer or home network; do not expose the API to the internet. Docker backend listens within its container but is published only on host loopback. Never share router credentials in logs or commits.

## Owner authentication and read-only settings

All `/api/**` data and admin endpoints require an HS256 JWT; `/api/auth/login` and one-time `/api/auth/setup` are public. Set `APP_ADMIN_USERNAME` and `APP_ADMIN_PASSWORD` (minimum 10 characters) to create the first owner automatically at startup, or leave both unset and call `POST /api/auth/setup` with `{ "username": "owner", "password": "a-unique-long-password" }` while there are no users. Setup returns 409 once the owner exists. `POST /api/auth/login` accepts the same JSON and returns `{ "token": "...", "username": "owner" }`; send `Authorization: Bearer <token>` on later API requests. `GET /api/auth/me` verifies the token, `POST /api/auth/change-password` takes `{ "currentPassword": "...", "newPassword": "..." }`. Usernames are lower-case; passwords are stored as BCrypt hashes, not logged or returned. Five failed app logins per username and IP in 15 minutes block further attempts with 429 and `Retry-After`. `APP_JWT_EXPIRATION` defaults to `PT12H`. **Set `APP_JWT_SECRET` to a unique secret of at least 32 UTF-8 bytes in deployment**; the bundled fallback is **dev-only and insecure**. Store environment secrets privately. Even with authentication, do not expose this service to the internet: keep the default `server.address=127.0.0.1` and loopback Compose mapping unless you intentionally deploy behind additional protection.

The Settings page reads Wi-Fi networks and LAN ports from the most recently stored poll. Router settings are **read-only**: this app never changes router settings. Local Wi-Fi/port labels, device aliases and device trust flags affect only this app's database; new devices start untrusted. No router write operation is implemented. The router does not expose per-device byte counters. Frontend development: `cd frontend && npm install && npm run dev` (port 5176, proxy `/api` to loopback port 8092); `VITE_MOCK=1 npm run dev` runs offline with demo username/password `demo`/`demo` and never contacts the backend. Mock credentials are not for deployment.

## Run

Start PostgreSQL: `docker compose up -d --wait`. Run a safe live demo with no real-router contact: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo`. The simulator generates traffic every two seconds and the poller reads every five seconds. Stop the app, then `docker compose down` (the volume remains).

For a real router, set `ROUTER_URL`, `ROUTER_USERNAME`, `ROUTER_PASSWORD` in the environment, verify credentials in the browser before starting, and run `./mvnw spring-boot:run` without the demo profile. The default URL is `https://192.168.1.1` but never use it unless you deliberately intend real router access. `.env.example` documents the DB and polling settings; `.env` must remain private. `docker compose --profile app up --build` runs the backend as a container; its database hostname is configured automatically.

## API

| Method | Path | Result |
|---|---|---|
| POST | `/api/auth/setup`, `/api/auth/login` | One-time owner setup and login (public) |
| GET / POST | `/api/auth/me`, `/api/auth/change-password` | Identity and password rotation (protected) |
| PUT / DELETE | `/api/devices/{mac}/alias` | Local display alias, up to 60 characters |
| PUT | `/api/devices/{mac}/trusted` | Locally mark a device trusted or unknown |
| PUT / DELETE | `/api/labels/{type}/{id}` | Local SSID or LAN_PORT label |
| GET | `/api/router/wifi`, `/api/router/ports` | Last stored router settings, read-only |
| GET | `/api/status` | Poll state, last successful read, router identity and DSL sync rates |
| GET | `/api/devices?online=true` | Device list, optionally filtered by presence |
| GET | `/api/devices/{mac}/events` | Changes for one MAC (400 malformed, 404 unknown) |
| GET | `/api/usage/summary?from=2025-01-01&to=2025-01-02` | Approximate total, notes, bySsid, byPort |
| GET | `/api/usage/timeseries?granularity=hour&groupBy=ssid&from=2025-01-01&to=2025-01-02` | Series of points; groupBy total/ssid/port; hour max 14 days, day max 400 days |
| POST | `/api/admin/router/reset-auth` | Clear auth failure after correcting credentials (204) |

Dates use ISO `yyyy-MM-dd` in configurable `TIMEZONE` (default `Africa/Cairo`), or ISO UTC instants; omitted ranges default to the last 24 hours. Date-only `to` includes the entire named day. Hourly buckets include their ending interval; endpoints may return buckets touching partial ranges. Every error is an `ApiError` JSON response.

## Test

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify` runs parser, simulator, login-safety, usage and Postgres Testcontainers API/poller integration tests. The integration tests require Docker. CI runs the same verification command.
