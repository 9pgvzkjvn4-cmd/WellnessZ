-- Spring Session JDBC tables (attribute bytes are AES-256-GCM encrypted by the BFF)
create schema if not exists bff;
create table bff.spring_session (
    primary_id            char(36)     not null primary key,
    session_id            char(36)     not null,
    creation_time         bigint       not null,
    last_access_time      bigint       not null,
    max_inactive_interval int          not null,
    expiry_time           bigint       not null,
    principal_name        varchar(100)
);
create unique index spring_session_ix1 on bff.spring_session (session_id);
create index spring_session_ix2 on bff.spring_session (expiry_time);
create index spring_session_ix3 on bff.spring_session (principal_name);
create table bff.spring_session_attributes (
    session_primary_id char(36)     not null references bff.spring_session(primary_id) on delete cascade,
    attribute_name     varchar(200) not null,
    attribute_bytes    bytea        not null,
    primary key (session_primary_id, attribute_name)
);
grant usage on schema bff to ${app_role};
grant select, insert, update, delete on bff.spring_session, bff.spring_session_attributes to ${app_role};
