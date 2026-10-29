import { humanise } from '../components/format';
import { baseLang } from './strings';

/**
 * UI strings of the increment-2 portal screens (My documents, My return to work, Job offers, Privacy, crew declaration
 * outcome, home tiles and navigation) in English, Arabic and French. Unknown languages fall back to English.
 * Server-provided content (document titles, duties, next steps …) is shown as sent.
 */
export interface PortalStrings {
  language: string;
  nav: {
    home: string;
    refer: string;
    advice: string;
    documents: string;
    rtw: string;
    offers: string;
    declaration: string;
    privacy: string;
    consents: string;
    profile: string;
  };
  loading: string;
  tilesTitle: string;
  tileDocuments: string;
  tileRtw: string;
  tileOffers: string;
  tilePrivacy: string;
  tileActions: (n: number) => string;

  /* My documents */
  docsTitle: string;
  docsIntro: string;
  docsEmpty: string;
  docsEmptyHint: string;
  docTypes: Record<string, string>;
  docNumber: string;
  docIssued: string;
  docVersion: (v: number) => string;
  docOpen: string;
  docDownload: string;
  docOpening: string;
  docOpenFailed: string;
  docAcknowledge: string;
  docAcknowledging: string;
  docAcknowledgeHint: string;
  docAcknowledgedOn: (date: string) => string;
  docAcknowledgedOk: string;
  docBadgeNew: string;
  docBadgeAction: string;
  docBadgeAcknowledged: string;
  docBadgeSuperseded: string;
  docSeeRtw: string;
  docSeeOffers: string;
  docsOpensNewTab: string;

  /* Return to work */
  rtwTitle: string;
  rtwIntro: string;
  rtwEmpty: string;
  rtwEmptyHint: string;
  rtwStatus: Record<string, string>;
  rtwStart: string;
  rtwGoal: string;
  rtwPhases: string;
  rtwWeeks: (from: number, to: number) => string;
  rtwHoursPerDay: (h: number) => string;
  rtwDuties: string;
  rtwRestrictions: string;
  rtwNoRestrictions: string;
  rtwReviews: string;
  rtwReviewDone: (date: string) => string;
  rtwNextReview: string;
  rtwContact: string;
  rtwContactFallback: string;
  rtwComment: string;
  rtwCommentHint: string;
  rtwAgree: string;
  rtwAgreeing: string;
  rtwDisagreeHint: string;
  rtwAgreedOn: (date: string) => string;
  rtwAgreedOk: string;
  rtwYourComment: string;
  rtwManagerAck: (date: string) => string;

  /* Job offers */
  offersTitle: string;
  offersIntro: string;
  offersEmpty: string;
  offersEmptyHint: string;
  offerStatus: Record<string, string>;
  offerRole: string;
  offerDuties: string;
  offerLocation: string;
  offerShift: string;
  offerHours: (h: number) => string;
  offerHoursLabel: string;
  offerPay: string;
  payBasis: Record<string, string>;
  offerRestrictions: string;
  offerRestrictionsHint: string;
  offerStart: string;
  offerEnd: string;
  offerOpenEnded: string;
  offerRespondBy: string;
  offerRespondByPassed: string;
  offerContact: string;
  offerComment: string;
  offerCommentHint: string;
  offerAccept: string;
  offerDecline: string;
  offerConfirmAcceptTitle: string;
  offerConfirmDeclineTitle: string;
  offerConfirmAccept: string;
  offerConfirmDecline: string;
  offerConfirmYesAccept: string;
  offerConfirmYesDecline: string;
  cancel: string;
  sending: string;
  offerAcceptedOk: string;
  offerDeclinedOk: string;
  offerRespondedOn: (date: string) => string;

  /* Privacy */
  privacyTitle: string;
  privacyIntro: string;
  disclosuresTitle: string;
  disclosuresEmpty: string;
  disclosureDate: string;
  disclosureRecipient: string;
  disclosureDocument: string;
  disclosurePurpose: string;
  disclosureStatus: string;
  disclosureStatusLabels: Record<string, string>;
  recipientTypes: Record<string, string>;
  consentsTitle: string;
  consentsIntro: string;
  consentsEmpty: string;
  consentGiven: (date: string) => string;
  consentManage: string;
  withdraw: string;
  withdrawTitle: (purpose: string) => string;
  withdrawExplain: string;
  withdrawPartner: string;
  withdrawLaw: string;
  withdrawConfirm: string;
  withdrawnOk: string;
  purposes: Record<string, string>;

  /* Crew declaration outcome (E05-BL-010) */
  declTitle: string;
  declThanks: (ref?: string) => string;
  declNoOperate: string;
  declNext: string;
  declDefaultSteps: string[];
  declSchedulingOnly: string;
  declAckDue: (date: string) => string;
  declAmeContact: string;
  declCall: string;
  declEmail: string;
  backHome: string;
}

