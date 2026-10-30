/*
 * WellnessZ DEMO PACK - synthetic demo data (the "demo data file").
 *
 * Loaded with a plain <script src="demo-data.js"> tag so it works from file://.
 * It sets window.WZ_DEMO_SEED. The engine (shared/engine.js) copies this seed into the
 * shared demo session the first time an app is opened, and again on "Reset demo".
 *
 * EVERYTHING HERE IS SYNTHETIC. All people, organisations, places, flights and
 * clinical details are fictional and exist only to demonstrate the product.
 * Names deliberately follow an obviously artificial "First Jobword" pattern.
 *
 * Dates are expressed relative to the day the seed is loaded (day(0) = today), so a
 * fresh session always looks current. Once seeded, the session keeps its own dates.
 */
(function (root) {
  'use strict';

  // ---------------------------------------------------------------- date helpers
  var BASE = new Date();
  BASE.setHours(0, 0, 0, 0);
  var YEAR = BASE.getFullYear();
  function pad(n, w) { n = String(n); while (n.length < (w || 2)) n = '0' + n; return n; }
  function dt(days) { var d = new Date(BASE.getTime()); d.setDate(d.getDate() + days); return d; }
  /** ISO date (yyyy-mm-dd) `days` from today. */
  function day(days) { var d = dt(days); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  /** ISO timestamp `days` from today at local time hh:mm. */
  function at(days, hhmm) {
    var d = dt(days); var p = (hhmm || '09:00').split(':');
    d.setHours(+p[0], +p[1], 0, 0); return d.toISOString();
  }
  function isWeekend(days) { var w = dt(days).getDay(); return w === 0 || w === 6; }
  function cid(prefix, n) { return prefix + '-' + YEAR + '-' + pad(n, 6); }
  function rid(prefix, n) { return prefix + '-' + YEAR + '-' + pad(n, 4); }

  // ---------------------------------------------------------------- reference data
  var orgUnits = [
    { id: '/AIRLINE', code: 'GRP', name: 'WellnessZ Air Group (fictional)', parent: null },
    { id: '/AIRLINE/FLIGHT_OPS', code: 'FLT', name: 'Flight Operations', parent: '/AIRLINE' },
    { id: '/AIRLINE/INFLIGHT', code: 'INF', name: 'Inflight Services (Cabin Crew)', parent: '/AIRLINE' },
    { id: '/AIRLINE/ENGINEERING', code: 'ENG', name: 'Engineering & Maintenance', parent: '/AIRLINE' },
    { id: '/AIRLINE/GROUND', code: 'GND', name: 'Ground Services', parent: '/AIRLINE' },
    { id: '/AIRLINE/CATERING', code: 'CAT', name: 'Flight Catering', parent: '/AIRLINE' },
    { id: '/AIRLINE/CORPORATE', code: 'COR', name: 'Corporate Office', parent: '/AIRLINE' },
    { id: '/AIRLINE/OPS', code: 'OPS', name: 'Operations Control & Crew Scheduling', parent: '/AIRLINE' },
    { id: '/AIRLINE/OH', code: 'OHS', name: 'Occupational Health Service', parent: '/AIRLINE' }
  ];

  var sites = [
    { code: 'HUB', name: 'Central Hub (WZH)', type: 'HUB', clinic: true, address: 'Building 7, Central Hub airport (fictional)' },
    { code: 'NPT', name: 'Northport Outstation', type: 'OUTSTATION', clinic: true, address: 'Cargo Road 2, Northport (fictional)' },
    { code: 'LKS', name: 'Lakeside Outstation', type: 'OUTSTATION', clinic: false, address: 'Terminal 1, Lakeside (fictional)' },
    { code: 'HBC', name: 'Harbour City Outstation', type: 'OUTSTATION', clinic: false, address: 'Apron Office 4, Harbour City (fictional)' }
  ];

  var safetyRoles = [
    { code: 'PILOT', name: 'Pilot (flight crew)', safetyCritical: true, safetyCategory: 'FLIGHT_CREW', standard: 'Aeromedical Class 1 (demo standard)', fitnessDecider: 'AME' },
    { code: 'CABIN_CREW', name: 'Cabin crew', safetyCritical: true, safetyCategory: 'CABIN_CREW', standard: 'Cabin crew medical (demo standard)', fitnessDecider: 'AME' },
    { code: 'ENGINEER', name: 'Aircraft engineer', safetyCritical: true, safetyCategory: 'CERTIFYING_ENGINEER', standard: 'Engineering fitness standard (demo)', fitnessDecider: 'OH_PHYSICIAN' },
    { code: 'RAMP', name: 'Ramp / airside', safetyCritical: true, safetyCategory: 'AIRSIDE_DRIVER', standard: 'Airside driving standard (demo)', fitnessDecider: 'OH_PHYSICIAN' },
    { code: 'CATERING', name: 'Catering / food handler', safetyCritical: true, safetyCategory: 'FOOD_HANDLER', standard: 'Food handler fitness (demo)', fitnessDecider: 'OH_PHYSICIAN' },
    { code: 'OFFICE', name: 'Office / non-safety', safetyCritical: false, safetyCategory: null, standard: 'General fitness for work', fitnessDecider: 'OH_PHYSICIAN' }
  ];

  var jobCodes = [
    { code: 'CPT', title: 'Captain', safetyRole: 'PILOT', demands: ['Night work', 'Time-zone changes', 'Safety-critical decisions'] },
    { code: 'FO', title: 'First Officer', safetyRole: 'PILOT', demands: ['Night work', 'Time-zone changes', 'Safety-critical decisions'] },
    { code: 'SCCM', title: 'Senior Cabin Crew Member', safetyRole: 'CABIN_CREW', demands: ['Standing for long periods', 'Pushing trolleys', 'Emergency evacuation duties'] },
    { code: 'CCM', title: 'Cabin Crew Member', safetyRole: 'CABIN_CREW', demands: ['Standing for long periods', 'Pushing trolleys', 'Emergency evacuation duties'] },
    { code: 'B1ENG', title: 'Licensed Aircraft Engineer (B1)', safetyRole: 'ENGINEER', demands: ['Working at height', 'Noise', 'Certifying release to service'] },
    { code: 'MECH', title: 'Aircraft Mechanic', safetyRole: 'ENGINEER', demands: ['Working at height', 'Noise', 'Manual handling up to 25 kg'] },
    { code: 'RAMP', title: 'Ramp Agent', safetyRole: 'RAMP', demands: ['Airside driving', 'Manual handling up to 23 kg', 'Noise', 'Shift work'] },
    { code: 'BAGG', title: 'Baggage Handler', safetyRole: 'RAMP', demands: ['Manual handling up to 23 kg', 'Forklift operation', 'Shift work'] },
    { code: 'CHEF', title: 'Production Chef', safetyRole: 'CATERING', demands: ['Food handling', 'Wet work', 'Standing'] },
    { code: 'CATASST', title: 'Catering Assistant', safetyRole: 'CATERING', demands: ['Food handling', 'Wet work', 'Manual handling up to 15 kg'] },
    { code: 'CATDRV', title: 'Catering High-loader Driver', safetyRole: 'RAMP', demands: ['Airside driving', 'Working at height (high-loader)'] },
    { code: 'OFFICE', title: 'Office Administrator', safetyRole: 'OFFICE', demands: ['Display screen equipment'] },
    { code: 'ANALYST', title: 'Business Analyst', safetyRole: 'OFFICE', demands: ['Display screen equipment'] },
    { code: 'MGR', title: 'Line Manager', safetyRole: 'OFFICE', demands: ['Display screen equipment'] },
    { code: 'HRBP', title: 'HR Business Partner', safetyRole: 'OFFICE', demands: ['Display screen equipment'] }
  ];

  // ---------------------------------------------------------------- employees (40)
  function emp(id, name, unit, site, job, managerId, extra) {
    var e = {
      id: id, name: name, orgUnit: '/AIRLINE/' + unit, site: site, jobCode: job, managerId: managerId,
      email: name.toLowerCase().replace(/[^a-z]+/g, '.') + '@wellnessz-air.example',
      language: 'en', shift: 'DAY', fitness: { status: 'FIT', since: day(-200), decidedBy: null },
      dutyStatus: 'AVAILABLE', dutySince: day(-200), startDate: day(-900)
    };
    if (extra) for (var k in extra) e[k] = extra[k];
    return e;
  }
  var employees = [
    // Managers and HR
    emp('M2001', 'Max Manager', 'CATERING', 'HUB', 'MGR', null),
    emp('M2002', 'Isla Inflight', 'INFLIGHT', 'HUB', 'MGR', null),
    emp('M2003', 'Gus Ground', 'GROUND', 'HUB', 'MGR', null),
    emp('M2004', 'Felix Fleet', 'FLIGHT_OPS', 'HUB', 'CPT', null, { note: 'Chief pilot and line manager' }),
    emp('M2005', 'Erica Engineering', 'ENGINEERING', 'HUB', 'MGR', null),
    emp('M2006', 'Owen Office', 'CORPORATE', 'HUB', 'MGR', null),
    emp('H3001', 'Hana HR', 'CORPORATE', 'HUB', 'HRBP', null),
    // Flight operations (manager Felix Fleet)
    emp('E1020', 'Paolo Pilot', 'FLIGHT_OPS', 'HUB', 'FO', 'M2004', { shift: 'ROSTERED' }),
    emp('E1021', 'Carlos Captain', 'FLIGHT_OPS', 'HUB', 'CPT', 'M2004', { shift: 'ROSTERED' }),
    emp('E1022', 'Fern Flightdeck', 'FLIGHT_OPS', 'HUB', 'FO', 'M2004', { shift: 'ROSTERED' }),
    emp('E1023', 'Wes Wingman', 'FLIGHT_OPS', 'NPT', 'FO', 'M2004', { shift: 'ROSTERED', fitness: { status: 'TEMPORARILY_UNFIT', since: day(-12), decidedBy: 'ame1' }, dutyStatus: 'RELIEVED', dutySince: day(-12) }),
    emp('E1024', 'Hal Heading', 'FLIGHT_OPS', 'HUB', 'CPT', 'M2004', { shift: 'ROSTERED' }),
    // Inflight (manager Isla Inflight)
    emp('E1010', 'Cara Cabin', 'INFLIGHT', 'HUB', 'CCM', 'M2002', { shift: 'ROSTERED', fitness: { status: 'TEMPORARILY_UNFIT', since: day(-4), decidedBy: 'ame1' } }),
    emp('E1011', 'Sam Steward', 'INFLIGHT', 'HUB', 'CCM', 'M2002', { shift: 'ROSTERED' }),
    emp('E1012', 'Lily Lounge', 'INFLIGHT', 'HUB', 'CCM', 'M2002', { shift: 'ROSTERED' }),
    emp('E1013', 'Theo Trolley', 'INFLIGHT', 'HUB', 'CCM', 'M2002', { shift: 'ROSTERED' }),
    emp('E1014', 'Mae Manifest', 'INFLIGHT', 'HUB', 'SCCM', 'M2002', { shift: 'ROSTERED', fitness: { status: 'TEMPORARILY_UNFIT', since: day(-45), decidedBy: 'ame1' } }),
    emp('E1015', 'Nell Napkin', 'INFLIGHT', 'LKS', 'CCM', 'M2002', { shift: 'ROSTERED' }),
    emp('E1016', 'Remy Recline', 'INFLIGHT', 'HUB', 'CCM', 'M2002', { shift: 'ROSTERED' }),
    emp('E1017', 'Bo Boarding', 'INFLIGHT', 'HUB', 'SCCM', 'M2002', { shift: 'ROSTERED' }),
    // Engineering (manager Erica Engineering)
    emp('E1030', 'Ezra Engineer', 'ENGINEERING', 'HUB', 'B1ENG', 'M2005', { shift: 'NIGHT' }),
    emp('E1031', 'Tess Torque', 'ENGINEERING', 'HUB', 'MECH', 'M2005', { shift: 'NIGHT', fitness: { status: 'FIT_WITH_RESTRICTIONS', since: day(-20), decidedBy: 'physician1' } }),
    emp('E1032', 'Milo Mechanic', 'ENGINEERING', 'NPT', 'MECH', 'M2005', { shift: 'DAY' }),
    emp('E1033', 'Rhea Rivet', 'ENGINEERING', 'HUB', 'B1ENG', 'M2005', { shift: 'DAY' }),
    emp('E1034', 'Hank Hangar', 'ENGINEERING', 'HUB', 'MECH', 'M2005', { shift: 'NIGHT' }),
    // Ground (manager Gus Ground)
    emp('E1003', 'Faro Forklift', 'GROUND', 'HUB', 'BAGG', 'M2003', { shift: 'EARLY' }),
    emp('E1004', 'Rami Ramp', 'GROUND', 'HUB', 'RAMP', 'M2003', { shift: 'EARLY', language: 'ar' }),
    emp('E1005', 'Gina Ground', 'GROUND', 'HUB', 'RAMP', 'M2003', { shift: 'LATE' }),
    emp('E1006', 'Bea Baggage', 'GROUND', 'HUB', 'BAGG', 'M2003', { shift: 'EARLY' }),
    emp('E1007', 'Dev Dolly', 'GROUND', 'HUB', 'RAMP', 'M2003', { shift: 'LATE', dutyStatus: 'RETURN_PENDING', dutySince: day(-9) }),
    emp('E1008', 'Toby Tug', 'GROUND', 'HBC', 'RAMP', 'M2003', { shift: 'EARLY' }),
    emp('E1009', 'Lars Loader', 'GROUND', 'HUB', 'BAGG', 'M2003', { shift: 'LATE' }),
    // Catering (manager Max Manager)
    emp('E1001', 'Erin Employee', 'CATERING', 'HUB', 'CATASST', 'M2001', { shift: 'EARLY', fitness: { status: 'FIT_WITH_RESTRICTIONS', since: day(-6), decidedBy: 'physician1' } }),
    emp('E1002', 'Colm Chef', 'CATERING', 'HUB', 'CHEF', 'M2001', { shift: 'EARLY' }),
    emp('E1041', 'Sofia Sauce', 'CATERING', 'HUB', 'CHEF', 'M2001', { shift: 'LATE' }),
    emp('E1042', 'Kit Kitchen', 'CATERING', 'HUB', 'CATASST', 'M2001', { shift: 'LATE' }),
    emp('E1043', 'Dina Driver', 'CATERING', 'HUB', 'CATDRV', 'M2001', { shift: 'EARLY' }),
    // Corporate (manager Owen Office)
    emp('E1050', 'Olive Office', 'CORPORATE', 'HUB', 'OFFICE', 'M2006'),
    emp('E1051', 'Andy Analyst', 'CORPORATE', 'HUB', 'ANALYST', 'M2006'),
    emp('E1052', 'Rosa Records', 'CORPORATE', 'HUB', 'OFFICE', 'M2006')
  ];

  // ---------------------------------------------------------------- partner organisations
  var partnerOrgs = [
    { code: 'CITYHOSP', name: 'City Hospital (fictional)', type: 'HOSPITAL', services: ['ORTHO_CONSULT', 'MRI', 'HAND_CLINIC'] },
    { code: 'PHYSIOPLUS', name: 'PhysioPlus Clinics (fictional)', type: 'PHYSIOTHERAPY', services: ['PHYSIO_ASSESS'] },
    { code: 'EAP_WELLBEING', name: 'Wellbeing EAP Services (fictional)', type: 'EAP', services: ['EAP_COUNSELLING'] },
    { code: 'GULFTPA', name: 'Gulf TPA Services (fictional)', type: 'TPA', services: [] },
    { code: 'TOXLAB', name: 'ToxLab Analytical (fictional)', type: 'LABORATORY', services: [] }
  ];
  var serviceCatalogue = [
    { code: 'PHYSIO_ASSESS', name: 'Physiotherapy assessment & treatment', partnerOrg: 'PHYSIOPLUS', reportDueDays: 14 },
    { code: 'ORTHO_CONSULT', name: 'Orthopaedic consultation', partnerOrg: 'CITYHOSP', reportDueDays: 10 },
    { code: 'MRI', name: 'MRI imaging', partnerOrg: 'CITYHOSP', reportDueDays: 7 },
    { code: 'HAND_CLINIC', name: 'Hand surgery clinic review', partnerOrg: 'CITYHOSP', reportDueDays: 10 },
    { code: 'EAP_COUNSELLING', name: 'EAP short-term counselling (up to 6 sessions)', partnerOrg: 'EAP_WELLBEING', reportDueDays: 42 }
  ];

  // ---------------------------------------------------------------- demo users
  // apps: employee | manager | oh | partner  (a user may appear in more than one app)
  function user(username, name, roles, apps, employeeId, orgScope, title, partnerOrg) {
    return { username: username, name: name, roles: roles, apps: apps, employeeId: employeeId || null,
      orgScope: orgScope, title: title, partnerOrg: partnerOrg || null, mfa: !!partnerOrg };
  }
  var users = [
    // Employee & crew app
    user('employee1', 'Erin Employee', ['EMPLOYEE'], ['employee'], 'E1001', '/AIRLINE/CATERING', 'Catering assistant (food handler)'),
    user('crew1', 'Cara Cabin', ['EMPLOYEE'], ['employee'], 'E1010', '/AIRLINE/INFLIGHT', 'Cabin crew member'),
    user('pilot1', 'Paolo Pilot', ['EMPLOYEE'], ['employee'], 'E1020', '/AIRLINE/FLIGHT_OPS', 'First officer'),
    user('engineer1', 'Ezra Engineer', ['EMPLOYEE'], ['employee'], 'E1030', '/AIRLINE/ENGINEERING', 'Licensed aircraft engineer'),
    user('ramp1', 'Rami Ramp', ['EMPLOYEE'], ['employee'], 'E1004', '/AIRLINE/GROUND', 'Ramp agent'),
    user('baggage1', 'Bea Baggage', ['EMPLOYEE'], ['employee'], 'E1006', '/AIRLINE/GROUND', 'Baggage handler'),
    user('office1', 'Olive Office', ['EMPLOYEE'], ['employee'], 'E1050', '/AIRLINE/CORPORATE', 'Office administrator'),
    // Manager & HR workspace
    user('manager1', 'Max Manager', ['MANAGER', 'EMPLOYEE'], ['manager'], 'M2001', '/AIRLINE/CATERING', 'Catering line manager'),
    user('manager2', 'Isla Inflight', ['MANAGER', 'EMPLOYEE'], ['manager'], 'M2002', '/AIRLINE/INFLIGHT', 'Cabin crew line manager'),
    user('manager3', 'Gus Ground', ['MANAGER', 'EMPLOYEE'], ['manager'], 'M2003', '/AIRLINE/GROUND', 'Ground services line manager'),
    user('manager4', 'Felix Fleet', ['MANAGER', 'EMPLOYEE'], ['manager'], 'M2004', '/AIRLINE/FLIGHT_OPS', 'Chief pilot (line manager)'),
    user('hr1', 'Hana HR', ['HR', 'EMPLOYEE'], ['manager'], 'H3001', '/AIRLINE', 'HR business partner'),
    user('crewsched1', 'Carla Scheduler', ['CREW_SCHEDULING'], ['manager'], null, '/AIRLINE/OPS', 'Crew scheduling'),
    user('safety1', 'Sally Safety', ['SAFETY_MANAGER'], ['manager', 'oh'], null, '/AIRLINE', 'Safety manager'),
    user('hse1', 'Hassan Safety', ['HSE_OFFICER'], ['manager'], null, '/AIRLINE', 'HSE officer (EHS incidents)'),
    // OH clinical workspace
    user('nurse1', 'Nina Nurse', ['OH_CLINICIAN'], ['oh'], null, '/AIRLINE/OH', 'OH nurse'),
    user('physician1', 'Phil Physician', ['OH_PHYSICIAN', 'OH_CLINICIAN'], ['oh'], null, '/AIRLINE/OH', 'OH physician'),
    user('ame1', 'Ava AME', ['AME', 'OH_PHYSICIAN', 'OH_CLINICIAN'], ['oh'], null, '/AIRLINE/OH', 'Aeromedical examiner'),
    user('psych1', 'Pat Psych', ['PSYCH_CLINICIAN'], ['oh'], null, '/AIRLINE/OH', 'Named mental-health clinician'),
    user('mro1', 'Dr Mia Reviewer', ['MRO'], ['oh'], null, '/AIRLINE', 'Medical review officer'),
    user('casemgr1', 'Casey Casemanager', ['CASE_MANAGER'], ['oh'], null, '/AIRLINE/OH', 'Nurse case manager'),
    user('claims1', 'Clare Claims', ['CLAIMS_COORDINATOR'], ['oh'], null, '/AIRLINE', 'Claims coordinator'),
    user('promo1', 'Paula Promotion', ['HEALTH_PROMOTION_LEAD'], ['oh'], null, '/AIRLINE', 'Health promotion lead'),
    user('damanager1', 'Dan Programme', ['DA_PROGRAMME_MANAGER'], ['oh'], null, '/AIRLINE', 'D&A programme manager'),
    user('collector1', 'Colin Collector', ['COLLECTOR'], ['oh'], null, '/AIRLINE', 'Specimen collector'),
    user('ergo1', 'Erik Ergonomist', ['ERGONOMIST'], ['oh'], null, '/AIRLINE', 'Ergonomist'),
    user('peer1', 'Pete Peer', ['PEER_SUPPORT_VOLUNTEER'], ['oh'], null, '/AIRLINE/OPS', 'Peer support volunteer'),
    user('nursinghead1', 'Nora Nursing-Head', ['NURSING_HEAD', 'OH_CLINICIAN', 'CASE_MANAGER'], ['oh'], null, '/AIRLINE/OH', 'Nursing head'),
    user('dpo1', 'Dana Protection', ['DPO'], ['oh'], null, '/AIRLINE', 'Data protection officer'),
    // Partner portal
    user('partner1', 'Petra Partner', ['PARTNER_PROVIDER'], ['partner'], null, '/PARTNERS/CITYHOSP', 'Hospital referral co-ordinator', 'CITYHOSP'),
    user('physio1', 'Phoebe Physio', ['PARTNER_PROVIDER'], ['partner'], null, '/PARTNERS/PHYSIOPLUS', 'Physiotherapist', 'PHYSIOPLUS'),
    user('eap1', 'Eve Counsellor', ['PARTNER_PROVIDER', 'EAP_PARTNER'], ['partner'], null, '/PARTNERS/EAP_WELLBEING', 'EAP counsellor', 'EAP_WELLBEING'),
    user('adjuster1', 'Adam Adjuster', ['TPA_ADJUSTER'], ['partner'], null, '/PARTNERS/GULFTPA', 'TPA claims adjuster', 'GULFTPA')
  ];

  // ---------------------------------------------------------------- catalogues and config
  var restrictionCatalogue = [
    { code: 'NO_LIFT_10', text: 'No lifting above 10 kg', category: 'MANUAL_HANDLING' },
    { code: 'NO_LIFT_5', text: 'No lifting above 5 kg', category: 'MANUAL_HANDLING' },
    { code: 'NO_AIRSIDE_DRIVING', text: 'No airside driving', category: 'DRIVING' },
    { code: 'NO_HEIGHTS', text: 'No work at height (ladders, stands, high-loaders)', category: 'HEIGHT' },
    { code: 'NO_WET_WORK', text: 'Avoid wet work; gloves for all food preparation', category: 'SKIN' },
    { code: 'NO_DIRECT_FOOD', text: 'No direct handling of open food', category: 'FOOD_SAFETY' },
    { code: 'NO_NIGHT_SHIFTS', text: 'No night shifts', category: 'HOURS' },
    { code: 'REDUCED_HOURS', text: 'Reduced hours (phased return)', category: 'HOURS' },
    { code: 'NO_FLYING', text: 'Not to operate as crew (ground duties only)', category: 'AVIATION' },
    { code: 'SEATED_DUTIES', text: 'Mainly seated duties; breaks every 2 hours', category: 'POSTURE' },
    { code: 'NO_FORKLIFT', text: 'No forklift or tug operation', category: 'DRIVING' }
  ];

  var triageProtocols = [
    { code: 'MSK_INJURY', name: 'Musculoskeletal injury', defaultUrgency: 'SEMI_URGENT', queue: 'OH_NURSE' },
    { code: 'HAND_INJURY', name: 'Hand / crush injury', defaultUrgency: 'URGENT', queue: 'PHYSICIAN' },
    { code: 'SKIN', name: 'Skin condition (food handler)', defaultUrgency: 'SEMI_URGENT', queue: 'FOOD_SAFETY' },
    { code: 'AERO_FITNESS', name: 'Aeromedical decrease in fitness', defaultUrgency: 'URGENT', queue: 'AEROMEDICAL' },
    { code: 'ABSENCE', name: 'Absence management referral', defaultUrgency: 'ROUTINE', queue: 'CASE_MANAGEMENT' },
    { code: 'NOISE', name: 'Noise-induced threshold shift', defaultUrgency: 'ROUTINE', queue: 'OH_NURSE' },
    { code: 'DSE', name: 'Display-screen / ergonomic complaint', defaultUrgency: 'ROUTINE', queue: 'OH_NURSE' },
    { code: 'COMMUNICABLE', name: 'Communicable disease', defaultUrgency: 'URGENT', queue: 'PHYSICIAN' },
    { code: 'MENTAL_HEALTH', name: 'Psychological (restricted)', defaultUrgency: 'URGENT', queue: 'MENTAL_HEALTH' }
  ];

  var config = {
    minGroupSize: 5,
    namedClinicians: ['psych1'],
    actingMroPhysicians: [],
    absenceTriggerRules: [
      { code: 'SPELLS_6M', name: '4 or more spells in a rolling 6 months', spells: 4, months: 6 },
      { code: 'LONG_TERM', name: '14 or more calendar days in one spell', days: 14 }
    ],
    supportScreen: { name: 'Wellbeing check (PHQ-style, 9 items, demo)', urgentItem: 9, urgentScore: 20 },
    emergencyNumber: '999',
    breakGlassHours: 4
  };

  // ---------------------------------------------------------------- surveillance
  var surveillanceProgrammes = [
    { code: 'NOISE', name: 'Noise surveillance (audiometry)', intervalMonths: 24, jobCodes: ['B1ENG', 'MECH', 'RAMP', 'BAGG'], slotType: 'SURVEILLANCE' },
    { code: 'FOOD_HANDLER', name: 'Food handler fitness review', intervalMonths: 12, jobCodes: ['CHEF', 'CATASST'], slotType: 'SURVEILLANCE' },
    { code: 'NIGHT_WORKER', name: 'Night worker health assessment', intervalMonths: 12, jobCodes: ['B1ENG', 'MECH'], slotType: 'SURVEILLANCE' },
    { code: 'AIRCREW_CLASS1', name: 'Aeromedical Class 1 renewal', intervalMonths: 12, jobCodes: ['CPT', 'FO'], slotType: 'AME_EXAM' },
    { code: 'CABIN_CREW_MED', name: 'Cabin crew medical', intervalMonths: 60, jobCodes: ['CCM', 'SCCM'], slotType: 'AME_EXAM' },
    { code: 'AIRSIDE_DRIVER', name: 'Airside driver medical', intervalMonths: 36, jobCodes: ['RAMP', 'CATDRV'], slotType: 'SURVEILLANCE' }
  ];
  var surv = [];
  function enrol(empId, prog, dueDays, status, last) {
    surv.push({ id: rid('SRV', surv.length + 101), employeeId: empId, programme: prog, dueDate: day(dueDays),
      status: status, lastDone: last || day(dueDays - 365), lastOutcome: 'NORMAL', appointmentId: null, results: [] });
  }
  enrol('E1030', 'NOISE', 6, 'DUE', day(-724));
  enrol('E1030', 'NIGHT_WORKER', 120, 'NOT_DUE');
  enrol('E1031', 'NOISE', -20, 'OVERDUE', day(-750));
  enrol('E1032', 'NOISE', 40, 'NOT_DUE');
  enrol('E1033', 'NOISE', 12, 'DUE');
  enrol('E1034', 'NOISE', -3, 'FOLLOW_UP', day(-3));
  enrol('E1004', 'NOISE', 90, 'NOT_DUE');
  enrol('E1004', 'AIRSIDE_DRIVER', 25, 'DUE');
  enrol('E1006', 'NOISE', -9, 'OVERDUE');
  enrol('E1009', 'NOISE', 4, 'DUE');
  enrol('E1001', 'FOOD_HANDLER', 30, 'NOT_DUE');
  enrol('E1002', 'FOOD_HANDLER', 2, 'DUE');
  enrol('E1041', 'FOOD_HANDLER', -5, 'OVERDUE');
  enrol('E1042', 'FOOD_HANDLER', 60, 'NOT_DUE');
  enrol('E1020', 'AIRCREW_CLASS1', 18, 'DUE');
  enrol('E1021', 'AIRCREW_CLASS1', 140, 'NOT_DUE');
  enrol('E1022', 'AIRCREW_CLASS1', -2, 'OVERDUE');
  enrol('E1010', 'CABIN_CREW_MED', 400, 'NOT_DUE');
  enrol('E1043', 'AIRSIDE_DRIVER', 9, 'DUE');
  surv[5].lastOutcome = 'ABNORMAL';
  surv[5].results.push({ at: at(-3, '10:15'), by: 'nurse1', outcome: 'ABNORMAL', summary: 'Audiometry: 4 kHz notch both ears, shift of 15 dB on baseline.' });

  // ---------------------------------------------------------------- clinic slots (next 10 working days)
  var clinicSlots = [];
  var slotN = 1;
  var hubPattern = [
    ['08:30', 'SURVEILLANCE', 'nurse1'], ['09:30', 'OH_CONSULT', 'nurse1'], ['10:30', 'AME_EXAM', 'ame1'],
    ['11:30', 'TRAVEL', 'nurse1'], ['13:30', 'OH_CONSULT', 'physician1'], ['14:30', 'SURVEILLANCE', 'nurse1'],
    ['15:30', 'SCREENING', 'nurse1']
  ];
  for (var d = 1, made = 0; made < 10; d++) {
    if (isWeekend(d)) continue;
    made++;
    hubPattern.forEach(function (p) {
      clinicSlots.push({ id: 'SLOT-' + pad(slotN++, 4), site: 'HUB', start: at(d, p[0]), minutes: 45, type: p[1], clinician: p[2], bookedBy: null, appointmentId: null });
    });
    if (made % 2 === 0) {
      clinicSlots.push({ id: 'SLOT-' + pad(slotN++, 4), site: 'NPT', start: at(d, '10:00'), minutes: 45, type: 'SURVEILLANCE', clinician: 'nurse1', bookedBy: null, appointmentId: null });
      clinicSlots.push({ id: 'SLOT-' + pad(slotN++, 4), site: 'NPT', start: at(d, '11:00'), minutes: 45, type: 'OH_CONSULT', clinician: 'nurse1', bookedBy: null, appointmentId: null });
    }
  }
  // Pre-book a few slots
  function bookSeed(slotIdx, apptId, empId, reason, caseId) {
    var s = clinicSlots[slotIdx]; s.bookedBy = empId; s.appointmentId = apptId;
    return { id: apptId, employeeId: empId, slotId: s.id, site: s.site, start: s.start, type: s.type,
      clinician: s.clinician, status: 'BOOKED', reason: reason, caseId: caseId || null, external: false };
  }
  var appointments = [
    bookSeed(9, rid('APT', 51), 'E1010', 'AME review – return to flying', cid('INF', 103)),
    bookSeed(5, rid('APT', 52), 'E1034', 'Repeat audiometry', cid('ENG', 112)),
    bookSeed(15, rid('APT', 53), 'E1051', 'Workstation follow-up', cid('COR', 109)),
    { id: rid('APT', 54), employeeId: 'E1014', slotId: null, site: 'CITYHOSP', start: at(3, '14:00'), type: 'EXTERNAL', clinician: 'City Hospital (fictional)', status: 'BOOKED', reason: 'Orthopaedic consultation (partner referral)', caseId: cid('INF', 108), external: true }
  ];

  // ---------------------------------------------------------------- roster (crew flights and ground shifts)
  var flights = {
    WZ101: { from: 'HUB', to: 'PSL' }, WZ102: { from: 'PSL', to: 'HUB' },
    WZ221: { from: 'HUB', to: 'KSD' }, WZ222: { from: 'KSD', to: 'HUB' },
    WZ305: { from: 'HUB', to: 'MTV' }, WZ306: { from: 'MTV', to: 'HUB' }
  };
  var roster = [];
  function fly(flight, days, crew) {
    crew.forEach(function (e) { roster.push({ employeeId: e, date: day(days), duty: flight, from: flights[flight].from, to: flights[flight].to }); });
  }
  // Past: index flight for the communicable-disease scenario (Sam Steward on WZ101/102)
  fly('WZ101', -3, ['E1011', 'E1010', 'E1012', 'E1020', 'E1021']);
  fly('WZ102', -2, ['E1011', 'E1010', 'E1012', 'E1020', 'E1021']);
  fly('WZ305', -5, ['E1013', 'E1016', 'E1022', 'E1024']);
  fly('WZ306', -4, ['E1013', 'E1016', 'E1022', 'E1024']);
  // Future: Port Solano and Kestrel Sands rotations
  fly('WZ101', 2, ['E1010', 'E1015', 'E1016', 'E1021', 'E1022']);
  fly('WZ102', 3, ['E1010', 'E1015', 'E1016', 'E1021', 'E1022']);
  fly('WZ221', 4, ['E1010', 'E1013', 'E1017', 'E1020', 'E1024']);
  fly('WZ222', 5, ['E1010', 'E1013', 'E1017', 'E1020', 'E1024']);
  fly('WZ101', 6, ['E1011', 'E1012', 'E1017', 'E1020', 'E1024']);
  // Ground shifts (contact tracing in shared work areas)
  ['E1001', 'E1002', 'E1043'].forEach(function (e) { roster.push({ employeeId: e, date: day(-1), duty: 'SHIFT:HUB-KITCHEN-EARLY', from: 'HUB', to: 'HUB' }); });
  ['E1004', 'E1006', 'E1003'].forEach(function (e) { roster.push({ employeeId: e, date: day(-1), duty: 'SHIFT:HUB-RAMP-EARLY', from: 'HUB', to: 'HUB' }); });

  var destinations = [
    { code: 'PSL', name: 'Port Solano (fictional, tropical coast)', risks: ['Dengue (mosquito-borne)', 'Yellow fever zone', 'Malaria (rural areas)', 'Travellers\' diarrhoea'],
      vaccines: ['Yellow fever (certificate required)', 'Hepatitis A', 'Typhoid'], advice: ['Insect-bite avoidance day and night', 'Safe food and water', 'Carry the yellow fever certificate on every rotation'] },
    { code: 'KSD', name: 'Kestrel Sands (fictional, desert)', risks: ['Heat illness', 'Respiratory infection advisory', 'Sun exposure'],
      vaccines: ['Hepatitis A', 'Seasonal influenza'], advice: ['Hydration plan on layover', 'Avoid close contact with camels and other livestock (respiratory advisory)', 'Report fever or cough within 14 days of return'] },
    { code: 'MTV', name: 'Mount Verity (fictional, high altitude)', risks: ['Altitude sickness', 'Cold injury'],
      vaccines: ['Routine vaccines up to date'], advice: ['Rest on arrival', 'Know the symptoms of altitude sickness'] }
  ];

  // ---------------------------------------------------------------- cases
  function tl(days, hhmm, by, type, text, vis) { return { at: at(days, hhmm), by: by, type: type, text: text, vis: vis || 'STATUS' }; }
  var cases = [
    {
      id: cid('CAT', 101), employeeId: 'E1001', category: 'ILLNESS', type: 'ILLNESS', source: 'SELF_REFERRAL',
      classification: 'CLINICAL', status: 'MONITORING', urgency: 'SEMI_URGENT', queue: 'FOOD_SAFETY', owner: 'nurse1',
      openedAt: at(-14, '08:12'), updatedAt: at(-5, '16:00'), recordable: false,
      summary: 'Irritant contact dermatitis both hands; aggravated by wet work and detergent. Emollient regime started.',
      workImpact: 'Difficulty wearing nitrile gloves for long periods.',
      triage: { protocol: 'SKIN', urgency: 'SEMI_URGENT', queue: 'FOOD_SAFETY', by: 'nurse1', at: at(-14, '10:30'), answers: { redFlags: 'None', infection: 'No signs of infection' } },
      fitness: { status: 'FIT_WITH_RESTRICTIONS', decidedBy: 'physician1', decidedAt: at(-6, '11:00'), reviewDate: day(22), note: 'Fit for catering duties with skin-protection restrictions.' },
      restrictions: [{ code: 'NO_WET_WORK', text: 'Avoid wet work; gloves for all food preparation', until: day(22) }],
      rtwPlanId: rid('RTW', 12), linkedIncident: null, tasks: [],
      timeline: [
        tl(-14, '08:12', 'employee1', 'REFERRAL', 'Self-referral received (skin problem affecting work).'),
        tl(-14, '10:30', 'nurse1', 'TRIAGE', 'Triaged SEMI_URGENT, food-safety queue.'),
        tl(-14, '10:31', 'nurse1', 'NOTE', 'Hands: erythema and fissuring, no vesicles. Advised emollients.', 'OH'),
        tl(-6, '11:00', 'physician1', 'FITNESS', 'Fitness decision: Fit with restrictions (review in 4 weeks).'),
        tl(-5, '16:00', 'nurse1', 'RTW', 'Adjusted-duties plan issued for acknowledgement.')
      ]
    },
    {
      id: cid('GND', 102), employeeId: 'E1005', category: 'INJURY', type: 'INJURY', source: 'EHS_INCIDENT',
      classification: 'CLINICAL', status: 'INTAKE', urgency: null, queue: 'INTAKE', owner: null,
      openedAt: at(0, '06:40'), updatedAt: at(0, '06:40'), recordable: null,
      summary: 'Slip on wet apron at stand 14; landed on right wrist. First aid given at the airside post.',
      workImpact: 'Unable to grip tow bar.', linkedIncident: 'EHS-' + YEAR + '-3381',
      triage: null, fitness: { status: 'NOT_ASSESSED' }, restrictions: [], rtwPlanId: null, tasks: [],
      timeline: [tl(0, '06:40', 'system', 'REFERRAL', 'Case opened from EHS incident EHS-' + YEAR + '-3381.')]
    },
    {
      id: cid('INF', 103), employeeId: 'E1010', category: 'DECREASE_IN_FITNESS', type: 'FITNESS', source: 'FITNESS_DECLARATION',
      classification: 'CLINICAL', status: 'FITNESS_REVIEW', urgency: 'URGENT', queue: 'AEROMEDICAL', owner: 'ame1',
      openedAt: at(-5, '19:20'), updatedAt: at(-4, '12:00'), recordable: false,
      summary: 'Right ear pain and muffled hearing after descent (barotrauma). Tympanic membrane intact, effusion present.',
      workImpact: 'Declared unfit to fly after sector WZ102.',
      triage: { protocol: 'AERO_FITNESS', urgency: 'URGENT', queue: 'AEROMEDICAL', by: 'nurse1', at: at(-5, '20:00') },
      fitness: { status: 'TEMPORARILY_UNFIT', decidedBy: 'ame1', decidedAt: at(-4, '12:00'), reviewDate: day(5), note: 'Not to operate as crew until review.' },
      restrictions: [{ code: 'NO_FLYING', text: 'Not to operate as crew (ground duties only)', until: day(5) }],
      rtwPlanId: null, tasks: [],
      timeline: [
        tl(-5, '19:20', 'crew1', 'REFERRAL', 'Crew fitness declaration received (decrease in fitness).'),
        tl(-5, '20:00', 'nurse1', 'TRIAGE', 'Triaged URGENT, aeromedical queue.'),
        tl(-4, '12:00', 'ame1', 'FITNESS', 'Fitness decision: Temporarily unfit (review booked).'),
        tl(-4, '12:05', 'ame1', 'NOTE', 'Otoscopy: right TM retracted with effusion. Nasal decongestant advised.', 'OH')
      ]
    },
    {
      id: cid('ENG', 104), employeeId: 'E1030', category: 'INJURY', type: 'INJURY', source: 'EHS_INCIDENT',
      classification: 'CLINICAL', status: 'ASSESSMENT', urgency: 'URGENT', queue: 'PHYSICIAN', owner: 'nurse1',
      openedAt: at(-2, '23:10'), updatedAt: at(-1, '09:00'), recordable: true,
      summary: 'Left hand caught between cowling and stand during night shift. Swelling over 4th and 5th metacarpals; X-ray at hub clinic shows no displaced fracture.',
      workImpact: 'Cannot use hand tools with the left hand.', linkedIncident: 'EHS-' + YEAR + '-3374',
      triage: { protocol: 'HAND_INJURY', urgency: 'URGENT', queue: 'PHYSICIAN', by: 'nurse1', at: at(-2, '23:40') },
      fitness: { status: 'UNDER_REVIEW' }, restrictions: [], rtwPlanId: null, tasks: [],
      timeline: [
        tl(-2, '23:10', 'system', 'REFERRAL', 'Case opened from EHS incident EHS-' + YEAR + '-3374.'),
        tl(-2, '23:40', 'nurse1', 'TRIAGE', 'Triaged URGENT, physician queue. Recordable (lost time likely).'),
        tl(-1, '09:00', 'nurse1', 'ASSIGN', 'Assigned to Nina Nurse.')
      ]
    },
    {
      id: cid('ENG', 105), employeeId: 'E1031', category: 'INJURY', type: 'INJURY', source: 'EHS_INCIDENT',
      classification: 'CLINICAL', status: 'TREATMENT', urgency: 'SEMI_URGENT', queue: 'OH_NURSE', owner: 'nurse1',
      openedAt: at(-26, '02:30'), updatedAt: at(-3, '15:00'), recordable: true,
      summary: 'Right knee twisting injury stepping off maintenance stand. Suspected medial ligament strain.',
      workImpact: 'Cannot climb stands or ladders.', linkedIncident: 'EHS-' + YEAR + '-3301',
      triage: { protocol: 'MSK_INJURY', urgency: 'SEMI_URGENT', queue: 'OH_NURSE', by: 'nurse1', at: at(-26, '08:00') },
      fitness: { status: 'FIT_WITH_RESTRICTIONS', decidedBy: 'physician1', decidedAt: at(-20, '10:00'), reviewDate: day(8), note: 'Ground-level duties only.' },
      restrictions: [{ code: 'NO_HEIGHTS', text: 'No work at height (ladders, stands, high-loaders)', until: day(8) }],
      rtwPlanId: rid('RTW', 11), treatmentPlanId: rid('TP', 8), tasks: [],
      timeline: [
        tl(-26, '02:30', 'system', 'REFERRAL', 'Case opened from EHS incident.'),
        tl(-26, '08:00', 'nurse1', 'TRIAGE', 'Triaged SEMI_URGENT.'),
        tl(-20, '10:00', 'physician1', 'FITNESS', 'Fitness decision: Fit with restrictions.'),
        tl(-19, '11:00', 'nurse1', 'REFERRAL_OUT', 'Referred to PhysioPlus Clinics for physiotherapy.'),
        tl(-12, '09:00', 'claims1', 'CLAIM', 'Workers\' compensation claim opened.'),
        tl(-3, '15:00', 'physio1', 'REPORT', 'Partner report received.')
      ]
    },
    {
      id: cid('GND', 106), employeeId: 'E1008', category: 'INJURY', type: 'INJURY', source: 'MANAGER_REFERRAL',
      classification: 'CLINICAL', status: 'CLOSED', urgency: 'ROUTINE', queue: 'OH_NURSE', owner: 'nurse1',
      openedAt: at(-60, '10:00'), updatedAt: at(-30, '10:00'), closedAt: at(-30, '10:00'), closeReason: 'Recovered – full duties',
      summary: 'Low back strain after towing; resolved with physiotherapy exercises.', workImpact: 'None now.',
      triage: { protocol: 'MSK_INJURY', urgency: 'ROUTINE', queue: 'OH_NURSE', by: 'nurse1', at: at(-60, '12:00') },
      fitness: { status: 'FIT', decidedBy: 'physician1', decidedAt: at(-30, '09:30') }, restrictions: [], rtwPlanId: null, tasks: [],
      timeline: [tl(-60, '10:00', 'manager3', 'REFERRAL', 'Manager referral received.'), tl(-30, '10:00', 'nurse1', 'CLOSED', 'Case closed: recovered – full duties.')]
    },
    {
      id: cid('CAT', 107), employeeId: 'E1002', category: 'ILLNESS', type: 'ILLNESS', source: 'SELF_REFERRAL',
      classification: 'CLINICAL', status: 'CLOSED', urgency: 'URGENT', queue: 'FOOD_SAFETY', owner: 'nurse1',
      openedAt: at(-21, '07:00'), updatedAt: at(-17, '08:00'), closedAt: at(-17, '08:00'), closeReason: 'Exclusion ended – symptom free 48 hours',
      summary: 'Acute gastroenteritis; food-handler exclusion for 48 hours after symptoms settled.', workImpact: 'Excluded from food handling.',
      triage: { protocol: 'COMMUNICABLE', urgency: 'URGENT', queue: 'FOOD_SAFETY', by: 'nurse1', at: at(-21, '07:30') },
      fitness: { status: 'FIT', decidedBy: 'physician1', decidedAt: at(-17, '08:00') }, restrictions: [], rtwPlanId: null, tasks: [],
      timeline: [tl(-21, '07:00', 'system', 'REFERRAL', 'Self-referral (food handler – illness).'), tl(-17, '08:00', 'nurse1', 'CLOSED', 'Case closed: exclusion ended.')]
    },
    {
      id: cid('INF', 108), employeeId: 'E1014', category: 'ILLNESS', type: 'ABSENCE', source: 'ABSENCE_TRIGGER',
      classification: 'CLINICAL', status: 'TREATMENT', urgency: 'ROUTINE', queue: 'CASE_MANAGEMENT', owner: 'casemgr1',
      openedAt: at(-30, '09:00'), updatedAt: at(-8, '14:00'), recordable: false,
      summary: 'Long-term absence: shoulder pain limiting overhead reach (stowing bags). Orthopaedic opinion requested.',
      workImpact: 'Cannot reach overhead lockers.',
      triage: { protocol: 'ABSENCE', urgency: 'ROUTINE', queue: 'CASE_MANAGEMENT', by: 'casemgr1', at: at(-30, '10:00') },
      fitness: { status: 'TEMPORARILY_UNFIT', decidedBy: 'ame1', decidedAt: at(-28, '11:00'), reviewDate: day(14) },
      restrictions: [], rtwPlanId: null, tasks: [],
      timeline: [
        tl(-30, '09:00', 'system', 'REFERRAL', 'Opened from absence trigger (14+ days).'),
        tl(-28, '11:00', 'ame1', 'FITNESS', 'Fitness decision: Temporarily unfit.'),
        tl(-12, '10:00', 'casemgr1', 'ADVICE', 'Management advice report released to HR and manager.'),
        tl(-8, '14:00', 'nurse1', 'REFERRAL_OUT', 'Referred to City Hospital (orthopaedic consultation).')
      ]
    },
    {
      id: cid('COR', 109), employeeId: 'E1051', category: 'OTHER', type: 'OTHER', source: 'SELF_REFERRAL',
      classification: 'CLINICAL', status: 'ASSESSMENT', urgency: 'ROUTINE', queue: 'OH_NURSE', owner: 'nurse1',
      openedAt: at(-9, '13:00'), updatedAt: at(-7, '10:00'), recordable: false,
      summary: 'Neck and wrist discomfort with prolonged screen work. Workstation assessment requested.', workImpact: 'Needs breaks from screen work.',
      triage: { protocol: 'DSE', urgency: 'ROUTINE', queue: 'OH_NURSE', by: 'nurse1', at: at(-9, '15:00') },
      fitness: { status: 'FIT' }, restrictions: [], rtwPlanId: null, tasks: [],
      timeline: [tl(-9, '13:00', 'system', 'REFERRAL', 'Self-referral received.'), tl(-7, '10:00', 'ergo1', 'NOTE', 'Ergonomic assessment requested.', 'OH')]
    },
    {
      id: cid('FLT', 110), employeeId: 'E1023', category: 'PSYCHOLOGICAL', type: 'MENTAL_HEALTH', source: 'SELF_REFERRAL',
      classification: 'RESTRICTED_CLINICAL', status: 'ASSESSMENT', urgency: 'URGENT', queue: 'MENTAL_HEALTH', owner: 'psych1',
      openedAt: at(-13, '21:00'), updatedAt: at(-12, '10:00'), recordable: false, supportCaseId: rid('SUP', 21),
      summary: 'Low mood and poor sleep following a bereavement. Relief from flying duties agreed with the AME.',
      workImpact: 'Relieved from flying duties.',
      triage: { protocol: 'MENTAL_HEALTH', urgency: 'URGENT', queue: 'MENTAL_HEALTH', by: 'psych1', at: at(-13, '21:30') },
      fitness: { status: 'TEMPORARILY_UNFIT', decidedBy: 'ame1', decidedAt: at(-12, '10:00'), reviewDate: day(16) },
      restrictions: [{ code: 'NO_FLYING', text: 'Not to operate as crew (ground duties only)', until: day(16) }], rtwPlanId: null, tasks: [],
      timeline: [tl(-13, '21:00', 'system', 'REFERRAL', 'Confidential self-referral.', 'RESTRICTED'), tl(-12, '10:00', 'ame1', 'FITNESS', 'Relief from duties (AME).', 'STATUS')]
    },
    {
      id: cid('GND', 111), employeeId: 'E1007', category: 'SUBSTANCE', type: 'SUBSTANCE', source: 'DA_TEST',
      classification: 'RESTRICTED_CLINICAL', status: 'MONITORING', urgency: 'URGENT', queue: 'MRO', owner: 'mro1',
      openedAt: at(-30, '15:00'), updatedAt: at(-9, '11:00'), recordable: false, testId: rid('DAT', 31),
      summary: 'Verified positive random test (demo). Return-to-duty plan with unannounced follow-up tests.',
      workImpact: 'Removed from safety-sensitive duty; return pending.',
      triage: null, fitness: { status: 'UNFIT_PENDING', decidedBy: 'mro1', decidedAt: at(-30, '15:00') },
      restrictions: [{ code: 'NO_AIRSIDE_DRIVING', text: 'No airside driving', until: null }], rtwPlanId: null, tasks: [],
      timeline: [tl(-30, '15:00', 'mro1', 'VERDICT', 'MRO verdict recorded.', 'RESTRICTED'), tl(-9, '11:00', 'mro1', 'RTD', 'Return-to-duty evaluation passed; follow-up testing plan active.', 'RESTRICTED')]
    },
    {
      id: cid('ENG', 112), employeeId: 'E1034', category: 'OTHER', type: 'SURVEILLANCE_FOLLOW_UP', source: 'SURVEILLANCE_TRIGGER',
      classification: 'CLINICAL', status: 'TRIAGE', urgency: 'ROUTINE', queue: 'OH_NURSE', owner: null,
      openedAt: at(-3, '10:20'), updatedAt: at(-3, '10:20'), recordable: null,
      summary: 'Audiometry threshold shift of 15 dB at 4 kHz (both ears) against baseline.', workImpact: 'Hearing protection review.',
      triage: null, fitness: { status: 'FIT' }, restrictions: [], rtwPlanId: null, tasks: [],
      timeline: [tl(-3, '10:20', 'system', 'REFERRAL', 'Opened from abnormal surveillance result.')]
    }
  ];

  var rtwPlans = [
    { id: rid('RTW', 11), caseId: cid('ENG', 105), employeeId: 'E1031', status: 'ACTIVE', createdBy: 'nurse1', createdAt: at(-19, '12:00'),
      startDate: day(-18), reviewDate: day(8), goal: 'Return to full mechanic duties including stands by week 4.',
      phases: [{ week: 1, hours: 'Full shift', duties: 'Ground-level inspections, stores and paperwork' }, { week: 2, hours: 'Full shift', duties: 'Ground-level component work' }, { week: 3, hours: 'Full shift', duties: 'Low steps (max 1 m) with a colleague' }, { week: 4, hours: 'Full shift', duties: 'Full duties if physiotherapy report agrees' }],
      ack: { employee: at(-18, '08:00'), manager: at(-18, '09:00') } },
    { id: rid('RTW', 12), caseId: cid('CAT', 101), employeeId: 'E1001', status: 'ISSUED', createdBy: 'nurse1', createdAt: at(-5, '16:00'),
      startDate: day(1), reviewDate: day(22), goal: 'Stay at work in catering while the skin heals; return to full food preparation duties.',
      phases: [{ week: 1, hours: 'Normal hours', duties: 'Tray assembly and packing (dry tasks only)' }, { week: 2, hours: 'Normal hours', duties: 'Dry tasks; cold-food preparation with cotton-lined gloves' }, { week: 3, hours: 'Normal hours', duties: 'Gradual return to wet tasks, max 2 hours per shift' }],
      ack: { employee: null, manager: null } }
  ];

  // ---------------------------------------------------------------- absence
  function ab(n, empId, s, e, reason, cert, src) {
    return { id: rid('ABS', n), employeeId: empId, start: day(s), end: e === null ? null : day(e), reasonCategory: reason,
      certificate: cert, source: src || 'HRIS', recordedBy: 'system', recordedAt: at(s, '08:00') };
  }
  var absences = [
    ab(201, 'E1006', -150, -149, 'Illness – short term', null),
    ab(202, 'E1006', -95, -94, 'Illness – short term', null),
    ab(203, 'E1006', -40, -38, 'Illness – short term', { status: 'NOT_REQUIRED' }),
    ab(204, 'E1014', -45, null, 'Musculoskeletal', { status: 'VALIDATED', from: day(-45), to: day(10), issuer: 'Family doctor (fictional)', fileName: 'fit-note-mae.pdf' }),
    ab(205, 'E1050', -20, -19, 'Illness – short term', null),
    ab(206, 'E1023', -12, null, 'Relieved from duties (OH)', { status: 'NOT_REQUIRED' }),
    ab(207, 'E1030', -1, null, 'Work-related injury', null, 'MANAGER'),
    ab(208, 'E1008', -62, -55, 'Musculoskeletal', { status: 'VALIDATED', from: day(-62), to: day(-55), issuer: 'Family doctor (fictional)', fileName: 'fit-note-toby.pdf' }),
    ab(209, 'E1002', -21, -18, 'Illness – food handler exclusion', { status: 'NOT_REQUIRED' }),
    ab(210, 'E1012', -70, -69, 'Illness – short term', null)
  ];
  var absenceTriggers = [
    { id: rid('TRG', 31), employeeId: 'E1014', rule: 'LONG_TERM', firedAt: at(-31, '06:00'), detail: '14 or more calendar days in one spell', status: 'ACTIONED', earlyInterventionId: rid('EI', 9) }
  ];
  var earlyInterventions = [
    { id: rid('EI', 9), employeeId: 'E1014', triggerId: rid('TRG', 31), status: 'REFERRED', raisedBy: 'hr1', raisedAt: at(-30, '08:30'), caseId: cid('INF', 108), adviceRequestId: rid('MAR', 4), note: 'Long-term absence; referred to OH for advice.' }
  ];
  var adviceRequests = [
    { id: rid('MAR', 4), employeeId: 'E1014', caseId: cid('INF', 108), requestedBy: 'hr1', requestedAt: at(-30, '08:30'),
      questions: ['When is a return to work likely?', 'Are adjustments needed on return?'], status: 'RELEASED',
      report: { by: 'casemgr1', at: at(-13, '16:00'), fitness: 'TEMPORARILY_UNFIT', likelyReturn: 'Within 4–6 weeks, subject to specialist opinion', adjustments: 'Phased return with no overhead stowage for 2 weeks', workImpact: 'Unable to reach overhead lockers at present.', review: day(14) },
      employeeReview: { decision: 'RELEASE', at: at(-12, '09:00') }, releasedAt: at(-12, '10:00') }
  ];

  // ---------------------------------------------------------------- treatment, referrals, claims
  var treatmentPlans = [
    { id: rid('TP', 8), caseId: cid('ENG', 105), employeeId: 'E1031', status: 'ACTIVE', createdBy: 'nurse1', createdAt: at(-19, '10:30'),
      goals: ['Pain-free kneeling and climbing', 'Return to stand work'], interventions: ['Physiotherapy 6 sessions', 'Home exercise programme'],
      expectedRecoveryDays: 35, followUps: [{ date: day(-12), status: 'DONE', note: 'Improving' }, { date: day(2), status: 'PLANNED', note: 'Review physiotherapy report' }] }
  ];
  var referrals = [
    { id: rid('RFL', 21), kind: 'TREATMENT', caseId: cid('ENG', 105), supportCaseId: null, employeeId: 'E1031', partnerOrg: 'PHYSIOPLUS', service: 'PHYSIO_ASSESS',
      urgency: 'ROUTINE', status: 'REPORT_RECEIVED', sentBy: 'nurse1', sentAt: at(-19, '11:00'), question: 'Assessment and treatment of right knee medial ligament strain; advise on return to stand work.',
      dataSet: ['Referral question', 'Work demands (job profile)', 'Relevant history'], consentId: rid('CON', 61),
      acceptedAt: at(-18, '09:00'), appointmentAt: at(-16, '10:00'), reportDue: day(-5),
      report: { at: at(-3, '15:00'), by: 'physio1', summary: 'Grade 1 MCL sprain resolving. Full range, mild pain on pivoting.', recommendation: 'Continue restriction from stands for 1 more week; full duties expected from week 4.', fileName: 'physioplus-report.pdf' },
      sessions: 4, closedAt: null },
    { id: rid('RFL', 22), kind: 'TREATMENT', caseId: cid('INF', 108), supportCaseId: null, employeeId: 'E1014', partnerOrg: 'CITYHOSP', service: 'ORTHO_CONSULT',
      urgency: 'ROUTINE', status: 'APPOINTMENT_SET', sentBy: 'nurse1', sentAt: at(-8, '14:00'), question: 'Shoulder pain limiting overhead reach; opinion on diagnosis and likely recovery.',
      dataSet: ['Referral question', 'Work demands (job profile)'], consentId: rid('CON', 62),
      acceptedAt: at(-7, '10:00'), appointmentAt: at(3, '14:00'), reportDue: day(13), report: null, sessions: 0, closedAt: null },
    { id: rid('RFL', 23), kind: 'EAP', caseId: null, supportCaseId: rid('SUP', 22), employeeId: 'E1012', partnerOrg: 'EAP_WELLBEING', service: 'EAP_COUNSELLING',
      urgency: 'ROUTINE', status: 'IN_PROGRESS', sentBy: 'psych1', sentAt: at(-15, '12:00'), question: 'Short-term counselling (work–life stress).',
      dataSet: ['Name and contact preference only'], consentId: rid('CON', 63),
      acceptedAt: at(-14, '09:00'), appointmentAt: at(-10, '17:00'), reportDue: day(27), report: null, sessions: 2, closedAt: null }
  ];
  var claims = [
    { id: cid('CLM', 41), caseId: cid('ENG', 105), employeeId: 'E1031', type: 'WORKERS_COMPENSATION', payerOrg: 'GULFTPA', status: 'UNDER_REVIEW',
      openedBy: 'claims1', openedAt: at(-12, '09:00'), incidentDate: day(-26), lostTimeDays: 6, reserve: 4200, currency: 'GBP',
      decision: null, rtwStatus: 'Modified duties', consentId: rid('CON', 64),
      documents: ['First report of injury', 'Fitness certificate (status only)'],
      messages: [{ at: at(-10, '11:00'), by: 'adjuster1', text: 'Received. Please confirm the expected date of full duties.' }, { at: at(-9, '09:30'), by: 'claims1', text: 'Expected within four weeks; physiotherapy ongoing.' }] }
  ];

  // ---------------------------------------------------------------- confidential support (restricted)
  var supportCases = [
    { id: rid('SUP', 21), employeeId: 'E1023', kernelCaseId: cid('FLT', 110), status: 'RELIEF', risk: 'MODERATE', source: 'SELF_REFERRAL', namedClinician: 'psych1',
      openedAt: at(-13, '21:00'), sharing: 'FITNESS_ONLY', sharingAt: at(-11, '09:00'), sharedAdjustments: null, contactPreference: 'PHONE',
      concern: 'Low mood and poor sleep after a bereavement.', screens: [{ at: at(-13, '21:05'), score: 14, item9: 0, band: 'MODERATE' }],
      urgentItems: [], notes: [{ at: at(-12, '09:00'), by: 'psych1', text: 'Assessment completed. Weekly sessions agreed. Relief from flying discussed with AME.' }], eapReferralId: null, consentId: rid('CON', 65) },
    { id: rid('SUP', 22), employeeId: 'E1012', kernelCaseId: null, status: 'REFERRED', risk: 'LOW', source: 'SELF_REFERRAL', namedClinician: 'psych1',
      openedAt: at(-16, '18:30'), sharing: 'NOTHING', sharingAt: null, sharedAdjustments: null, contactPreference: 'EMAIL',
      concern: 'Stress balancing rosters and caring responsibilities.', screens: [{ at: at(-16, '18:35'), score: 8, item9: 0, band: 'MILD' }],
      urgentItems: [], notes: [{ at: at(-15, '11:30'), by: 'psych1', text: 'Signposted; EAP counselling offered and accepted.' }], eapReferralId: rid('RFL', 23), consentId: rid('CON', 63) },
    { id: rid('SUP', 23), employeeId: 'E1007', kernelCaseId: cid('GND', 111), status: 'ACTIVE', risk: 'MODERATE', source: 'PROGRAMME_REFERRAL', namedClinician: 'psych1',
      openedAt: at(-29, '09:00'), sharing: 'NOTHING', sharingAt: null, sharedAdjustments: null, contactPreference: 'PHONE',
      concern: 'Programme referral from the substance testing pathway.', screens: [], urgentItems: [], notes: [{ at: at(-20, '10:00'), by: 'psych1', text: 'Engaged with support; attending sessions.' }], eapReferralId: null, consentId: null }
  ];
  var peerRequests = [
    { code: 'PS-7K2D-Q9XA', alias: 'Skylark', language: 'en', base: 'HUB', status: 'CONTACTED', volunteer: 'peer1', createdAt: at(-6, '22:10'), callbackPhone: '+00 7000 000 111', contacts: [{ at: at(-5, '10:00'), category: 'WORK_STRESS', outcome: 'SUPPORTED' }] },
    { code: 'PS-M4TR-8BWE', alias: 'Kestrel', language: 'en', base: 'NPT', status: 'OPEN', volunteer: 'peer1', createdAt: at(-1, '20:45'), callbackPhone: '+00 7000 000 222', contacts: [] }
  ];

  // ---------------------------------------------------------------- drug & alcohol testing
  var testing = {
    policies: [
      { code: 'DA-SAFETY', name: 'Safety-sensitive D&A policy (demo)', randomDrugRatePct: 25, randomAlcoholRatePct: 10, panels: ['Drug panel (urine)', 'Breath alcohol'], noticeMinutes: 120 }
    ],
    pools: [
      { id: 'POOL-GND-ENG', name: 'Ground & engineering safety-sensitive', policy: 'DA-SAFETY', members: ['E1003', 'E1004', 'E1005', 'E1006', 'E1008', 'E1009', 'E1043', 'E1030', 'E1031', 'E1032', 'E1033', 'E1034'] },
      { id: 'POOL-AIRCREW', name: 'Aircrew (flight and cabin)', policy: 'DA-SAFETY', members: ['E1020', 'E1021', 'E1022', 'E1024', 'E1010', 'E1011', 'E1012', 'E1013', 'E1015', 'E1016', 'E1017'] }
    ],
    selections: [
      { id: rid('SEL', 9), poolId: 'POOL-GND-ENG', at: at(-31, '07:00'), by: 'damanager1', count: 2, selected: ['E1007', 'E1032'], method: 'Seeded random draw (demo)' },
      { id: rid('SEL', 10), poolId: 'POOL-AIRCREW', at: at(-6, '07:00'), by: 'damanager1', count: 1, selected: ['E1024'], method: 'Seeded random draw (demo)' },
      { id: rid('SEL', 11), poolId: 'POOL-GND-ENG', at: at(-4, '07:00'), by: 'damanager1', count: 1, selected: ['E1009'], method: 'Seeded random draw (demo)' }
    ],
    tests: [
      { id: rid('DAT', 31), employeeId: 'E1007', reason: 'RANDOM', selectionId: rid('SEL', 9), status: 'VERIFIED_POSITIVE', panel: 'Drug panel (urine)',
        notice: { location: 'Hub clinic, Building 7, room 2', window: 'Within 2 hours of notice' }, notifiedAt: at(-31, '08:00'),
        specimen: { id: 'SPC-88213', ccf: 'CCF-55120', collectedAt: at(-31, '09:10'), collector: 'collector1', seals: 'Intact' },
        lab: { at: at(-30, '10:00'), result: 'NON_NEGATIVE' }, verdict: { at: at(-30, '15:00'), by: 'mro1', outcome: 'POSITIVE', note: 'No legitimate explanation offered.' }, followUps: [{ due: day(12), status: 'PLANNED' }] },
      { id: rid('DAT', 32), employeeId: 'E1032', reason: 'RANDOM', selectionId: rid('SEL', 9), status: 'VERIFIED_NEGATIVE', panel: 'Drug panel (urine)',
        notice: { location: 'Northport clinic', window: 'Within 2 hours of notice' }, notifiedAt: at(-31, '08:00'),
        specimen: { id: 'SPC-88214', ccf: 'CCF-55121', collectedAt: at(-31, '09:40'), collector: 'collector1', seals: 'Intact' },
        lab: { at: at(-30, '10:00'), result: 'NEGATIVE' }, verdict: { at: at(-30, '11:00'), by: 'mro1', outcome: 'NEGATIVE', note: '' }, followUps: [] },
      { id: rid('DAT', 33), employeeId: 'E1024', reason: 'RANDOM', selectionId: rid('SEL', 10), status: 'VERIFIED_NEGATIVE', panel: 'Breath alcohol',
        notice: { location: 'Crew report centre, Hub', window: 'Before sign-on' }, notifiedAt: at(-6, '07:30'),
        specimen: { id: 'BRT-1203', ccf: 'CCF-55188', collectedAt: at(-6, '08:05'), collector: 'collector1', seals: 'n/a (breath)' },
        lab: null, verdict: { at: at(-6, '09:00'), by: 'mro1', outcome: 'NEGATIVE', note: '' }, followUps: [] },
      { id: rid('DAT', 34), employeeId: 'E1009', reason: 'RANDOM', selectionId: rid('SEL', 11), status: 'MRO_REVIEW', panel: 'Drug panel (urine)',
        notice: { location: 'Hub clinic, Building 7, room 2', window: 'Within 2 hours of notice' }, notifiedAt: at(-4, '08:00'),
        specimen: { id: 'SPC-88301', ccf: 'CCF-55230', collectedAt: at(-4, '09:15'), collector: 'collector1', seals: 'Intact' },
        lab: { at: at(-2, '10:00'), result: 'NON_NEGATIVE', analyte: 'Opiates (demo)' }, verdict: null, followUps: [] },
      { id: rid('DAT', 35), employeeId: 'E1004', reason: 'POST_INCIDENT', selectionId: null, status: 'NOTIFIED', panel: 'Breath alcohol + drug panel',
        notice: { location: 'Hub clinic, Building 7, room 2', window: 'Today before ' + '14:00' }, notifiedAt: at(0, '09:00'),
        specimen: null, lab: null, verdict: null, followUps: [] }
    ],
    rng: 20261
  };

  // ---------------------------------------------------------------- travel, advisories, communicable disease
  var travelConsultations = [
    { id: rid('TRV', 11), employeeId: 'E1021', destination: 'MTV', departDate: day(-20), purpose: 'Layover rotation', status: 'COMPLETED', requestedAt: at(-30, '10:00'),
      consultedBy: 'nurse1', consultedAt: at(-27, '11:30'), recommendations: ['Routine vaccines up to date', 'Altitude sickness advice given'], certificateId: rid('DOC', 71) },
    { id: rid('TRV', 12), employeeId: 'E1016', destination: 'PSL', departDate: day(2), purpose: 'New Port Solano rotation', status: 'REQUESTED', requestedAt: at(-2, '13:00'),
      consultedBy: null, consultedAt: null, recommendations: [], certificateId: null }
  ];
  var advisories = [
    { id: rid('ADV', 7), title: 'Dengue season at Port Solano', destination: 'PSL', severity: 'MEDIUM', publishedBy: 'physician1', publishedAt: at(-6, '09:00'),
      body: 'Dengue cases are rising at Port Solano (fictional). Use insect repellent day and night, cover arms and legs, and report fever within 14 days of return.',
      targetRule: 'Crew rostered to PSL in the next 14 days', recipients: ['E1010', 'E1015', 'E1016', 'E1021', 'E1022', 'E1011', 'E1012', 'E1017', 'E1020', 'E1024'],
      acks: { E1015: at(-5, '08:00'), E1021: at(-5, '09:00'), E1022: at(-4, '19:00'), E1017: at(-4, '20:00') } }
  ];
  var cdCases = [
    { id: rid('CDC', 3), employeeId: 'E1002', condition: 'Gastroenteritis (norovirus-like, demo)', status: 'CLOSED', openedBy: 'nurse1', openedAt: at(-21, '07:30'),
      exclusion: { from: day(-21), until: day(-18), basis: 'Food handler – 48 hours symptom free' }, kernelCaseId: cid('CAT', 107), traced: true, publicHealthReported: false }
  ];
  var contacts = [
    { id: rid('CON-T', 1), cdCaseId: rid('CDC', 3), employeeId: 'E1043', exposure: 'Shared kitchen shift', exposureDate: day(-22), notifiedAt: at(-21, '09:00'), status: 'CLEARED', checkIns: [{ date: day(-20), symptoms: false }, { date: day(-19), symptoms: false }], monitorUntil: day(-19) },
    { id: rid('CON-T', 2), cdCaseId: rid('CDC', 3), employeeId: 'E1041', exposure: 'Shared kitchen shift', exposureDate: day(-22), notifiedAt: at(-21, '09:00'), status: 'CLEARED', checkIns: [{ date: day(-20), symptoms: false }], monitorUntil: day(-19) }
  ];

  // ---------------------------------------------------------------- health promotion
  var campaigns = [
    { id: rid('CMP', 3), title: 'Heart Health Check', type: 'SCREENING', status: 'ACTIVE', owner: 'promo1', createdAt: at(-20, '09:00'),
      description: 'Free 20-minute check: blood pressure, cholesterol (finger-prick), BMI and lifestyle advice.',
      units: ['/AIRLINE/GROUND'], start: day(-10), end: day(21), screeningType: 'BIOMETRIC',
      events: [{ id: 'EVT-' + YEAR + '-05', date: day(6), site: 'HUB', location: 'Hub clinic, Building 7', capacity: 30 }, { id: 'EVT-' + YEAR + '-06', date: day(8), site: 'NPT', location: 'Northport crew room', capacity: 10 }],
      invited: ['E1003', 'E1004', 'E1005', 'E1006', 'E1007', 'E1008', 'E1009'], joined: ['E1003', 'E1005', 'E1008', 'E1009'], declined: [] },
    { id: rid('CMP', 4), title: 'Hearing Matters', type: 'AWARENESS', status: 'PLANNED', owner: 'promo1', createdAt: at(-3, '09:00'),
      description: 'Hearing-protection fit testing and awareness sessions in the hangars.',
      units: [], start: day(14), end: day(45), screeningType: 'HEARING', events: [], invited: [], joined: [], declined: [] },
    { id: rid('CMP', 2), title: 'Seasonal flu vaccination', type: 'VACCINATION', status: 'ACTIVE', owner: 'promo1', createdAt: at(-40, '09:00'),
      description: 'Free flu vaccination at the hub clinic and outstation crew rooms.',
      units: ['/AIRLINE/INFLIGHT', '/AIRLINE/FLIGHT_OPS'], start: day(-30), end: day(30), screeningType: null, events: [{ id: 'EVT-' + YEAR + '-02', date: day(-15), site: 'HUB', location: 'Crew report centre', capacity: 60 }],
      invited: ['E1010', 'E1011', 'E1012', 'E1013', 'E1014', 'E1015', 'E1016', 'E1017', 'E1020', 'E1021', 'E1022', 'E1023', 'E1024'], joined: ['E1011', 'E1013', 'E1016', 'E1021', 'E1022', 'E1024'], declined: ['E1015'] }
  ];
  var screeningResults = [
    { id: rid('SCR', 41), campaignId: rid('CMP', 3), employeeId: 'E1005', at: at(-8, '10:00'), by: 'nurse1', measures: { systolic: 124, diastolic: 78, cholesterol: 4.6, bmi: 24.8 }, outcome: 'NORMAL', letterId: rid('DOC', 81), followUpCaseId: null },
    { id: rid('SCR', 42), campaignId: rid('CMP', 3), employeeId: 'E1008', at: at(-8, '10:30'), by: 'nurse1', measures: { systolic: 131, diastolic: 84, cholesterol: 5.2, bmi: 27.1 }, outcome: 'NORMAL', letterId: rid('DOC', 82), followUpCaseId: null }
  ];
  var ergonomicActions = [
    { id: rid('ERG', 1), unit: '/AIRLINE/CORPORATE', area: 'Corporate office – level 2', assessment: 'DSE workstation', rag: 'AMBER', finding: 'Monitors too low on 6 of 14 desks.', action: 'Provide monitor risers', owner: 'manager (Owen Office)', due: day(10), status: 'OPEN', createdBy: 'ergo1' },
    { id: rid('ERG', 2), unit: '/AIRLINE/GROUND', area: 'Baggage hall – reclaim belt 3', assessment: 'Manual handling', rag: 'RED', finding: 'Repeated twisting lifts over 23 kg bags.', action: 'Install lift-assist at belt 3; team-lift rule for heavy tags', owner: 'Gus Ground', due: day(21), status: 'IN_PROGRESS', createdBy: 'ergo1' },
    { id: rid('ERG', 3), unit: '/AIRLINE/CATERING', area: 'Tray assembly line', assessment: 'Task', rag: 'AMBER', finding: 'Repetitive reach above shoulder height.', action: 'Lower the top tray rack by 20 cm', owner: 'Max Manager', due: day(-2), status: 'OPEN', createdBy: 'ergo1' },
    { id: rid('ERG', 4), unit: '/AIRLINE/ENGINEERING', area: 'Hangar 2 stands', assessment: 'Task', rag: 'GREEN', finding: 'Guard rails compliant.', action: 'None – re-assess in 12 months', owner: 'Erica Engineering', due: day(300), status: 'DONE', createdBy: 'ergo1' }
  ];

  // ---------------------------------------------------------------- privacy, consents, documents
  var consents = [
    { id: rid('CON', 60), employeeId: 'E1001', type: 'OH_PROCESSING', scope: 'Occupational health assessment of my self-referral', status: 'GIVEN', at: at(-14, '08:10') },
    { id: rid('CON', 61), employeeId: 'E1031', type: 'PARTNER_TREATMENT', scope: 'Share referral data set with PhysioPlus Clinics', partnerOrg: 'PHYSIOPLUS', status: 'GIVEN', at: at(-19, '10:55') },
    { id: rid('CON', 62), employeeId: 'E1014', type: 'PARTNER_TREATMENT', scope: 'Share referral data set with City Hospital', partnerOrg: 'CITYHOSP', status: 'GIVEN', at: at(-8, '13:50') },
    { id: rid('CON', 63), employeeId: 'E1012', type: 'EAP_REFERRAL', scope: 'Refer me to the EAP partner (name and contact only)', partnerOrg: 'EAP_WELLBEING', status: 'GIVEN', at: at(-15, '11:45') },
    { id: rid('CON', 64), employeeId: 'E1031', type: 'TPA_DISCLOSURE', scope: 'Share claim documents (non-clinical) with the TPA', partnerOrg: 'GULFTPA', status: 'GIVEN', at: at(-12, '08:50') },
    { id: rid('CON', 65), employeeId: 'E1023', type: 'SUPPORT_SERVICE', scope: 'Confidential support from the named mental-health clinician', status: 'GIVEN', at: at(-13, '21:00') },
    { id: rid('CON', 66), employeeId: 'E1004', type: 'OH_PROCESSING', scope: 'Occupational health records (general)', status: 'GIVEN', at: at(-300, '09:00') },
    { id: rid('CON', 67), employeeId: 'E1001', type: 'CAMPAIGN_REMINDERS', scope: 'Health campaign invitations by e-mail', status: 'WITHDRAWN', at: at(-100, '09:00'), withdrawnAt: at(-60, '09:00') }
  ];
  var documents = [
    { id: rid('DOC', 70), employeeId: 'E1001', caseId: cid('CAT', 101), type: 'FITNESS_CERTIFICATE', title: 'Fitness certificate – fit with restrictions', createdAt: at(-6, '11:05'), by: 'physician1', classification: 'STATUS', visibleToEmployee: true, sharedWith: ['manager1'] },
    { id: rid('DOC', 71), employeeId: 'E1021', caseId: null, type: 'TRAVEL_CERTIFICATE', title: 'Travel health certificate – Mount Verity', createdAt: at(-27, '11:40'), by: 'nurse1', classification: 'CLINICAL', visibleToEmployee: true, sharedWith: [] },
    { id: rid('DOC', 72), employeeId: 'E1001', caseId: cid('CAT', 101), type: 'RTW_PLAN', title: 'Adjusted-duties plan', createdAt: at(-5, '16:00'), by: 'nurse1', classification: 'STATUS', visibleToEmployee: true, sharedWith: ['manager1'] },
    { id: rid('DOC', 73), employeeId: 'E1014', caseId: cid('INF', 108), type: 'ADVICE_REPORT', title: 'Management advice report', createdAt: at(-13, '16:00'), by: 'casemgr1', classification: 'STATUS', visibleToEmployee: true, sharedWith: ['hr1', 'manager2'] },
    { id: rid('DOC', 74), employeeId: 'E1001', caseId: cid('CAT', 101), type: 'CLINICAL_NOTE', title: 'Skin assessment', createdAt: at(-14, '10:31'), by: 'nurse1', classification: 'CLINICAL', visibleToEmployee: false, sharedWith: [] },
    { id: rid('DOC', 81), employeeId: 'E1005', caseId: null, type: 'SCREENING_LETTER', title: 'Heart Health Check – your results', createdAt: at(-8, '10:05'), by: 'nurse1', classification: 'CLINICAL', visibleToEmployee: true, sharedWith: [] },
    { id: rid('DOC', 82), employeeId: 'E1008', caseId: null, type: 'SCREENING_LETTER', title: 'Heart Health Check – your results', createdAt: at(-8, '10:35'), by: 'nurse1', classification: 'CLINICAL', visibleToEmployee: true, sharedWith: [] }
  ];
  var disclosures = [
    { id: rid('DSC', 1), at: at(-6, '11:05'), employeeId: 'E1001', recipient: 'Max Manager (line manager)', what: 'Fitness status and restrictions (no clinical detail)', basis: 'Fitness for work – legitimate interest' },
    { id: rid('DSC', 2), at: at(-19, '11:00'), employeeId: 'E1031', recipient: 'PhysioPlus Clinics (fictional)', what: 'Referral data set', basis: 'Consent ' + rid('CON', 61) },
    { id: rid('DSC', 3), at: at(-12, '09:00'), employeeId: 'E1031', recipient: 'Gulf TPA Services (fictional)', what: 'Claim documents (non-clinical)', basis: 'Consent ' + rid('CON', 64) },
    { id: rid('DSC', 4), at: at(-11, '09:00'), employeeId: 'E1023', recipient: 'Felix Fleet (line manager)', what: 'Fitness status only (employee choice)', basis: 'Employee sharing choice' },
    { id: rid('DSC', 5), at: at(-12, '10:00'), employeeId: 'E1014', recipient: 'Hana HR, Isla Inflight', what: 'Management advice report (released by employee)', basis: 'Employee release' }
  ];

  // ---------------------------------------------------------------- notifications and audit
  function nt(n, to, days, hhmm, title, body, link, read) {
    return { id: rid('NTF', n), to: to, at: at(days, hhmm), title: title, body: body, link: link || null, read: !!read, kind: 'INFO' };
  }
  var notifications = [
    nt(1, 'employee1', -5, '16:01', 'Your adjusted-duties plan is ready', 'Please read and acknowledge your return-to-work plan.', { app: 'employee', route: 'rtw' }),
    nt(2, 'employee1', -6, '11:06', 'Fitness decision recorded', 'Occupational Health recorded a fitness decision on your case.', { app: 'employee', route: 'fitness' }, true),
    nt(3, 'crew1', -6, '09:01', 'Health advisory: Port Solano', 'A health advisory applies to your upcoming rotation. Please read and acknowledge it.', { app: 'employee', route: 'travel' }),
    nt(4, 'crew1', -4, '12:01', 'Appointment booked', 'AME review booked at the hub clinic.', { app: 'employee', route: 'appointments' }),
    nt(5, 'ramp1', 0, '09:00', 'Workplace testing notice', 'You are required to attend a workplace test. See where and when.', { app: 'employee', route: 'testing' }),
    nt(6, 'manager1', -5, '16:01', 'Adjusted-duties plan to acknowledge', 'An adjusted-duties plan for a team member needs your acknowledgement.', { app: 'manager', route: 'rtw' }),
    nt(7, 'nurse1', 0, '06:41', 'New case in intake queue', 'A new EHS incident case is waiting for triage.', { app: 'oh', route: 'queues' }),
    nt(8, 'mro1', -2, '10:01', 'Result awaiting MRO review', 'A laboratory result is waiting in the MRO queue.', { app: 'oh', route: 'testing/mro' }),
    nt(9, 'physio1', -19, '11:01', 'New referral', 'A new referral has been sent to your organisation.', { app: 'partner', route: 'referrals' }, true),
    nt(10, 'adjuster1', -9, '09:31', 'New message on a claim', 'The claims coordinator replied on a claim.', { app: 'partner', route: 'claims' }),
    nt(11, 'eap1', -15, '12:01', 'New EAP referral', 'A new EAP referral has been sent to your organisation.', { app: 'partner', route: 'eap' }, true),
    nt(12, 'hr1', -9, '11:01', 'Duty status change', 'A team member\'s duty status changed to Return pending.', { app: 'manager', route: 'duty-status' }),
    nt(13, 'office1', -2, '10:00', 'Heart Health Check', 'Health campaigns are running at the hub this month.', { app: 'employee', route: 'campaigns' }, true)
  ];
  var audit = [
    { seq: 1, at: at(-14, '10:30'), actor: 'nurse1', action: 'TRIAGE', entity: 'case', entityId: cid('CAT', 101), outcome: 'ALLOWED', detail: 'SEMI_URGENT' },
    { seq: 2, at: at(-12, '11:00'), actor: 'nurse1', action: 'READ_RESTRICTED', entity: 'case', entityId: cid('FLT', 110), outcome: 'DENIED', detail: 'Not the named clinician' },
    { seq: 3, at: at(-12, '11:00'), actor: 'nurse1', action: 'SUPPORT_PLACEHOLDER_READ', entity: 'employee', entityId: 'E1023', outcome: 'ALLOWED', detail: '1 restricted case' },
    { seq: 4, at: at(-6, '11:00'), actor: 'physician1', action: 'FITNESS_DECISION', entity: 'case', entityId: cid('CAT', 101), outcome: 'ALLOWED', detail: 'FIT_WITH_RESTRICTIONS' },
    { seq: 5, at: at(-2, '10:00'), actor: 'damanager1', action: 'READ_MRO_QUEUE', entity: 'test', entityId: rid('DAT', 34), outcome: 'DENIED', detail: 'Programme manager never sees results' }
  ];

  var counters = { case: 200, RTW: 20, ABS: 300, TRG: 40, EI: 20, MAR: 10, TP: 20, RFL: 30, CLM: 50, SUP: 30, DAT: 40, SEL: 20, TRV: 20, ADV: 10, CDC: 10, 'CON-T': 10, CMP: 10, SCR: 50, ERG: 10, CON: 70, DOC: 90, DSC: 10, NTF: 50, APT: 60, SRV: 200, PS: 0, BG: 0 };

  root.WZ_DEMO_SEED = {
    meta: {
      product: 'WellnessZ',
      seedId: 'wz-demo-seed',
      version: '1.0.0',
      schema: 1,
      generatedFor: day(0),
      organisation: 'WellnessZ Air Group (fictional)',
      notice: 'Synthetic demo data. All people, organisations and clinical details are fictional.'
    },
    config: config,
    orgUnits: orgUnits, sites: sites, safetyRoles: safetyRoles, jobCodes: jobCodes,
    employees: employees, users: users, partnerOrgs: partnerOrgs, serviceCatalogue: serviceCatalogue,
    restrictionCatalogue: restrictionCatalogue, triageProtocols: triageProtocols,
    cases: cases, rtwPlans: rtwPlans,
    absences: absences, absenceTriggers: absenceTriggers, earlyInterventions: earlyInterventions, adviceRequests: adviceRequests,
    surveillanceProgrammes: surveillanceProgrammes, surveillance: surv,
    clinicSlots: clinicSlots, appointments: appointments,
    treatmentPlans: treatmentPlans, referrals: referrals, claims: claims,
    supportCases: supportCases, peerRequests: peerRequests,
    testing: testing,
    roster: roster, destinations: destinations, travelConsultations: travelConsultations, advisories: advisories,
    cdCases: cdCases, contacts: contacts,
    campaigns: campaigns, screeningResults: screeningResults, ergonomicActions: ergonomicActions,
    consents: consents, documents: documents, disclosures: disclosures,
    notifications: notifications, audit: audit, breakGlass: [],
    counters: counters
  };
})(typeof window !== 'undefined' ? window : globalThis);
