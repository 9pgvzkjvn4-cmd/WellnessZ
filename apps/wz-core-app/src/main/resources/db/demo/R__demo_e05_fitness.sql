-- Demo crew attributes (fleet / base) for the CMO crew dashboard – local and test profiles only.
insert into fitness.crew_profile (employee_id, fleet, base, updated_at) values
    ('E1002', 'A320', 'LHR', now()),
    ('E1003', 'B787', 'LHR', now())
on conflict (employee_id) do update set fleet = excluded.fleet, base = excluded.base, updated_at = excluded.updated_at;