const en: PortalStrings = {
  language: 'Language',
  nav: {
    home: 'Home',
    refer: 'Contact OH',
    advice: 'My advice',
    documents: 'My documents',
    rtw: 'Return to work',
    offers: 'Job offers',
    declaration: 'Crew declaration',
    privacy: 'Privacy',
    consents: 'My consents',
    profile: 'Profile',
  },
  loading: 'Loading…',
  tilesTitle: 'My documents and plans',
  tileDocuments: 'My documents',
  tileRtw: 'My return to work',
  tileOffers: 'Job offers',
  tilePrivacy: 'Who received my information',
  tileActions: (n) => (n === 1 ? '1 item needs your attention' : `${n} items need your attention`),

  docsTitle: 'My documents',
  docsIntro:
    'Certificates, notes and letters Occupational Health has shared with you. They never show a diagnosis. Please acknowledge certificates and restriction notes once you have read them.',
  docsEmpty: 'No documents yet.',
  docsEmptyHint: 'When Occupational Health shares a certificate or letter with you, it will appear here.',
  docTypes: {
    FITNESS_CERTIFICATE: 'Fitness certificate',
    RESTRICTION_NOTE: 'Restriction note',
    REFERRAL_LETTER: 'Referral letter',
    CABIN_CREW_MEDICAL_REPORT: 'Cabin crew medical report',
    CREW_MEDICAL_REPORT: 'Crew medical report',
    FOOD_HANDLER_CLEARANCE: 'Food-handler clearance',
    RESULT_LETTER: 'Result letter',
    RTW_PLAN: 'Return-to-work plan',
    JOB_OFFER: 'Job offer',
    IHR_DECISION_LETTER: 'Decision letter',
    EXTERNAL_REPORT: 'External report',
    OTHER: 'Document',
  },
  docNumber: 'Document number',
  docIssued: 'Issued',
  docVersion: (v) => `Version ${v}`,
  docOpen: 'Open PDF',
  docDownload: 'Download',
  docOpening: 'Opening…',
  docOpenFailed: 'The document could not be opened. Please try again.',
  docAcknowledge: 'I have read this',
  docAcknowledging: 'Saving…',
  docAcknowledgeHint: 'Confirms that you received and read this document. It does not mean you agree with it.',
  docAcknowledgedOn: (d) => `Acknowledged ${d}`,
  docAcknowledgedOk: 'Thank you – your acknowledgement has been recorded.',
  docBadgeNew: 'New',
  docBadgeAction: 'Please acknowledge',
  docBadgeAcknowledged: 'Acknowledged',
  docBadgeSuperseded: 'Replaced by a newer version',
  docSeeRtw: 'Review and agree in “My return to work”',
  docSeeOffers: 'Reply in “Job offers”',
  docsOpensNewTab: '(opens in a new tab)',

  rtwTitle: 'My return to work',
  rtwIntro:
    'Your return-to-work plan describes your working hours, duties and work restrictions step by step. Your manager sees the same plan – it contains no medical details.',
  rtwEmpty: 'You have no active return-to-work plan.',
  rtwEmptyHint: 'If Occupational Health agrees a phased return with you, the plan will appear here for you to agree.',
  rtwStatus: { DRAFT: 'Draft', ACTIVE: 'Active', COMPLETED: 'Completed', CANCELLED: 'Cancelled' },
  rtwStart: 'Starts',
  rtwGoal: 'Goal',
  rtwPhases: 'Phases',
  rtwWeeks: (f, t) => (f === t ? `Week ${f}` : `Weeks ${f}–${t}`),
  rtwHoursPerDay: (h) => `${h} hours per day`,
  rtwDuties: 'Duties',
  rtwRestrictions: 'Work restrictions',
  rtwNoRestrictions: 'No restrictions in this phase',
  rtwReviews: 'Review dates',
  rtwReviewDone: (d) => `reviewed ${d}`,
  rtwNextReview: 'Next review',
  rtwContact: 'Your contact',
  rtwContactFallback: 'Questions? Contact Occupational Health.',
  rtwComment: 'Comment (optional)',
  rtwCommentHint: 'For example a question about your hours. Please do not include medical details.',
  rtwAgree: 'I agree to this plan',
  rtwAgreeing: 'Saving…',
  rtwDisagreeHint: 'If you do not agree with the plan, do not confirm it – contact Occupational Health to discuss it.',
  rtwAgreedOn: (d) => `You agreed to this plan on ${d}.`,
  rtwAgreedOk: 'Thank you – your agreement has been recorded.',
  rtwYourComment: 'Your comment',
  rtwManagerAck: (d) => `Your manager acknowledged the plan on ${d}.`,

  offersTitle: 'Job offers',
  offersIntro:
    'Alternative or modified duty offered by HR while you have work restrictions. Your restrictions are respected in every offer.',
  offersEmpty: 'You have no job offers.',
  offersEmptyHint: 'If HR offers you alternative duties, the offer will appear here for you to accept or decline.',
  offerStatus: { ISSUED: 'Awaiting your reply', ACCEPTED: 'Accepted', DECLINED: 'Declined', WITHDRAWN: 'Withdrawn', EXPIRED: 'Expired' },
  offerRole: 'Role',
  offerDuties: 'Duties',
  offerLocation: 'Location',
  offerShift: 'Shift',
  offerHours: (h) => `${h} hours per week`,
  offerHoursLabel: 'Hours',
  offerPay: 'Pay',
  payBasis: { SAME_PAY: 'Same pay', PROTECTED_PAY: 'Pay protected', ADJUSTED_PAY: 'Adjusted pay' },
  offerRestrictions: 'Restrictions respected',
  offerRestrictionsHint: 'The role has been checked against your current work restrictions.',
  offerStart: 'Start',
  offerEnd: 'End',
  offerOpenEnded: 'Until reviewed',
  offerRespondBy: 'Please reply by',
  offerRespondByPassed: 'The reply date has passed. You can still reply – HR will decide whether the offer is still available.',
  offerContact: 'Contact',
  offerComment: 'Comment (optional)',
  offerCommentHint: 'Visible to HR. Please do not include medical details.',
  offerAccept: 'Accept offer',
  offerDecline: 'Decline offer',
  offerConfirmAcceptTitle: 'Accept this offer?',
  offerConfirmDeclineTitle: 'Decline this offer?',
  offerConfirmAccept: 'Your acceptance is recorded electronically and HR is informed. This replaces a signature on paper.',
  offerConfirmDecline: 'HR is informed that you declined. HR or Occupational Health may contact you to discuss other options.',
  offerConfirmYesAccept: 'Yes, accept',
  offerConfirmYesDecline: 'Yes, decline',
  cancel: 'Cancel',
  sending: 'Sending…',
  offerAcceptedOk: 'You accepted the offer. HR has been informed.',
  offerDeclinedOk: 'You declined the offer. HR has been informed.',
  offerRespondedOn: (d) => `You replied on ${d}.`,

  privacyTitle: 'Who received my information',
  privacyIntro:
    'Every time Occupational Health shares one of your documents outside OH – for example with your manager, HR or a partner provider – it is recorded here.',
  disclosuresTitle: 'Disclosure log',
  disclosuresEmpty: 'None of your documents has been shared outside Occupational Health.',
  disclosureDate: 'Date',
  disclosureRecipient: 'Recipient',
  disclosureDocument: 'Document',
  disclosurePurpose: 'Purpose',
  disclosureStatus: 'Access',
  disclosureStatusLabels: { ACTIVE: 'Access active', REVOKED: 'Access revoked' },
  recipientTypes: { EMPLOYEE: 'You', MANAGER: 'Manager', HR: 'HR', PARTNER: 'Partner provider' },
  consentsTitle: 'My consents',
  consentsIntro: 'Sharing with partner providers is based on your consent. You can withdraw it at any time.',
  consentsEmpty: 'You have no active consents.',
  consentGiven: (d) => `Given ${d}`,
  consentManage: 'Give or review consents',
  withdraw: 'Withdraw consent',
  withdrawTitle: (p) => `Withdraw consent for “${p}”?`,
  withdrawExplain: 'What happens when you withdraw:',
  withdrawPartner:
    'Partner providers lose access immediately to every document shared under this consent. This is recorded in your disclosure log.',
  withdrawLaw:
    'It does not affect information shared before, or sharing required by law – for example your fitness status (without medical details) to your manager.',
  withdrawConfirm: 'Yes, withdraw',
  withdrawnOk: 'Consent withdrawn. Partner access has been stopped.',
  purposes: {
    TREATMENT_SHARING_PARTNER: 'Share treatment information with a partner provider',
    EAP_REFERRAL: 'Referral to the Employee Assistance Programme (EAP)',
    INSURER_SHARING: 'Share relevant information with the insurer',
    WEARABLE_DATA: 'Use data from my wearable device',
    HEALTH_CAMPAIGNS: 'Invitations to health campaigns',
    RESEARCH_DEIDENTIFIED: 'Use of de-identified data for research',
    FITNESS_STATUS: 'Fitness for work status',
    LEGAL_OBLIGATION: 'Required by law',
  },

  declTitle: 'Declaration received',
  declThanks: (ref) =>
    `Thank you for declaring a decrease in fitness${ref ? ` – your case reference is ${ref}` : ''}.`,
  declNoOperate: 'Do not operate until you have been assessed.',
  declNext: 'What happens next',
  declDefaultSteps: [
    'An aeromedical examiner (AME) will contact you.',
    'Your status will be updated after the assessment. You can follow it on the home screen.',
  ],
  declSchedulingOnly: 'Crew scheduling only sees “unfit – pending”. They do not see your reason or anything you wrote.',
  declAckDue: (d) => `Occupational Health will acknowledge your declaration by ${d}.`,
  declAmeContact: 'Your aeromedical contact',
  declCall: 'Call',
  declEmail: 'Email',
  backHome: 'Back to home',
};

