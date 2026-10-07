create table app_users (
    id bigserial primary key,
    username varchar(128) not null unique,
    password_hash varchar(100) not null,
    created_at timestamptz not null
);

alter table devices add column alias varchar(60);
alter table devices add column trusted boolean not null default false;
alter table devices add column trusted_at timestamptz;

create table router_wifi (
    id varchar(32) primary key,
    name varchar(128),
    enabled boolean not null,
    security_mode varchar(64),
    channel integer,
    band varchar(32)
);
create table router_ports (
    id varchar(32) primary key,
    status varchar(32),
    link_speed_mbps integer
);

create table source_labels (
    source_type varchar(16) not null,
    source_id varchar(32) not null,
    label varchar(60) not null,
    primary key (source_type, source_id),
    constraint source_labels_type_check check (source_type in ('SSID', 'LAN_PORT'))
);
