# Router Manager — Plan

Home-network companion for a **ZTE ZXHN H188A** (web UI only: ports 80/443, no SNMP/SSH/UPnP).
See connected devices, measure internet usage, and change a few router settings safely.

## What recon found (public login page, no credentials used)
- Login: `GET /?_type=loginData&_tag=login_token` -> numeric token;
  `POST /?_type=loginData&_tag=login_entry` with `Username`, `Password = sha256(password + token)`,
  `_sessionTOKEN` (hidden input of the login page), `action=login`. JSON reply has `sess_token`,
  `loginErrMsg`, `lockingTime`.
- The router LOCKS login after failed attempts: never guess or retry passwords; one attempt, then stop.
- Data pages are read through `/?_type=menuView|menuData|hiddenData&_tag=...` (e.g. `accessdev_data`).

## Hard limits (be honest in the README)
- Per-device traffic is only possible if the router exposes it (to be verified in phase 1).
  Otherwise: total WAN usage + "who was online when".
- Firmware updates can change the pages: the router access lives behind one `RouterClient` interface.

## Stack
Java 21, Spring Boot, PostgreSQL, Flyway, Testcontainers, React + TypeScript, Docker Compose, GitHub Actions.
Runs INSIDE the home network (the router is not reachable from the internet).

## Phases
1. Recon with real credentials (read-only) + `ZteClient` (login, devices, WAN counters) + router simulator.
2. Storage, poller, usage calculator (counter resets after reboot, daily/monthly totals, quota projection).
3. API + app login + encrypted router credentials + safe settings actions (confirm, backup, rollback).
4. React dashboard (live devices, usage charts, settings).
5. Alerts (unknown device, quota), Docker, CI, README, GitHub, CV.

## Security rules
- Router credentials only in `.env` (git-ignored), never printed, never committed.
- Recon is READ-ONLY: only GET on menuView/menuData/hiddenData; no `action=set/apply`.

## Recon result (logged in once, read-only, ZXHN H188A)
Login flow (verified): GET `/` -> GET `/?_type=loginData&_tag=login_entry` (JSON: `sess_token`, lock state)
-> GET `login_token` -> POST `login_entry` {Username, Password=sha256(password+token), _sessionTOKEN, action=login}.
Data: open the page first (`/?_type=menuView&_tag=<page>&Menu3Location=0`), then GET
`/?_type=menuData&_tag=<endpoint>.lua` (without opening the page the router answers `SessionTimeout`). XML `<Instance>` lists of ParaName/ParaValue pairs.

| Endpoint (page) | Gives |
|---|---|
| accessdev_ssiddev_lua (localNetStatus) | Wi-Fi clients: name, IP, MAC, SSID, RSSI, link TX/RX rate, channel, mode |
| accessdev_landevs_lua | wired clients: IP, MAC, port |
| arp_arptable_lua / macinfo_mactable_lua | IP<->MAC, MAC<->port |
| eth_lanstatus_lua | per LAN PORT: BytesReceived/BytesSent, link state/speed |
| wlan_status_lua | per SSID: TotalBytes/Packets Received/Sent |
| dsl_interface_status_lua (dslWanStatus) | DSL sync rates (current/max up/down) |
| wan_internet_lua | WAN status, uptime, PPPoE (contains the ISP account name: keep recon/ out of git) |
| devmgr_statusmgr_lua | model, firmware, serial |

NOT available: bytes per device, and no dedicated WAN byte counter. The "RX/TX rate" values are negotiated link
rates, not traffic. Counters are cumulative and reset on reboot.
