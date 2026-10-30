-- Demo duty assignments (local / test profiles only): crew rosters, ground shifts and a shared facility, relative to
-- current_date, for critical-incident lists (E09), post-incident testing (E10) and contact tracing / advisories (E11).
insert into masterdata.duty_assignment (id, source, source_ref, duty_ref, duty_date, starts_at, ends_at, employee_id, duty_role,
                                        location_code, destination_code, proximity_group, received_at)
select v.id::uuid, v.source, v.source_ref, v.duty_ref, (current_date + v.day_offset), (current_date + v.day_offset) + v.start_t,
       (current_date + v.day_offset) + v.end_t, v.employee_id, v.duty_role, v.location_code, v.destination_code, v.proximity_group, now()
from (values
    -- WZ101 LHR→SIN two days ago, back WZ102 yesterday (pilot E1002, cabin crew E1003, captain E7001)
    ('d0d0de00-0000-4000-8000-000000000001', 'CREW_ROSTER', 'ROS-WZ101-E1002', 'WZ101', -2, time '09:00', time '22:30', 'E1002', 'FIRST_OFFICER', 'LHR', 'SIN', 'G-WZAA'),
    ('d0d0de00-0000-4000-8000-000000000002', 'CREW_ROSTER', 'ROS-WZ101-E1003', 'WZ101', -2, time '09:00', time '22:30', 'E1003', 'CABIN_CREW',    'LHR', 'SIN', 'G-WZAA'),
    ('d0d0de00-0000-4000-8000-000000000003', 'CREW_ROSTER', 'ROS-WZ101-E7001', 'WZ101', -2, time '09:00', time '22:30', 'E7001', 'CAPTAIN',       'LHR', 'SIN', 'G-WZAA'),
    ('d0d0de00-0000-4000-8000-000000000004', 'CREW_ROSTER', 'ROS-WZ102-E1002', 'WZ102', -1, time '01:00', time '14:00', 'E1002', 'FIRST_OFFICER', 'SIN', 'LHR', 'G-WZAA'),
    ('d0d0de00-0000-4000-8000-000000000005', 'CREW_ROSTER', 'ROS-WZ102-E1003', 'WZ102', -1, time '01:00', time '14:00', 'E1003', 'CABIN_CREW',    'SIN', 'LHR', 'G-WZAA'),
    -- WZ205 LHR→DXB in three days (advisory targeting)
    ('d0d0de00-0000-4000-8000-000000000006', 'CREW_ROSTER', 'ROS-WZ205-E1003', 'WZ205', 3, time '07:30', time '15:00', 'E1003', 'CABIN_CREW',    'LHR', 'DXB', 'G-WZAB'),
    ('d0d0de00-0000-4000-8000-000000000007', 'CREW_ROSTER', 'ROS-WZ205-E1002', 'WZ205', 3, time '07:30', time '15:00', 'E1002', 'FIRST_OFFICER', 'LHR', 'DXB', 'G-WZAB'),
    -- ramp early shift yesterday at stand 5 (E1004, E1007, E1009) and catering line 2 (E1001, E1010, M2001)
    ('d0d0de00-0000-4000-8000-000000000008', 'SHIFT',       'SHF-RAMP-E1004',  'RAMP-EARLY', -1, time '05:00', time '13:00', 'E1004', 'RAMP_AGENT', 'LHR', null, 'LHR-STAND-5'),
    ('d0d0de00-0000-4000-8000-000000000009', 'SHIFT',       'SHF-RAMP-E1007',  'RAMP-EARLY', -1, time '05:00', time '13:00', 'E1007', 'SECURITY',   'LHR', null, 'LHR-STAND-5'),
    ('d0d0de00-0000-4000-8000-000000000010', 'SHIFT',       'SHF-RAMP-E1009',  'RAMP-EARLY', -1, time '05:00', time '13:00', 'E1009', 'CLEANER',    'LHR', null, 'LHR-STAND-5'),
    ('d0d0de00-0000-4000-8000-000000000011', 'SHIFT',       'SHF-CAT-E1001',   'CAT-LINE-2', -1, time '04:00', time '12:00', 'E1001', 'CATERING',   'LHR', null, 'LHR-CAT-LINE-2'),
    ('d0d0de00-0000-4000-8000-000000000012', 'SHIFT',       'SHF-CAT-E1010',   'CAT-LINE-2', -1, time '04:00', time '12:00', 'E1010', 'CATERING',   'LHR', null, 'LHR-CAT-LINE-2'),
    ('d0d0de00-0000-4000-8000-000000000013', 'SHIFT',       'SHF-CAT-M2001',   'CAT-LINE-2', -1, time '06:00', time '14:00', 'M2001', 'SUPERVISOR', 'LHR', null, 'LHR-CAT-LINE-2'),
    -- shared crew room access (facility)
    ('d0d0de00-0000-4000-8000-000000000014', 'FACILITY',    'FAC-CREWROOM-E1003', 'LHR-CREW-ROOM', -2, time '07:30', time '09:00', 'E1003', null, 'LHR', null, 'LHR-CREW-ROOM'),
    ('d0d0de00-0000-4000-8000-000000000015', 'FACILITY',    'FAC-CREWROOM-E1006', 'LHR-CREW-ROOM', -2, time '08:00', time '08:45', 'E1006', null, 'LHR', null, 'LHR-CREW-ROOM')
) as v(id, source, source_ref, duty_ref, day_offset, start_t, end_t, employee_id, duty_role, location_code, destination_code, proximity_group)
where not exists (select 1 from masterdata.duty_assignment d where d.source = v.source and d.source_ref = v.source_ref);
