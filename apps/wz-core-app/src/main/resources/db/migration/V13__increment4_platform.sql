-- V13 – Increment 4 platform additions (E04 / E08): partner organisation master data and the claim-record retention class.
-- Module schemas are created by their own migrations: V14 treatment (E04), V15 claims (E08).

create table masterdata.partner_organisation (
    code            varchar(40)   primary key,
    name            varchar(200)  not null,
    org_type        varchar(30)   not null,
    country_code    varchar(2)    not null,
    city            varchar(100),
    contact_email   varchar(320),
    phone           varchar(40),
    portal_enabled  boolean       not null default false,
    active          boolean       not null default true,
    updated_at      timestamptz   not null,
    constraint ck_partner_org_type check (org_type in ('HOSPITAL','CLINIC','SPECIALIST','PHYSIOTHERAPY','IMAGING','LABORATORY','MENTAL_HEALTH',
                                                       'TELEHEALTH','ASSISTANCE','INSURER','TPA','AUTHORITY'))
);
create index ix_partner_org_type on masterdata.partner_organisation (org_type) where active;

-- initial partner network (E04-BL-019, illustrative – procurement replaces it with the contracted network):
-- hub hospitals, specialists, physiotherapy, imaging, mental health, telehealth and the travel-assistance partner for
-- layover cities; insurers, TPAs and reporting authorities for E08. Portal users carry org_scope /PARTNERS/<code>.
insert into masterdata.partner_organisation (code, name, org_type, country_code, city, contact_email, phone, portal_enabled, updated_at) values
    ('CITYHOSP',      'City General Hospital',                    'HOSPITAL',      'GB', 'London',    'referrals@cityhosp.example',     '+44 20 7000 0001', true,  now()),
    ('HEATHROWMED',   'Heathrow Medical Centre',                  'CLINIC',        'GB', 'London',    'desk@heathrowmed.example',       '+44 20 7000 0002', true,  now()),
    ('STMARYS_ORTHO', 'St Mary''s Orthopaedic & Sports Injury',   'SPECIALIST',    'GB', 'London',    'ortho@stmarys.example',          '+44 20 7000 0003', true,  now()),
    ('CARDIO_LDN',    'London Heart Specialists',                 'SPECIALIST',    'GB', 'London',    'cardio@ldnheart.example',        '+44 20 7000 0004', false, now()),
    ('EYE_LDN',       'Thames Eye Clinic',                        'SPECIALIST',    'GB', 'London',    'eyes@thameseye.example',         '+44 20 7000 0005', false, now()),
    ('ENT_AUDIO',     'Hearing & ENT Partners',                   'SPECIALIST',    'GB', 'Slough',    'ent@hearingpartners.example',    '+44 17 5300 0006', false, now()),
    ('DERM_LDN',      'Skin & Occupational Dermatology',          'SPECIALIST',    'GB', 'London',    'derm@skinocc.example',           '+44 20 7000 0007', false, now()),
    ('PHYSIOPLUS',    'PhysioPlus Rehabilitation',                'PHYSIOTHERAPY', 'GB', 'Hounslow',  'bookings@physioplus.example',    '+44 20 7000 0008', true,  now()),
    ('REHAB_HUB',     'Airport Rehab & Work Conditioning Centre', 'PHYSIOTHERAPY', 'GB', 'Staines',   'rehab@rehabhub.example',         '+44 17 8400 0009', false, now()),
    ('LDN_IMAGING',   'London Diagnostic Imaging',                'IMAGING',       'GB', 'London',    'imaging@ldnimaging.example',     '+44 20 7000 0010', true,  now()),
    ('PATHLAB',       'Pathology Partners Laboratory',            'LABORATORY',    'GB', 'London',    'lab@pathpartners.example',       '+44 20 7000 0011', false, now()),
    ('MINDCARE',      'MindCare Psychological Services',          'MENTAL_HEALTH', 'GB', 'London',    'intake@mindcare.example',        '+44 20 7000 0012', true,  now()),
    ('OCC_PSYCH',     'Aviation Peer Support & Psychiatry',       'MENTAL_HEALTH', 'GB', 'London',    'support@avpsych.example',        '+44 20 7000 0013', false, now()),
    ('TELEDOC',       'TeleDoc Specialists Online',               'TELEHEALTH',    'GB', null,        'partners@teledoc.example',       '+44 20 7000 0014', true,  now()),
    ('GLOBALASSIST',  'Global Crew Assistance (layover cities)',  'ASSISTANCE',    'GB', 'London',    'ops@globalassist.example',       '+44 20 7000 0015', true,  now()),
    ('DXB_AMERICAN',  'Dubai American Hospital',                  'HOSPITAL',      'AE', 'Dubai',     'intl@dxbamerican.example',       '+971 4 000 0016',  true,  now()),
    ('DXB_PHYSIO',    'Gulf Physiotherapy Clinic',                'PHYSIOTHERAPY', 'AE', 'Dubai',     'care@gulfphysio.example',        '+971 4 000 0017',  false, now()),
    ('JFK_URGENT',    'JFK Airport Urgent Care',                  'CLINIC',        'US', 'New York',  'desk@jfkurgent.example',         '+1 718 000 0018',  false, now()),
    ('SIN_MEDICAL',   'Changi Travel Medical Centre',             'CLINIC',        'SG', 'Singapore', 'travel@changimed.example',       '+65 6000 0019',    false, now()),
    ('HKG_CLINIC',    'Hong Kong Crew Clinic',                    'CLINIC',        'HK', 'Hong Kong', 'crew@hkgclinic.example',         '+852 3000 0020',   false, now()),
    ('GULFTPA',       'Gulf Claims Administrators (TPA)',         'TPA',           'GB', 'London',    'adjusters@gulftpa.example',      '+44 20 7000 0021', true,  now()),
    ('AVIA_INSURE',   'AviaSure Employers'' Liability',           'INSURER',       'GB', 'London',    'claims@aviasure.example',        '+44 20 7000 0022', true,  now()),
    ('GROUPMED',      'GroupMed Health Insurance',                'INSURER',       'GB', 'Reading',   'preauth@groupmed.example',       '+44 11 8000 0023', true,  now()),
    ('AUTH_OSH',      'National OSH Reporting Authority',         'AUTHORITY',     'GB', 'London',    'reports@osh-authority.example',  null,               false, now()),
    ('AUTH_LABOUR_AE','UAE Labour Inspectorate (injury notices)', 'AUTHORITY',     'AE', 'Dubai',     'notices@labour-ae.example',      null,               false, now());

insert into docs.retention_schedule (record_class, retention_years, trigger_event, legal_basis, updated_by, updated_at) values
    ('CLAIM_RECORD', 10, 'DOCUMENT_DATE', 'Proposed: claim, pre-authorisation, first-report and medico-legal records – limitation period for '
        || 'injury claims plus margin; suspended while a litigation hold is active', 'migration', now())
on conflict (record_class) do nothing;

grant select, insert, update on masterdata.partner_organisation to ${app_role};
