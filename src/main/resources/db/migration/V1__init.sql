create table incident (
    id                  uuid primary key,
    fingerprint         varchar(64)  not null,
    service             varchar(100) not null,
    title               varchar(300) not null,
    exception_type      varchar(300),
    level               varchar(10)  not null,
    status              varchar(20)  not null,
    severity            varchar(20)  not null,
    root_cause_category varchar(40)  not null,
    root_cause_summary  text,
    suggested_action    text,
    confidence          double precision,
    analysis_status     varchar(20)  not null,
    analyzed_by         varchar(100),
    analyzed_at         timestamptz,
    occurrence_count    bigint       not null,
    first_seen          timestamptz  not null,
    last_seen           timestamptz  not null,
    created_at          timestamptz  not null,
    resolved_at         timestamptz
);

create index idx_incident_status_last_seen on incident (status, last_seen desc);
create index idx_incident_fingerprint on incident (fingerprint);
create index idx_incident_service on incident (service);

create table incident_sample (
    id          bigint generated always as identity primary key,
    incident_id uuid        not null references incident (id) on delete cascade,
    event_id    uuid,
    occurred_at timestamptz not null,
    level       varchar(10) not null,
    host        varchar(200),
    trace_id    varchar(64),
    message     text        not null,
    stack_trace text
);

create index idx_incident_sample_incident on incident_sample (incident_id);
