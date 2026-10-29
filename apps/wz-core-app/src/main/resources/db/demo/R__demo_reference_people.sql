-- Demo / test people for the local and test profiles ONLY (flyway location classpath:db/demo).
-- Never enable this location in shared environments: real employees come from the HRIS sync.
insert into masterdata.employee (employee_id, display_name, email, subsidiary_code, department_code, cost_centre, job_code,
                                 location_code, shift_pattern, preferred_language, badge_id, manager_employee_id, updated_at) values
    ('E1001', 'Erin Employee', 'employee1@wellnessz.local', 'CAT',  'CATERING',  'CC-CAT-100', 'CATERING_ASSOC', 'LHR', 'EARLY', 'en', 'B-1001', 'M2001', now()),
    ('E1002', 'Paul Pilot',    'pilot1@wellnessz.local',    'FLT',  'FLIGHTOPS', 'CC-FLT-200', 'PILOT',          'LHR', 'ROSTER','en', 'B-1002', null,   now()),
    ('E1003', 'Cara Crew',     'crew1@wellnessz.local',     'CAB',  'CABIN',     'CC-CAB-300', 'CABIN_CREW',     'LHR', 'ROSTER','fr', 'B-1003', null,   now()),
    ('E1004', 'Rami Ramp',     'ramp1@wellnessz.local',     'GND',  'GROUND',    'CC-GND-400', 'RAMP_AGENT',     'LHR', 'LATE',  'ar', 'B-1004', null,   now()),
    ('E1005', 'Olivia Office', 'office1@wellnessz.local',   'CORP', 'CORP',      'CC-COR-500', 'OFFICE_ADMIN',   'HQ',  'DAY',   'en', 'B-1005', null,   now()),
    ('M2001', 'Max Manager',   'manager1@wellnessz.local',  'CAT',  'CATERING',  'CC-CAT-100', 'OFFICE_ADMIN',   'LHR', 'DAY',   'en', 'B-2001', null,   now())
on conflict (employee_id) do nothing;

insert into masterdata.staff_member (username, display_name, email, roles, site_codes, languages, specialties, caseload_cap) values
    ('nurse1',       'Nina Nurse',        'nurse1@wellnessz.local',       'OH_CLINICIAN',               'LHR,HQ', 'en,ar', 'MSK,WOUND',       30),
    ('nurse2',       'Noah Nurse',        'nurse2@wellnessz.local',       'OH_CLINICIAN',               'LHR,HQ', 'en,fr', 'MSK,MENTAL',      30),
    ('clinician1',   'Clara Clinician',   'clinician1@wellnessz.local',   'OH_CLINICIAN,CASE_MANAGER',  'LHR,HQ', 'en',    'CASE_MANAGEMENT', 30),
    ('physician1',   'Dr Phil Physician', 'physician1@wellnessz.local',   'OH_PHYSICIAN',               'LHR,HQ', 'en',    'OCCUPATIONAL',    40),
    ('ame1',         'Dr Ava AME',        'ame1@wellnessz.local',         'AME',                        'LHR',    'en,fr', 'AEROMEDICAL',     40),
    ('cmo1',         'Dr Carl CMO',       'cmo1@wellnessz.local',         'CMO',                        'HQ',     'en',    'GOVERNANCE',      10),
    ('nursinghead1', 'Nora Nursing Head', 'nursinghead1@wellnessz.local', 'NURSING_HEAD,OH_CLINICIAN',  'LHR,HQ', 'en',    'LEADERSHIP',      10),
    ('psych1',       'Dr Pia Psych',      'psych1@wellnessz.local',       'PSYCH_CLINICIAN',            'HQ',     'en',    'MENTAL',          25)
on conflict (username) do nothing;

insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
    ('notifications.crew-scheduling.email', 'crew-scheduling@wellnessz.local', 'Crew scheduling distribution list (status only)', 'demo', now()),
    ('notifications.hygiene-manager.email', 'catering-hygiene@wellnessz.local', 'Catering hygiene manager (status only)', 'demo', now())
on conflict (config_key) do nothing;
