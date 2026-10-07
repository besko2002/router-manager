-- Router Manager, phase 2 schema.
--
-- What the ZXHN H188A can and cannot give us drives this design:
--   * it lists the devices it sees, with no byte counters per device;
--   * it has cumulative byte counters PER SSID and PER LAN PORT, which reset to 0 on reboot;
--   * it has no dedicated WAN byte counter.
-- So: device presence lives in `devices` / `device_events`, traffic lives in `counter_samples`
-- (raw readings, kept 14 days) and in the `usage_hourly` / `usage_daily` rollups (kept forever).

-- ---------------------------------------------------------------- devices

create table devices (
    mac           varchar(32)  primary key,
    name          varchar(128),
    -- Reserved for a future OUI lookup; the router does not report a vendor.
    vendor        varchar(128),
    kind          varchar(8)   not null,
    ssid          varchar(64),
    port          varchar(16),
    last_ip       varchar(64),
    last_rssi     integer,
    link_tx_kbps  integer,
    link_rx_kbps  integer,
    first_seen    timestamptz  not null,
    last_seen     timestamptz  not null,
    online        boolean      not null default false,
    -- Flapping guard: a device must be missing from two consecutive polls before it goes OFFLINE.
    missed_polls  integer      not null default 0,
    constraint devices_kind_check check (kind in ('WIFI', 'WIRED'))
);

create index idx_devices_last_seen on devices (last_seen desc);
create index idx_devices_online on devices (online);

-- Only CHANGES are recorded here, never one row per poll.
create table device_events (
    id      bigserial    primary key,
    mac     varchar(32)  not null references devices (mac) on delete cascade,
    at      timestamptz  not null,
    type    varchar(16)  not null,
    details varchar(512),
    constraint device_events_type_check
        check (type in ('NEW_DEVICE', 'ONLINE', 'OFFLINE', 'IP_CHANGED'))
);

create index idx_device_events_mac_at on device_events (mac, at desc);
create index idx_device_events_at on device_events (at desc);

-- ---------------------------------------------------------------- raw counter readings

-- One row per source per poll. `rx_bytes` / `tx_bytes` are the ROUTER's cumulative counters as
-- read, never deltas: all arithmetic happens later, so a wrong delta can always be recomputed.
-- `router_uptime_s` is stored next to the reading because a DROP in it is how a reboot (and
-- therefore a counter reset) is detected.
create table counter_samples (
    id              bigserial    primary key,
    taken_at        timestamptz  not null,
    source_type     varchar(16)  not null,
    source_id       varchar(32)  not null,
    rx_bytes        bigint       not null,
    tx_bytes        bigint       not null,
    router_uptime_s bigint,
    constraint counter_samples_source_type_check check (source_type in ('SSID', 'LAN_PORT')),
    constraint counter_samples_rx_check check (rx_bytes >= 0),
    constraint counter_samples_tx_check check (tx_bytes >= 0)
);

-- One reading per source per instant; makes re-processing a poll idempotent.
create unique index uq_counter_samples_source_time
    on counter_samples (source_type, source_id, taken_at);
create index idx_counter_samples_taken_at on counter_samples (taken_at);

-- ---------------------------------------------------------------- rollups

-- Hour buckets, cut in the configured zone (default Africa/Cairo) and stored as the UTC instant
-- the local hour starts at.
create table usage_hourly (
    id          bigserial    primary key,
    hour_start  timestamptz  not null,
    source_type varchar(16)  not null,
    source_id   varchar(32)  not null,
    rx_bytes    bigint       not null,
    tx_bytes    bigint       not null,
    constraint uq_usage_hourly unique (hour_start, source_type, source_id),
    constraint usage_hourly_source_type_check check (source_type in ('SSID', 'LAN_PORT'))
);

create index idx_usage_hourly_hour on usage_hourly (hour_start desc);

-- Day buckets, as a LOCAL date in the configured zone: "yesterday" must mean yesterday at home.
create table usage_daily (
    id          bigserial    primary key,
    day_start   date         not null,
    source_type varchar(16)  not null,
    source_id   varchar(32)  not null,
    rx_bytes    bigint       not null,
    tx_bytes    bigint       not null,
    constraint uq_usage_daily unique (day_start, source_type, source_id),
    constraint usage_daily_source_type_check check (source_type in ('SSID', 'LAN_PORT'))
);

create index idx_usage_daily_day on usage_daily (day_start desc);

-- ---------------------------------------------------------------- router / poller health

-- Exactly one row, enforced by the primary key check.
create table router_status (
    id                    integer      primary key,
    state                 varchar(16)  not null,
    last_ok_at            timestamptz,
    last_poll_at          timestamptz,
    last_error            text,
    model                 varchar(64),
    firmware              varchar(64),
    hardware              varchar(64),
    uptime_s              bigint,
    dsl_down_current_kbps integer,
    dsl_down_max_kbps     integer,
    dsl_up_current_kbps   integer,
    dsl_up_max_kbps       integer,
    consecutive_failures  integer      not null default 0,
    updated_at            timestamptz  not null,
    constraint router_status_single_row check (id = 1),
    constraint router_status_state_check
        check (state in ('NEW', 'OK', 'AUTH_FAILED', 'LOCKED', 'UNREACHABLE'))
);

insert into router_status (id, state, consecutive_failures, updated_at)
values (1, 'NEW', 0, now());
