create schema if not exists scheduling;

-- E14 Scheduling, clinic operations & resource management (schema scheduling).
-- Scheduling never stores clinical content: appointments carry the appointment type, times, resources and identifiers
-- only. The admin note is screened for clinical content before it is stored.

-- ---------- appointment types / service catalogue (E14-F01, BL-010) ----------
create table scheduling.appointment_type (
    code              varchar(40)   primary key,
    name              varchar(200)  not null,
    duration_minutes  int           not null,
    clinician_roles   varchar(300),                -- CSV of role names
    room_type         varchar(20),
    device_category   varchar(30),
    telehealth        boolean       not null default false,
    self_bookable     boolean       not null default false,
    charge_code       varchar(40),
    internal_price    numeric(10,2),
    active            boolean       not null default true,
    updated_by        varchar(200)  not null,
    updated_at        timestamptz   not null,
    constraint ck_appt_type_duration check (duration_minutes between 5 and 480),
    constraint ck_appt_type_room check (room_type is null or room_type in ('CONSULT','AUDIO_BOOTH','TREATMENT','VACCINATION','TELEHEALTH')),
    constraint ck_appt_type_device check (device_category is null or device_category in ('AUDIOMETER','SPIROMETER','ECG','VISION_SCREENER','VACCINE_FRIDGE','OTHER'))
);

-- ---------- site resources and weekly diary templates ----------
create table scheduling.resource (
    id            uuid          primary key,
    site_code     varchar(40)   not null,
    type          varchar(20)   not null,
    code          varchar(64)   not null,
    name          varchar(200)  not null,
    username      varchar(200),
    room_type     varchar(20),
    equipment_id  uuid,
    active        boolean       not null default true,
    updated_at    timestamptz   not null,
    constraint uq_resource unique (site_code, type, code),
    constraint ck_resource_type check (type in ('CLINICIAN','ROOM','DEVICE')),
    constraint ck_resource_room_type check (room_type is null or room_type in ('CONSULT','AUDIO_BOOTH','TREATMENT','VACCINATION','TELEHEALTH'))
);

create table scheduling.diary_template (
    id                  uuid          primary key,
    site_code           varchar(40)   not null,
    weekday             int           not null,
    start_time          time          not null,
    end_time            time          not null,
    clinician_username  varchar(200)  not null,
    room_code           varchar(64),
    appointment_types   varchar(1000),             -- CSV of appointment type codes, empty = all
    telehealth          boolean       not null default false,
    updated_by          varchar(200)  not null,
    updated_at          timestamptz   not null,
    constraint ck_template_weekday check (weekday between 1 and 7),
    constraint ck_template_times check (start_time < end_time)
);
create index ix_diary_template_site on scheduling.diary_template (site_code, weekday);
create index ix_diary_template_clinician on scheduling.diary_template (clinician_username);

-- ---------- campaigns (E14-F02) ----------
create table scheduling.campaign (
    id                uuid          primary key,
    name              varchar(200)  not null,
    campaign_type     varchar(20)   not null,
    site_code         varchar(40)   not null,
    appointment_type  varchar(40)   not null,
    days              varchar(2000) not null,       -- CSV of ISO dates
    day_start         time          not null,
    day_end           time          not null,
    slot_minutes      int           not null,
    stations          int           not null,
    device_kit        varchar(2000),                -- CSV of equipment ids
    staff             varchar(2000),                -- CSV of usernames
    target_count      int           not null default 0,
    status            varchar(20)   not null,
    created_by        varchar(200)  not null,
    created_at        timestamptz   not null,
    updated_at        timestamptz   not null,
    version           bigint        not null default 0,
    constraint ck_campaign_type check (campaign_type in ('SURVEILLANCE','VACCINATION','MOBILE_CLINIC')),
    constraint ck_campaign_status check (status in ('PLANNED','OPEN','IN_PROGRESS','CLOSED'))
);

