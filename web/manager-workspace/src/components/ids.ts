import { idPart } from './format';

/** Element ids of the increment-2 per-item forms (shared by screens, focus handling and tests). */
export const decisionIds = (proposalId: string) => {
  const p = `dp-${idPart(proposalId)}`;
  return { reasonCode: `${p}-reasonCode`, note: `${p}-note` };
};
export const ackCommentId = (planId: string) => `rtw-${idPart(planId)}-comment`;
export const restrictionCheckboxId = (code: string) => `jo-restriction-${idPart(code)}`;
export const appealIds = (assessmentId: string) => {
  const p = `ihr-${idPart(assessmentId)}`;
  return { grounds: `${p}-grounds`, secondOpinionBy: `${p}-secondOpinionBy` };
};

/* increment 3 */
export const hrStageIds = (caseId: string) => {
  const p = `lta-${idPart(caseId)}`;
  return { stage: `${p}-stage`, note: `${p}-note` };
};
export const adviceQuestionId = (code: string) => `adv-q-${idPart(code)}`;

/* increment 5 – for-cause request form (EmployeeSearch with idPrefix "fc" renders `fc-employeeId`) */
export const FOR_CAUSE_IDS = {
  employeeId: 'fc-employeeId',
  observedAt: 'fc-observedAt',
  location: 'fc-location',
  observations: 'fc-observations',
  description: 'fc-description',
  secondObserverName: 'fc-secondObserverName',
} as const;