const fr: PortalStrings = {
  language: 'Langue',
  nav: {
    home: 'Accueil',
    refer: 'Contacter la ST',
    advice: 'Mes conseils',
    documents: 'Mes documents',
    rtw: 'Reprise du travail',
    offers: 'Offres de poste',
    declaration: 'Déclaration équipage',
    privacy: 'Confidentialité',
    consents: 'Mes consentements',
    profile: 'Profil',
  },
  loading: 'Chargement…',
  tilesTitle: 'Mes documents et plans',
  tileDocuments: 'Mes documents',
  tileRtw: 'Ma reprise du travail',
  tileOffers: 'Offres de poste',
  tilePrivacy: 'Qui a reçu mes informations',
  tileActions: (n) => (n === 1 ? '1 élément demande votre attention' : `${n} éléments demandent votre attention`),

  docsTitle: 'Mes documents',
  docsIntro:
    'Certificats, notes et courriers partagés avec vous par la Santé au travail. Ils ne mentionnent jamais de diagnostic. Merci de confirmer la lecture des certificats et notes de restriction.',
  docsEmpty: 'Aucun document pour le moment.',
  docsEmptyHint: 'Lorsque la Santé au travail partage un certificat ou un courrier avec vous, il apparaîtra ici.',
  docTypes: {
    FITNESS_CERTIFICATE: 'Certificat d’aptitude',
    RESTRICTION_NOTE: 'Note de restriction',
    REFERRAL_LETTER: 'Lettre d’orientation',
    CABIN_CREW_MEDICAL_REPORT: 'Rapport médical PNC',
    CREW_MEDICAL_REPORT: 'Rapport médical équipage',
    FOOD_HANDLER_CLEARANCE: 'Autorisation manipulation des aliments',
    RESULT_LETTER: 'Courrier de résultats',
    RTW_PLAN: 'Plan de reprise du travail',
    JOB_OFFER: 'Offre de poste',
    IHR_DECISION_LETTER: 'Lettre de décision',
    EXTERNAL_REPORT: 'Rapport externe',
    OTHER: 'Document',
  },
  docNumber: 'Numéro du document',
  docIssued: 'Émis le',
  docVersion: (v) => `Version ${v}`,
  docOpen: 'Ouvrir le PDF',
  docDownload: 'Télécharger',
  docOpening: 'Ouverture…',
  docOpenFailed: 'Le document n’a pas pu être ouvert. Veuillez réessayer.',
  docAcknowledge: 'J’ai lu ce document',
  docAcknowledging: 'Enregistrement…',
  docAcknowledgeHint: 'Confirme que vous avez reçu et lu ce document. Cela ne signifie pas que vous êtes d’accord.',
  docAcknowledgedOn: (d) => `Lu et confirmé le ${d}`,
  docAcknowledgedOk: 'Merci – votre confirmation a été enregistrée.',
  docBadgeNew: 'Nouveau',
  docBadgeAction: 'À confirmer',
  docBadgeAcknowledged: 'Confirmé',
  docBadgeSuperseded: 'Remplacé par une version plus récente',
  docSeeRtw: 'Consulter et accepter dans « Reprise du travail »',
  docSeeOffers: 'Répondre dans « Offres de poste »',
  docsOpensNewTab: '(s’ouvre dans un nouvel onglet)',

  rtwTitle: 'Ma reprise du travail',
  rtwIntro:
    'Votre plan de reprise décrit étape par étape vos horaires, vos tâches et vos restrictions. Votre responsable voit le même plan – il ne contient aucune information médicale.',
  rtwEmpty: 'Vous n’avez aucun plan de reprise actif.',
  rtwEmptyHint: 'Si la Santé au travail convient avec vous d’une reprise progressive, le plan apparaîtra ici pour accord.',
  rtwStatus: { DRAFT: 'Brouillon', ACTIVE: 'Actif', COMPLETED: 'Terminé', CANCELLED: 'Annulé' },
  rtwStart: 'Début',
  rtwGoal: 'Objectif',
  rtwPhases: 'Phases',
  rtwWeeks: (f, t) => (f === t ? `Semaine ${f}` : `Semaines ${f} à ${t}`),
  rtwHoursPerDay: (h) => `${h} heures par jour`,
  rtwDuties: 'Tâches',
  rtwRestrictions: 'Restrictions de travail',
  rtwNoRestrictions: 'Aucune restriction pendant cette phase',
  rtwReviews: 'Dates de suivi',
  rtwReviewDone: (d) => `fait le ${d}`,
  rtwNextReview: 'Prochain suivi',
  rtwContact: 'Votre contact',
  rtwContactFallback: 'Des questions ? Contactez la Santé au travail.',
  rtwComment: 'Commentaire (facultatif)',
  rtwCommentHint: 'Par exemple une question sur vos horaires. N’indiquez pas d’informations médicales.',
  rtwAgree: 'J’accepte ce plan',
  rtwAgreeing: 'Enregistrement…',
  rtwDisagreeHint: 'Si vous n’êtes pas d’accord, ne confirmez pas – contactez la Santé au travail pour en discuter.',
  rtwAgreedOn: (d) => `Vous avez accepté ce plan le ${d}.`,
  rtwAgreedOk: 'Merci – votre accord a été enregistré.',
  rtwYourComment: 'Votre commentaire',
  rtwManagerAck: (d) => `Votre responsable a pris connaissance du plan le ${d}.`,

  offersTitle: 'Offres de poste',
  offersIntro:
    'Poste alternatif ou aménagé proposé par les RH pendant vos restrictions de travail. Chaque offre respecte vos restrictions.',
  offersEmpty: 'Vous n’avez aucune offre de poste.',
  offersEmptyHint: 'Si les RH vous proposent un poste alternatif, l’offre apparaîtra ici pour acceptation ou refus.',
  offerStatus: { ISSUED: 'En attente de votre réponse', ACCEPTED: 'Acceptée', DECLINED: 'Refusée', WITHDRAWN: 'Retirée', EXPIRED: 'Expirée' },
  offerRole: 'Poste',
  offerDuties: 'Tâches',
  offerLocation: 'Lieu',
  offerShift: 'Horaire',
  offerHours: (h) => `${h} heures par semaine`,
  offerHoursLabel: 'Heures',
  offerPay: 'Rémunération',
  payBasis: { SAME_PAY: 'Rémunération inchangée', PROTECTED_PAY: 'Rémunération maintenue', ADJUSTED_PAY: 'Rémunération ajustée' },
  offerRestrictions: 'Restrictions respectées',
  offerRestrictionsHint: 'Le poste a été vérifié par rapport à vos restrictions actuelles.',
  offerStart: 'Début',
  offerEnd: 'Fin',
  offerOpenEnded: 'Jusqu’à réévaluation',
  offerRespondBy: 'Merci de répondre avant le',
  offerRespondByPassed: 'La date de réponse est dépassée. Vous pouvez encore répondre – les RH décideront si l’offre est toujours valable.',
  offerContact: 'Contact',
  offerComment: 'Commentaire (facultatif)',
  offerCommentHint: 'Visible par les RH. N’indiquez pas d’informations médicales.',
  offerAccept: 'Accepter l’offre',
  offerDecline: 'Refuser l’offre',
  offerConfirmAcceptTitle: 'Accepter cette offre ?',
  offerConfirmDeclineTitle: 'Refuser cette offre ?',
  offerConfirmAccept: 'Votre acceptation est enregistrée électroniquement et les RH sont informées. Elle remplace une signature papier.',
  offerConfirmDecline: 'Les RH sont informées de votre refus. Les RH ou la Santé au travail pourront vous contacter pour d’autres options.',
  offerConfirmYesAccept: 'Oui, accepter',
  offerConfirmYesDecline: 'Oui, refuser',
  cancel: 'Annuler',
  sending: 'Envoi…',
  offerAcceptedOk: 'Vous avez accepté l’offre. Les RH ont été informées.',
  offerDeclinedOk: 'Vous avez refusé l’offre. Les RH ont été informées.',
  offerRespondedOn: (d) => `Vous avez répondu le ${d}.`,

  privacyTitle: 'Qui a reçu mes informations',
  privacyIntro:
    'Chaque fois que la Santé au travail partage l’un de vos documents en dehors du service – par exemple avec votre responsable, les RH ou un prestataire partenaire – c’est enregistré ici.',
  disclosuresTitle: 'Journal des communications',
  disclosuresEmpty: 'Aucun de vos documents n’a été partagé en dehors de la Santé au travail.',
  disclosureDate: 'Date',
  disclosureRecipient: 'Destinataire',
  disclosureDocument: 'Document',
  disclosurePurpose: 'Finalité',
  disclosureStatus: 'Accès',
  disclosureStatusLabels: { ACTIVE: 'Accès actif', REVOKED: 'Accès révoqué' },
  recipientTypes: { EMPLOYEE: 'Vous', MANAGER: 'Responsable', HR: 'RH', PARTNER: 'Prestataire partenaire' },
  consentsTitle: 'Mes consentements',
  consentsIntro: 'Le partage avec des prestataires partenaires repose sur votre consentement. Vous pouvez le retirer à tout moment.',
  consentsEmpty: 'Vous n’avez aucun consentement actif.',
  consentGiven: (d) => `Donné le ${d}`,
  consentManage: 'Donner ou consulter mes consentements',
  withdraw: 'Retirer le consentement',
  withdrawTitle: (p) => `Retirer votre consentement pour « ${p} » ?`,
  withdrawExplain: 'Ce qui se passe si vous retirez votre consentement :',
  withdrawPartner:
    'Les prestataires partenaires perdent immédiatement l’accès à tous les documents partagés sur la base de ce consentement. Cela est enregistré dans votre journal.',
  withdrawLaw:
    'Cela ne concerne pas les informations déjà partagées ni les partages imposés par la loi – par exemple votre aptitude (sans détail médical) à votre responsable.',
  withdrawConfirm: 'Oui, retirer',
  withdrawnOk: 'Consentement retiré. L’accès des partenaires a été arrêté.',
  purposes: {
    TREATMENT_SHARING_PARTNER: 'Partager les informations de soins avec un prestataire partenaire',
    EAP_REFERRAL: 'Orientation vers le programme d’aide aux employés (PAE)',
    INSURER_SHARING: 'Partager les informations pertinentes avec l’assureur',
    WEARABLE_DATA: 'Utiliser les données de mon objet connecté',
    HEALTH_CAMPAIGNS: 'Invitations aux campagnes de santé',
    RESEARCH_DEIDENTIFIED: 'Utilisation de données anonymisées pour la recherche',
    FITNESS_STATUS: 'Statut d’aptitude au travail',
    LEGAL_OBLIGATION: 'Obligation légale',
  },

  declTitle: 'Déclaration reçue',
  declThanks: (ref) =>
    `Merci d’avoir déclaré une baisse d’aptitude${ref ? ` – votre référence de dossier est ${ref}` : ''}.`,
  declNoOperate: 'N’assurez aucun vol avant d’avoir été évalué(e).',
  declNext: 'Prochaines étapes',
  declDefaultSteps: [
    'Un examinateur aéromédical (AME) vous contactera.',
    'Votre statut sera mis à jour après l’évaluation. Vous pouvez le suivre sur l’écran d’accueil.',
  ],
  declSchedulingOnly: 'La planification des équipages voit uniquement « inapte – en attente ». Elle ne voit ni le motif ni ce que vous avez écrit.',
  declAckDue: (d) => `La Santé au travail accusera réception de votre déclaration avant le ${d}.`,
  declAmeContact: 'Votre contact aéromédical',
  declCall: 'Appeler',
  declEmail: 'E-mail',
  backHome: 'Retour à l’accueil',
};

