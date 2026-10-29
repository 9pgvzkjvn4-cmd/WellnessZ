import type { SafetyCategory } from '../api/types';

/** Contract §5.2 checklists. The last item of each list is the decision; the others are findings (yes/no). */
export const CHECKLISTS = {
  FOOD_HANDLER: { title: 'Food handler exclusion', items: ['GI_SYMPTOMS', 'SKIN_LESIONS', 'INFECTIONS'], decision: 'EXCLUSION_DECISION' },
  DRIVING: { title: 'Driving fitness (airside / forklift)', items: ['VISION', 'SEIZURE_HISTORY', 'SEDATING_MEDICATION'], decision: 'FITNESS_TO_DRIVE' },
  HEIGHT: { title: 'Working at height', items: ['VERTIGO', 'BALANCE'], decision: 'FITNESS_FOR_HEIGHT' },
} as const;
export type ChecklistCode = keyof typeof CHECKLISTS;
export const CHECKLIST_OUTCOMES = ['CLEAR', 'RESTRICTED', 'EXCLUDED'] as const;
export type ChecklistOutcome = (typeof CHECKLIST_OUTCOMES)[number];

export function checklistFor(category: SafetyCategory | null | undefined): ChecklistCode | undefined {
  switch (category) {
    case 'FOOD_HANDLER': return 'FOOD_HANDLER';
    case 'AIRSIDE_DRIVER':
    case 'FORKLIFT': return 'DRIVING';
    case 'WORKING_AT_HEIGHT': return 'HEIGHT';
    default: return undefined;
  }
}

