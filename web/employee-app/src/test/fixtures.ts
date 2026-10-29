import type { BodyMapRegion, Template } from '../api/types';

export const REGIONS: BodyMapRegion[] = [
  { code: 'HEAD', label: 'Head', side: 'FRONT' },
  { code: 'HAND_R', label: 'Right hand', side: 'FRONT' },
  { code: 'HAND_L', label: 'Left hand', side: 'FRONT' },
  { code: 'KNEE_R', label: 'Right knee', side: 'FRONT' },
  { code: 'LOWER_BACK', label: 'Lower back', side: 'BACK' },
  { code: 'HAND_R_BACK', label: 'Back of right hand', side: 'BACK' },
  { code: 'TONGUE', label: 'Tongue', side: 'FRONT' }, // unknown to the drawing → list only
];

export const CATERING: Template = {
  code: 'CATERING',
  title: 'Catering – cuts, burns, food-handler symptoms',
  audience: 'EMPLOYEE',
  departments: ['CATERING'],
  routingQueue: 'FOOD_SAFETY',
  fields: [
    { key: 'injuryType', label: 'What happened?', type: 'SELECT', required: true, options: [{ value: 'CUT', label: 'Cut' }, { value: 'BURN', label: 'Burn' }] },
    { key: 'where', label: 'Where were you working?', type: 'TEXT', required: true, maxLength: 20 },
    { key: 'details', label: 'Anything else?', type: 'TEXTAREA', required: false, maxLength: 500 },
    { key: 'handledFood', label: 'Did you handle food since?', type: 'BOOLEAN', required: true },
    { key: 'gi', label: 'Stomach symptoms', type: 'MULTISELECT', required: true, options: [{ value: 'VOMITING', label: 'Vomiting' }, { value: 'DIARRHOEA', label: 'Diarrhoea' }] },
    { key: 'onset', label: 'When did it start?', type: 'DATE', required: true },
    { key: 'body', label: 'Where does it hurt?', type: 'BODY_MAP', required: true },
    { key: 'sym', label: 'Symptoms', type: 'SYMPTOMS', required: false },
  ],
  symptomOptions: [
    { value: 'PAIN', label: 'Pain' },
    { value: 'SWELLING', label: 'Swelling' },
  ],
};

export const CORPORATE: Template = {
  code: 'CORPORATE',
  title: 'Office – ergonomic, psychosocial',
  audience: 'EMPLOYEE',
  departments: ['CORP', 'FINANCE'],
  fields: [
    { key: 'where', label: 'Where do you work?', type: 'TEXT', required: false },
    { key: 'workstation', label: 'Workstation issue?', type: 'BOOLEAN', required: false },
  ],
};