const ar: PortalStrings = {
  language: 'اللغة',
  nav: {
    home: 'الرئيسية',
    refer: 'التواصل مع الصحة المهنية',
    advice: 'نصائحي',
    documents: 'مستنداتي',
    rtw: 'العودة إلى العمل',
    offers: 'عروض العمل',
    declaration: 'إقرار الطاقم',
    privacy: 'الخصوصية',
    consents: 'موافقاتي',
    profile: 'الملف الشخصي',
  },
  loading: 'جارٍ التحميل…',
  tilesTitle: 'مستنداتي وخططي',
  tileDocuments: 'مستنداتي',
  tileRtw: 'عودتي إلى العمل',
  tileOffers: 'عروض العمل',
  tilePrivacy: 'من تلقّى معلوماتي',
  tileActions: (n) => (n === 1 ? 'عنصر واحد يحتاج إلى اهتمامك' : `${n} عناصر تحتاج إلى اهتمامك`),

  docsTitle: 'مستنداتي',
  docsIntro:
    'الشهادات والملاحظات والخطابات التي شاركتها الصحة المهنية معك. لا تتضمن أي تشخيص. يرجى تأكيد الاطلاع على الشهادات وملاحظات القيود بعد قراءتها.',
  docsEmpty: 'لا توجد مستندات بعد.',
  docsEmptyHint: 'عندما تشارك الصحة المهنية شهادة أو خطابًا معك، سيظهر هنا.',
  docTypes: {
    FITNESS_CERTIFICATE: 'شهادة اللياقة',
    RESTRICTION_NOTE: 'ملاحظة القيود',
    REFERRAL_LETTER: 'خطاب إحالة',
    CABIN_CREW_MEDICAL_REPORT: 'تقرير طبي لطاقم الضيافة',
    CREW_MEDICAL_REPORT: 'تقرير طبي للطاقم',
    FOOD_HANDLER_CLEARANCE: 'تصريح التعامل مع الأغذية',
    RESULT_LETTER: 'خطاب النتائج',
    RTW_PLAN: 'خطة العودة إلى العمل',
    JOB_OFFER: 'عرض عمل',
    IHR_DECISION_LETTER: 'خطاب القرار',
    EXTERNAL_REPORT: 'تقرير خارجي',
    OTHER: 'مستند',
  },
  docNumber: 'رقم المستند',
  docIssued: 'تاريخ الإصدار',
  docVersion: (v) => `الإصدار ${v}`,
  docOpen: 'فتح ملف PDF',
  docDownload: 'تنزيل',
  docOpening: 'جارٍ الفتح…',
  docOpenFailed: 'تعذّر فتح المستند. يرجى المحاولة مرة أخرى.',
  docAcknowledge: 'لقد قرأت هذا المستند',
  docAcknowledging: 'جارٍ الحفظ…',
  docAcknowledgeHint: 'يؤكد أنك استلمت هذا المستند وقرأته. ولا يعني ذلك موافقتك عليه.',
  docAcknowledgedOn: (d) => `تم التأكيد في ${d}`,
  docAcknowledgedOk: 'شكرًا لك – تم تسجيل تأكيدك.',
  docBadgeNew: 'جديد',
  docBadgeAction: 'يرجى التأكيد',
  docBadgeAcknowledged: 'تم التأكيد',
  docBadgeSuperseded: 'تم استبداله بإصدار أحدث',
  docSeeRtw: 'راجعها ووافق عليها في «العودة إلى العمل»',
  docSeeOffers: 'قم بالرد في «عروض العمل»',
  docsOpensNewTab: '(يفتح في علامة تبويب جديدة)',

  rtwTitle: 'عودتي إلى العمل',
  rtwIntro:
    'تصف خطة العودة إلى العمل ساعات عملك ومهامك وقيود العمل خطوة بخطوة. يرى مديرك الخطة نفسها – ولا تحتوي على أي تفاصيل طبية.',
  rtwEmpty: 'ليس لديك خطة عودة إلى العمل نشطة.',
  rtwEmptyHint: 'إذا اتفقت الصحة المهنية معك على عودة تدريجية، ستظهر الخطة هنا لتوافق عليها.',
  rtwStatus: { DRAFT: 'مسودة', ACTIVE: 'نشطة', COMPLETED: 'مكتملة', CANCELLED: 'ملغاة' },
  rtwStart: 'تاريخ البدء',
  rtwGoal: 'الهدف',
  rtwPhases: 'المراحل',
  rtwWeeks: (f, t) => (f === t ? `الأسبوع ${f}` : `الأسابيع ${f}–${t}`),
  rtwHoursPerDay: (h) => `${h} ساعات يوميًا`,
  rtwDuties: 'المهام',
  rtwRestrictions: 'قيود العمل',
  rtwNoRestrictions: 'لا توجد قيود في هذه المرحلة',
  rtwReviews: 'مواعيد المراجعة',
  rtwReviewDone: (d) => `تمت المراجعة في ${d}`,
  rtwNextReview: 'المراجعة التالية',
  rtwContact: 'جهة الاتصال',
  rtwContactFallback: 'هل لديك أسئلة؟ تواصل مع الصحة المهنية.',
  rtwComment: 'تعليق (اختياري)',
  rtwCommentHint: 'مثل سؤال حول ساعات عملك. يرجى عدم كتابة تفاصيل طبية.',
  rtwAgree: 'أوافق على هذه الخطة',
  rtwAgreeing: 'جارٍ الحفظ…',
  rtwDisagreeHint: 'إذا كنت لا توافق على الخطة، فلا تؤكدها – تواصل مع الصحة المهنية لمناقشتها.',
  rtwAgreedOn: (d) => `وافقت على هذه الخطة في ${d}.`,
  rtwAgreedOk: 'شكرًا لك – تم تسجيل موافقتك.',
  rtwYourComment: 'تعليقك',
  rtwManagerAck: (d) => `اطّلع مديرك على الخطة في ${d}.`,

  offersTitle: 'عروض العمل',
  offersIntro: 'مهام بديلة أو معدّلة تعرضها الموارد البشرية أثناء وجود قيود على عملك. تُراعى قيودك في كل عرض.',
  offersEmpty: 'ليس لديك أي عروض عمل.',
  offersEmptyHint: 'إذا عرضت عليك الموارد البشرية مهام بديلة، سيظهر العرض هنا لقبوله أو رفضه.',
  offerStatus: { ISSUED: 'بانتظار ردك', ACCEPTED: 'مقبول', DECLINED: 'مرفوض', WITHDRAWN: 'مسحوب', EXPIRED: 'منتهي الصلاحية' },
  offerRole: 'الوظيفة',
  offerDuties: 'المهام',
  offerLocation: 'الموقع',
  offerShift: 'المناوبة',
  offerHours: (h) => `${h} ساعة أسبوعيًا`,
  offerHoursLabel: 'الساعات',
  offerPay: 'الأجر',
  payBasis: { SAME_PAY: 'الأجر نفسه', PROTECTED_PAY: 'أجر محمي', ADJUSTED_PAY: 'أجر معدّل' },
  offerRestrictions: 'القيود المراعاة',
  offerRestrictionsHint: 'تم التحقق من توافق الوظيفة مع قيود عملك الحالية.',
  offerStart: 'البدء',
  offerEnd: 'الانتهاء',
  offerOpenEnded: 'حتى المراجعة',
  offerRespondBy: 'يرجى الرد قبل',
  offerRespondByPassed: 'انقضى موعد الرد. لا يزال بإمكانك الرد – وستقرر الموارد البشرية ما إذا كان العرض لا يزال متاحًا.',
  offerContact: 'جهة الاتصال',
  offerComment: 'تعليق (اختياري)',
  offerCommentHint: 'تراه الموارد البشرية. يرجى عدم كتابة تفاصيل طبية.',
  offerAccept: 'قبول العرض',
  offerDecline: 'رفض العرض',
  offerConfirmAcceptTitle: 'هل تريد قبول هذا العرض؟',
  offerConfirmDeclineTitle: 'هل تريد رفض هذا العرض؟',
  offerConfirmAccept: 'يُسجَّل قبولك إلكترونيًا ويتم إبلاغ الموارد البشرية. وهذا يحل محل التوقيع الورقي.',
  offerConfirmDecline: 'يتم إبلاغ الموارد البشرية برفضك. قد تتواصل معك الموارد البشرية أو الصحة المهنية لمناقشة خيارات أخرى.',
  offerConfirmYesAccept: 'نعم، أقبل',
  offerConfirmYesDecline: 'نعم، أرفض',
  cancel: 'إلغاء',
  sending: 'جارٍ الإرسال…',
  offerAcceptedOk: 'لقد قبلت العرض. تم إبلاغ الموارد البشرية.',
  offerDeclinedOk: 'لقد رفضت العرض. تم إبلاغ الموارد البشرية.',
  offerRespondedOn: (d) => `قمت بالرد في ${d}.`,

  privacyTitle: 'من تلقّى معلوماتي',
  privacyIntro:
    'في كل مرة تشارك فيها الصحة المهنية أحد مستنداتك خارجها – مثل مديرك أو الموارد البشرية أو مقدم خدمة شريك – يتم تسجيل ذلك هنا.',
  disclosuresTitle: 'سجل الإفصاح',
  disclosuresEmpty: 'لم تتم مشاركة أي من مستنداتك خارج الصحة المهنية.',
  disclosureDate: 'التاريخ',
  disclosureRecipient: 'الجهة المستلمة',
  disclosureDocument: 'المستند',
  disclosurePurpose: 'الغرض',
  disclosureStatus: 'الوصول',
  disclosureStatusLabels: { ACTIVE: 'الوصول نشط', REVOKED: 'تم إلغاء الوصول' },
  recipientTypes: { EMPLOYEE: 'أنت', MANAGER: 'المدير', HR: 'الموارد البشرية', PARTNER: 'مقدم خدمة شريك' },
  consentsTitle: 'موافقاتي',
  consentsIntro: 'تتم المشاركة مع مقدمي الخدمات الشركاء بناءً على موافقتك. يمكنك سحبها في أي وقت.',
  consentsEmpty: 'ليس لديك أي موافقات نشطة.',
  consentGiven: (d) => `مُنحت في ${d}`,
  consentManage: 'منح الموافقات أو مراجعتها',
  withdraw: 'سحب الموافقة',
  withdrawTitle: (p) => `هل تريد سحب موافقتك على «${p}»؟`,
  withdrawExplain: 'ما الذي يحدث عند سحب الموافقة:',
  withdrawPartner:
    'يفقد مقدمو الخدمات الشركاء فورًا الوصول إلى كل مستند تمت مشاركته بموجب هذه الموافقة. ويُسجَّل ذلك في سجل الإفصاح الخاص بك.',
  withdrawLaw:
    'لا يؤثر ذلك على المعلومات التي تمت مشاركتها سابقًا أو المشاركة التي يفرضها القانون – مثل حالة لياقتك للعمل (دون تفاصيل طبية) لمديرك.',
  withdrawConfirm: 'نعم، اسحب الموافقة',
  withdrawnOk: 'تم سحب الموافقة. تم إيقاف وصول الشركاء.',
  purposes: {
    TREATMENT_SHARING_PARTNER: 'مشاركة معلومات العلاج مع مقدم خدمة شريك',
    EAP_REFERRAL: 'الإحالة إلى برنامج مساعدة الموظفين',
    INSURER_SHARING: 'مشاركة المعلومات ذات الصلة مع شركة التأمين',
    WEARABLE_DATA: 'استخدام بيانات جهازي القابل للارتداء',
    HEALTH_CAMPAIGNS: 'دعوات إلى الحملات الصحية',
    RESEARCH_DEIDENTIFIED: 'استخدام بيانات مجهولة الهوية لأغراض البحث',
    FITNESS_STATUS: 'حالة اللياقة للعمل',
    LEGAL_OBLIGATION: 'مطلوب بموجب القانون',
  },

  declTitle: 'تم استلام الإقرار',
  declThanks: (ref) => `شكرًا لك على الإقرار بانخفاض اللياقة${ref ? ` – الرقم المرجعي لحالتك هو ${ref}` : ''}.`,
  declNoOperate: 'لا تزاول مهامك التشغيلية حتى يتم تقييمك.',
  declNext: 'ما الذي سيحدث بعد ذلك',
  declDefaultSteps: [
    'سيتواصل معك طبيب الطيران المعتمد (AME).',
    'سيتم تحديث حالتك بعد التقييم. يمكنك متابعتها من الشاشة الرئيسية.',
  ],
  declSchedulingOnly: 'لا يرى قسم جدولة الطاقم سوى «غير لائق – قيد الانتظار». ولا يرى السبب أو أي شيء كتبته.',
  declAckDue: (d) => `ستؤكد الصحة المهنية استلام إقرارك قبل ${d}.`,
  declAmeContact: 'جهة الاتصال الطبية للطيران',
  declCall: 'اتصال',
  declEmail: 'البريد الإلكتروني',
  backHome: 'العودة إلى الرئيسية',
};

const PORTAL: Record<string, PortalStrings> = { en, fr, ar };

export function portalStrings(code: string | undefined): PortalStrings {
  return PORTAL[baseLang(code)] ?? en;
}

/** Label for a code from a map; unknown codes (`UPPER_SNAKE`) are humanised, free text is shown as sent. */
export function labelOf(map: Record<string, string>, code: string | undefined | null, fallback = ''): string {
  if (!code) return fallback;
  const hit = map[code] ?? map[code.toUpperCase()];
  if (hit) return hit;
  return /^[A-Z0-9_]+$/.test(code) ? humanise(code) : code;
}
