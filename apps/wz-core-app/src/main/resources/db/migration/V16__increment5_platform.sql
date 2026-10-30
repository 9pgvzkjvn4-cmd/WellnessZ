-- V16 – Increment 5 platform additions (E09 / E10 / E11 / E12): duty assignments from rostering, shift and facility
-- systems, extra partner organisations and retention classes. Module schemas are created by their own migrations:
-- V17 mentalhealth (E09), V18 substance (E10), V19 travelhealth (E11), V20 promotion (E12).

create table masterdata.duty_assignment (
    id                uuid primary key,
    source            varchar(20)   not null,
    source_ref        varchar(120)  not null,
    duty_ref          varchar(60)   not null,
    duty_date         date          not null,
    starts_at         timestamptz   not null,
    ends_at           timestamptz   not null,
    employee_id       varchar(64)   not null,
    duty_role         varchar(40),
    location_code     varchar(40),
    destination_code  varchar(40),
    proximity_group   varchar(60),
    received_at       timestamptz   not null,
    constraint uq_duty_source unique (source, source_ref),
    constraint ck_duty_source check (source in ('CREW_ROSTER','SHIFT','FACILITY')),
    constraint ck_duty_window check (ends_at > starts_at)
);
create index ix_duty_employee on masterdata.duty_assignment (employee_id, starts_at);
create index ix_duty_ref on masterdata.duty_assignment (duty_ref, starts_at);
create index ix_duty_proximity on masterdata.duty_assignment (proximity_group, starts_at);
create index ix_duty_destination on masterdata.duty_assignment (destination_code, starts_at);

-- partners used by increment 5 (illustrative): EAP / counselling partner, accredited toxicology laboratory,
-- public-health authority. MINDCARE (mental health) already exists from V13.
insert into masterdata.partner_organisation (code, name, org_type, country_code, city, contact_email, phone, portal_enabled, updated_at) values
    ('EAP_WELLBEING', 'Wellbeing Assist EAP (counselling partner)', 'MENTAL_HEALTH', 'GB', 'London', 'referrals@wellbeingassist.example', '+44 20 7000 0030', true,  now()),
    ('TOXLAB',        'Accredited Toxicology Laboratory',           'LABORATORY',    'GB', 'Reading', 'results@toxlab.example',            '+44 11 8000 0031', false, now()),
    ('UKHSA_LOCAL',   'Local Health Protection Team',               'AUTHORITY',     'GB', 'London', 'hpt@health-protection.example',     null,               false, now())
on conflict (code) do nothing;

insert into docs.retention_schedule (record_class, retention_years, trigger_event, legal_basis, updated_by, updated_at) values
    ('RESTRICTED_RECORD', 10, 'LAST_ENCOUNTER', 'Proposed: mental-health and substance-programme records – restricted class, reviewed by the DPO and CMO', 'migration', now()),
    ('PUBLIC_HEALTH',     10, 'DOCUMENT_DATE',  'Proposed: notifiable-disease, contact-tracing and exposure reports and authority correspondence', 'migration', now())
on conflict (record_class) do nothing;

grant select, insert, update on masterdata.duty_assignment to ${app_role};
