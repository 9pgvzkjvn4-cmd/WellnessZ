-- V9 – Increment 3 platform additions (E06 / E07 / E14): clinic sites, extra job codes and exposure hazards.
-- Module schemas are created by their own migrations: V10 absence (E06), V11 surveillance (E07), V12 scheduling (E14).

create table masterdata.clinic_site (
    code           varchar(40)   primary key,
    name           varchar(200)  not null,
    site_type      varchar(30)   not null,
    location_code  varchar(40)   not null,
    time_zone      varchar(60)   not null,
    address        varchar(500),
    active         boolean       not null default true,
    updated_at     timestamptz   not null,
    constraint ck_clinic_site_type check (site_type in ('HQ_MEDICAL_CENTRE','AIRSIDE_POST','CATERING_ROOM','CARGO_ENG_CLINIC','OUTSTATION','MOBILE'))
);
create index ix_clinic_site_location on masterdata.clinic_site (location_code) where active;

insert into masterdata.clinic_site (code, name, site_type, location_code, time_zone, address, updated_at) values
    ('HQ-MC',       'HQ Medical Centre',                 'HQ_MEDICAL_CENTRE', 'HQ',  'Europe/London', 'Head office, level 1',          now()),
    ('LHR-AIRSIDE', 'LHR Airside First-Aid Post',        'AIRSIDE_POST',      'LHR', 'Europe/London', 'Terminal 5 airside, gate A10',  now()),
    ('LHR-CAT',     'LHR Catering Medical Room',         'CATERING_ROOM',     'LHR', 'Europe/London', 'Catering centre, ground floor', now()),
    ('LHR-CARGO',   'LHR Cargo & Engineering Clinic',    'CARGO_ENG_CLINIC',  'LHR', 'Europe/London', 'Cargo village, building 4',     now()),
    ('DXB-OUT',     'Dubai Outstation Clinic (partner)', 'OUTSTATION',        'DXB', 'Asia/Dubai',    'DXB Terminal 1 crew centre',    now()),
    ('MOBILE-1',    'Mobile Clinic Unit 1',              'MOBILE',            'LHR', 'Europe/London', 'Deployed per campaign',         now());

-- exposure hazards and job codes used by the initial surveillance programme set (E07-BL-002)
insert into masterdata.hazard (code, name, category, surveillance_required, surveillance_interval_months) values
    ('DUST_RESP',   'Dust / respiratory sensitisers',    'CHEMICAL',      true,  24),
    ('SOLVENT',     'Solvents and hazardous chemicals',  'CHEMICAL',      true,  12),
    ('BBP',         'Blood-borne pathogens',             'BIOLOGICAL',    true,  60),
    ('VIBRATION',   'Hand-arm / whole-body vibration',   'PHYSICAL',      true,  12),
    ('HEAT',        'Heat stress',                       'PHYSICAL',      false, null),
    ('NIGHT_WORK',  'Night / shift work',                'PSYCHOSOCIAL',  true,  12),
    ('HEIGHT',      'Working at height',                 'PHYSICAL',      true,  36),
    ('DGR',         'Dangerous goods handling',          'CHEMICAL',      true,  24),
    ('VDU',         'Display screen equipment',          'ERGONOMIC',     true,  24)
on conflict (code) do nothing;

insert into masterdata.job_code (code, title, safety_critical, hazard_codes, safety_category) values
    ('SECURITY_SCREENER', 'Security screener',        true,  'NIGHT_WORK,VDU',                 'SECURITY'),
    ('CLEANER',           'Aircraft cleaner',         false, 'BBP,SOLVENT,NIGHT_WORK',         null),
    ('FORKLIFT_OP',       'Cargo forklift operator',  true,  'NOISE,VIBRATION,DGR,NIGHT_WORK', 'FORKLIFT'),
    ('CLINICAL_STAFF',    'OH clinical staff',        false, 'BBP',                            null),
    ('ENG_HEIGHT',        'Hangar engineer (height)', true,  'NOISE,JET_FUEL,HEIGHT,SOLVENT',  'CERTIFYING_ENGINEER')
on conflict (code) do nothing;
update masterdata.job_code set hazard_codes = 'NOISE,JET_FUEL,SOLVENT' where code = 'ENGINEER';
update masterdata.job_code set hazard_codes = 'NOISE,MANUAL_HANDLING,JET_FUEL,NIGHT_WORK,DGR' where code = 'RAMP_AGENT';
update masterdata.job_code set hazard_codes = 'VDU' where code = 'OFFICE_ADMIN' and hazard_codes is null;

grant select, insert, update on masterdata.clinic_site to ${app_role};

-- retention classes for increment-3 documents (proposed, pending DPO / CMO approval)
insert into docs.retention_schedule (record_class, retention_years, trigger_event, legal_basis, updated_by, updated_at) values
    ('ABSENCE_RECORD',   6, 'DOCUMENT_DATE',  'Proposed: sick certificates and OH advice reports – limitation period of employment claims', 'migration', now()),
    ('PRE_EMPLOYMENT',   1, 'DOCUMENT_DATE',  'Proposed: pre-employment health data of candidates not hired is deleted after 12 months; hired candidates move to OH_RECORD', 'migration', now())
on conflict (record_class) do nothing;
