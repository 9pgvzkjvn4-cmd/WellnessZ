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