-- ---------- appointments (E14-F01, F03) ----------
create table scheduling.appointment (
    id                   uuid          primary key,
    employee_id          varchar(64)   not null,
    appointment_type     varchar(40)   not null references scheduling.appointment_type (code),
    site_code            varchar(40)   not null,
    starts_at            timestamptz   not null,
    ends_at              timestamptz   not null,
    status               varchar(20)   not null,
    clinician_username   varchar(200),
    room_code            varchar(64),
    device_id            varchar(100),
    campaign_id          uuid          references scheduling.campaign (id),
    case_id              uuid,
    source_type          varchar(40),
    source_ref           varchar(200),
    admin_note           varchar(500),             -- screened: functional wording only
    telehealth           boolean       not null default false,
    telehealth_room      varchar(64),
    ticket               varchar(10),
    checked_in_at        timestamptz,
    called_at            timestamptz,
    started_at           timestamptz,
    completed_at         timestamptz,
    cancelled_at         timestamptz,
    cancel_reason        varchar(500),
    reminder48_sent_at   timestamptz,
    reminder2_sent_at    timestamptz,
    booked_by            varchar(200)  not null,
    created_at           timestamptz   not null,
    updated_at           timestamptz   not null,
    version              bigint        not null default 0,
    constraint ck_appointment_status check (status in ('BOOKED','CHECKED_IN','IN_PROGRESS','ATTENDED','NO_SHOW','CANCELLED')),
    constraint ck_appointment_times check (starts_at < ends_at)
);
create index ix_appointment_site_time on scheduling.appointment (site_code, starts_at);
create index ix_appointment_employee on scheduling.appointment (employee_id, starts_at);
create index ix_appointment_clinician on scheduling.appointment (clinician_username, starts_at) where clinician_username is not null;
create index ix_appointment_campaign on scheduling.appointment (campaign_id) where campaign_id is not null;
create index ix_appointment_source on scheduling.appointment (source_type, source_ref) where source_ref is not null;
create index ix_appointment_open on scheduling.appointment (status, starts_at) where status in ('BOOKED','CHECKED_IN','IN_PROGRESS');

create table scheduling.campaign_slot (
    id              uuid          primary key,
    campaign_id     uuid          not null references scheduling.campaign (id),
    day             date          not null,
    station         int           not null,
    starts_at       timestamptz   not null,
    ends_at         timestamptz   not null,
    appointment_id  uuid,
    constraint uq_campaign_slot unique (campaign_id, starts_at, station)
);
create index ix_campaign_slot_free on scheduling.campaign_slot (campaign_id, starts_at) where appointment_id is null;

-- ---------- waitlist ----------
create table scheduling.waitlist_entry (
    id                      uuid          primary key,
    employee_id             varchar(64)   not null,
    appointment_type        varchar(40)   not null,
    site_code               varchar(40)   not null,
    earliest                date          not null,
    latest                  date          not null,
    priority                int           not null default 3,
    status                  varchar(20)   not null,
    campaign_id             uuid,
    offered_appointment_id  uuid,
    offered_starts_at       timestamptz,
    offered_at              timestamptz,
    created_by              varchar(200)  not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    constraint ck_waitlist_status check (status in ('WAITING','OFFERED','BOOKED','CANCELLED'))
);
create index ix_waitlist_open on scheduling.waitlist_entry (appointment_type, site_code, status);
create index ix_waitlist_employee on scheduling.waitlist_entry (employee_id);

-- ---------- equipment & cold chain (E14-F04) ----------
create table scheduling.equipment (
    id                           uuid          primary key,
    asset_tag                    varchar(64)   not null unique,
    device_id                    varchar(100)  not null unique,
    category                     varchar(30)   not null,
    model                        varchar(200),
    serial_number                varchar(100),
    site_code                    varchar(40)   not null,
    status                       varchar(20)   not null,
    status_reason                varchar(500),
    calibration_interval_months  int,
    last_calibrated_on           date,
    calibration_due_on           date,
    last_certificate_number      varchar(100),
    min_temp_c                   numeric(5,2),
    max_temp_c                   numeric(5,2),
    due_alerted_for              date,
    created_at                   timestamptz   not null,
    updated_at                   timestamptz   not null,
    version                      bigint        not null default 0,
    constraint ck_equipment_category check (category in ('AUDIOMETER','SPIROMETER','ECG','VISION_SCREENER','VACCINE_FRIDGE','OTHER')),
    constraint ck_equipment_status check (status in ('IN_SERVICE','OUT_OF_SERVICE','RETIRED'))
);
create index ix_equipment_site on scheduling.equipment (site_code, category);

create table scheduling.calibration (
    id                  uuid          primary key,
    equipment_id        uuid          not null references scheduling.equipment (id),
    performed_on        date          not null,
    vendor              varchar(200)  not null,
    certificate_number  varchar(100)  not null,
    result              varchar(10)   not null,
    next_due_on         date,
    document_sha256     varchar(64),
    document_content    bytea,
    recorded_by         varchar(200)  not null,
    recorded_at         timestamptz   not null,
    constraint ck_calibration_result check (result in ('PASS','FAIL'))
);
create index ix_calibration_equipment on scheduling.calibration (equipment_id, performed_on desc);

create table scheduling.temperature_reading (
    id             uuid          primary key,
    equipment_id   uuid          not null references scheduling.equipment (id),
    recorded_at    timestamptz   not null,
    temperature_c  numeric(5,2)  not null,
    source         varchar(10)   not null,
    excursion      boolean       not null default false,
    recorded_by    varchar(200)  not null,
    constraint ck_reading_source check (source in ('MANUAL','IOT'))
);
create index ix_reading_equipment on scheduling.temperature_reading (equipment_id, recorded_at);

create table scheduling.excursion (
    id               uuid          primary key,
    equipment_id     uuid          not null references scheduling.equipment (id),
    started_at       timestamptz   not null,
    ended_at         timestamptz,
    min_c            numeric(5,2)  not null,
    max_c            numeric(5,2)  not null,
    status           varchar(20)   not null,
    action           varchar(1000),
    acknowledged_by  varchar(200),
    acknowledged_at  timestamptz,
    closed_by        varchar(200),
    closed_at        timestamptz,
    constraint ck_excursion_status check (status in ('OPEN','ACKNOWLEDGED','CLOSED'))
);
create index ix_excursion_equipment on scheduling.excursion (equipment_id, started_at desc);
create index ix_excursion_status on scheduling.excursion (status);

-- ---------- seed: appointment types (contract §5.1) with charge codes / internal prices ----------
insert into scheduling.appointment_type (code, name, duration_minutes, clinician_roles, room_type, device_category, telehealth, self_bookable, charge_code, internal_price, updated_by, updated_at) values
    ('GENERAL_OH',             'General OH consultation',        30, 'OH_CLINICIAN,OH_PHYSICIAN',              null,          null,              false, false, 'OH-CONS',  60.00,  'migration', now()),
    ('NURSE_REVIEW',           'OH nurse review',                20, 'OH_CLINICIAN',                           null,          null,              false, false, 'OH-NURS',  40.00,  'migration', now()),
    ('PHYSICIAN_REVIEW',       'OH physician review',            30, 'OH_PHYSICIAN',                           null,          null,              false, false, 'OH-PHYS',  120.00, 'migration', now()),
    ('AUDIOMETRY',             'Audiometry',                     20, 'OH_CLINICIAN',                           'AUDIO_BOOTH', 'AUDIOMETER',      false, false, 'OH-AUD',   35.00,  'migration', now()),
    ('SPIROMETRY',             'Spirometry',                     20, 'OH_CLINICIAN',                           null,          'SPIROMETER',      false, false, 'OH-SPI',   35.00,  'migration', now()),
    ('VISION',                 'Vision screening',               15, 'OH_CLINICIAN',                           null,          'VISION_SCREENER', false, false, 'OH-VIS',   25.00,  'migration', now()),
    ('ECG',                    'ECG',                            20, 'OH_CLINICIAN,OH_PHYSICIAN',              null,          'ECG',             false, false, 'OH-ECG',   45.00,  'migration', now()),
    ('VACCINATION',            'Vaccination',                    10, 'OH_CLINICIAN',                           'VACCINATION', null,              false, true,  'OH-VAC',   20.00,  'migration', now()),
    ('PRE_EMPLOYMENT',         'Pre-employment assessment',      45, 'OH_CLINICIAN,OH_PHYSICIAN',              null,          null,              false, false, 'OH-PRE',   90.00,  'migration', now()),
    ('CREW_AEROMEDICAL',       'Crew aeromedical examination',   60, 'AME',                                    null,          null,              false, false, 'OH-AME',   250.00, 'migration', now()),
    ('FOOD_HANDLER_CLEARANCE', 'Food handler clearance',         20, 'OH_CLINICIAN',                           null,          null,              false, false, 'OH-FHC',   30.00,  'migration', now()),
    ('RTW_REVIEW',             'Return-to-work review',          30, 'OH_CLINICIAN,OH_PHYSICIAN',              null,          null,              false, false, 'OH-RTW',   60.00,  'migration', now()),
    ('ABSENCE_REVIEW',         'Absence review',                 30, 'OH_CLINICIAN,OH_PHYSICIAN,CASE_MANAGER', null,          null,              false, false, 'OH-ABS',   60.00,  'migration', now()),
    ('SURVEILLANCE_PANEL',     'Health surveillance panel',      40, 'OH_CLINICIAN',                           'AUDIO_BOOTH', null,              false, false, 'OH-SURV',  70.00,  'migration', now()),
    ('TELEHEALTH_CONSULT',     'Telehealth consultation',        20, 'OH_CLINICIAN,OH_PHYSICIAN',              null,          null,              true,  true,  'OH-TELE',  40.00,  'migration', now())
on conflict (code) do nothing;

-- ---------- seed: configuration ----------
insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
    ('notifications.biomedical.email', 'biomedical@wellnessz.local', 'Biomedical engineering distribution list (calibration due alerts)', 'migration', now()),
    ('scheduling.self-booking.min-notice-hours', '24', 'Employee self-booking: minimum notice in hours', 'migration', now()),
    ('scheduling.self-booking.max-days-ahead', '60', 'Employee self-booking: furthest bookable day', 'migration', now()),
    ('scheduling.self-booking.one-open-per-type', 'true', 'Employee self-booking: at most one open booking per appointment type', 'migration', now()),
    ('scheduling.check-in.window-minutes', '60', 'App / kiosk check-in opens this many minutes before the appointment', 'migration', now()),
    ('scheduling.no-show.grace-minutes', '30', 'Booked / checked-in appointments not started this long after the end become NO_SHOW', 'migration', now()),
    ('scheduling.telehealth-base-url', 'https://telehealth.wellnessz.local/room/', 'Telehealth join URL prefix (a random room id is appended)', 'migration', now()),
    ('scheduling.calibration.due-alert-days', '30', 'Calibration due alert window in days', 'migration', now())
on conflict (config_key) do nothing;

grant usage on schema scheduling to ${app_role};
grant select, insert, update on scheduling.appointment_type, scheduling.resource, scheduling.campaign, scheduling.appointment,
      scheduling.campaign_slot, scheduling.waitlist_entry, scheduling.equipment, scheduling.calibration,
      scheduling.temperature_reading, scheduling.excursion to ${app_role};
grant select, insert, update, delete on scheduling.diary_template to ${app_role};
