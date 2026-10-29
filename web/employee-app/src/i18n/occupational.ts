import { ApiError } from '../auth/AuthClient';
import type { FormErrorCode } from '../intake/occupationalForms';
import { errorText, portalStrings } from './portal';
import { baseLang } from './strings';

/**
 * UI strings of the increment-3 screens (appointments, absence & sick certificates, OH advice reports, surveillance &
 * immunisations, pre-employment) in English, Arabic and French. Unknown languages fall back to English.
 */
export interface OhStrings {
  nav: { appointments: string; absence: string; surveillance: string; preEmployment: string };
  tilesTitle: string;
  tileAppointments: string;
  tileAbsence: string;
  tileAdvice: string;
  tileSurveillance: string;
  fieldErrors: Record<FormErrorCode, string>;
  clinicalContent: string;
  cancel: string;
  sending: string;
  back: string;
  yes: string;
  no: string;
  optional: string;
  statusLabel: string;
  opensNewTab: string;

  /* Appointments */
  apptTitle: string;
  apptIntro: string;
  apptEmpty: string;
  apptEmptyHint: string;
  apptUpcoming: string;
  apptPast: string;
  apptStatus: Record<string, string>;
  apptWhen: string;
  apptWhere: string;
  apptTicket: (ticket: string) => string;
  apptVideo: string;
  apptCheckIn: string;
  apptCheckInHint: string;
  apptCheckedInOk: (ticket?: string) => string;
  apptJoin: string;
  apptJoinHint: string;
  apptReschedule: string;
  apptRescheduleTitle: string;
  apptRescheduledOk: string;
  apptCancel: string;
  apptCancelTitle: string;
  apptCancelReason: string;
  apptCancelConfirm: string;
  apptCancelledOk: string;
  apptBookTitle: string;
  apptBookIntro: string;
  apptType: string;
  apptSite: string;
  apptChoose: string;
  apptFindSlots: string;
  apptNoSlots: string;
  apptChooseSlot: string;
  apptBook: string;
  apptBookedOk: (when: string) => string;
  apptRules: string;
  apptAlreadyBooked: string;
  apptNoTypes: string;

  /* Absence */
  absTitle: string;
  absIntro: string;
  absPrivacy: string;
  absEpisodes: string;
  absEpisodesEmpty: string;
  absPeriod: string;
  absOngoing: string;
  absDays: (n: number) => string;
  absTypes: Record<string, string>;
  absCertified: string;
  absNotCertified: string;
  certValidity: Record<string, string>;
  absUploadTitle: string;
  absUploadIntro: string;
  absTakePhoto: string;
  absChooseFile: string;
  absFileHint: string;
  absSelectedFile: (name: string) => string;
  absFrom: string;
  absTo: string;
  absEpisode: string;
  absEpisodeNone: string;
  absUpload: string;
  absUploadedOk: string;
  absCertificates: string;
  absCertificatesEmpty: string;
  certStatus: Record<string, string>;
  certStatusHint: Record<string, string>;
  certReceived: (date: string) => string;

  /* OH advice reports */
  advTitle: string;
  advIntro: string;
  advEmpty: string;
  advEmptyHint: string;
  advStatus: Record<string, string>;
  advRole: Record<string, string>;
  advRequestedBy: (role: string) => string;
  advFitness: string;
  fitness: Record<string, string>;
  advAdjustments: string;
  advRestrictions: string;
  advPrognosis: string;
  prognosis: Record<string, string>;
  advExpectedReturn: string;
  advReviewDate: string;
  advAnswers: string;
  advNone: string;
  advWhatSeesTitle: (role: string) => string;
  advSeesConsent: (role: string) => string;
  advSeesDecline: (role: string) => string;
  advNeverSees: string;
  advChoice: string;
  advConsent: string;
  advDecline: string;
  advComment: string;
  advCommentHint: string;
  advDeclineReason: string;
  advDeclineReasonHint: string;
  advSubmit: string;
  advConfirmConsentTitle: string;
  advConfirmDeclineTitle: string;
  advConfirmConsent: (role: string) => string;
  advConfirmDecline: (role: string) => string;
  advConfirmYes: string;
  advConsentedOk: string;
  advDeclinedOk: string;
  advYourComment: string;
  advReleasedOn: (date: string) => string;
  advWithheld: string;
  advOtherTitle: string;

  /* Surveillance */
  survTitle: string;
  survIntro: string;
  survProgrammes: string;
  survProgrammesEmpty: string;
  survStatus: Record<string, string>;
  survNextDue: string;
  survAppointment: (when: string) => string;
  survBookHint: string;
  survImms: string;
  survImmsEmpty: string;
  immStatus: Record<string, string>;
  immVaccine: string;
  immLastDose: string;
  immNextDue: string;
  immDoses: (given: number, required: number) => string;
  survCertTitle: string;
  survCertIntro: string;
  survCertRequest: string;
  survCertOk: string;
  survCertOpen: string;
  crewTitle: string;
  crewStatus: Record<string, string>;
  crewValidUntil: (date: string) => string;
  pregTitle: string;
  pregIntro: string;
  pregWho: string;
  pregStart: string;
  pregDate: string;
  pregSubmit: string;
  pregOk: string;

  /* Pre-employment */
  preTitle: string;
  preIntro: string;
  preNone: string;
  preNoneHint: string;
  preOffer: string;
  preJob: string;
  preProposedStart: string;
  preStatusLabel: string;
  preStatus: Record<string, string>;
  preProgress: (done: number, total: number) => string;
  preDone: string;
  preTodo: string;
  preStepQuestionnaire: string;
  preStepConsent: string;
  preStepDocuments: string;
  preStepBook: string;
  preQIntro: string;
  preQSubmit: string;
  preQOk: string;
  preQDone: string;
  preQEmpty: string;
  preConsentIntro: string;
  prePurposes: Record<string, string>;
  preConsentCheckbox: string;
  preConsentSubmit: string;
  preConsentOk: string;
  preConsentDone: string;
  preDocsIntro: string;
  preDocTitle: string;
  preDocUpload: string;
  preDocOk: string;
  preDocsList: string;
  preBookIntro: string;
  preBookNeeds: string;
  preSite: string;
  preSiteAny: string;
  preEarliest: string;
  preBookSubmit: string;
  preBookedOk: (when: string) => string;
  preAppointment: string;
  preWhatHrSees: string;
}

const en: OhStrings = {
  nav: { appointments: 'Appointments', absence: 'Absence', surveillance: 'Health checks', preEmployment: 'Pre-employment' },
  tilesTitle: 'Appointments and health checks',
  tileAppointments: 'My appointments',
  tileAbsence: 'Absence & sick notes',
  tileAdvice: 'Advice reports to review',
  tileSurveillance: 'Health checks & vaccinations',
  fieldErrors: {
    required: 'Please fill in this field.',
    fileType: 'Please choose a PDF, JPEG or PNG file.',
    fileSize: 'The file is larger than 10 MB.',
    dateInvalid: 'Please enter a valid date.',
    dateOrder: 'The end date must be on or after the start date.',
    dateFuture: 'The date cannot be that far in the future.',
    spanTooLong: 'A certificate can cover at most one year.',
    tooLong: 'This text is too long.',
  },
  clinicalContent: 'Please remove health or medical details from your text – it may be shown to your manager or HR.',
  cancel: 'Cancel',
  sending: 'Sending…',
  back: 'Back',
  yes: 'Yes',
  no: 'No',
  optional: '(optional)',
  statusLabel: 'Status',
  opensNewTab: '(opens in a new tab)',

  apptTitle: 'My appointments',
  apptIntro: 'Your Occupational Health appointments. Reminders never contain health details.',
  apptEmpty: 'You have no appointments.',
  apptEmptyHint: 'When Occupational Health books an appointment for you, or you book one below, it will appear here.',
  apptUpcoming: 'Upcoming',
  apptPast: 'Past and cancelled',
  apptStatus: { BOOKED: 'Booked', CHECKED_IN: 'Checked in', IN_PROGRESS: 'In progress', ATTENDED: 'Attended', NO_SHOW: 'Missed', CANCELLED: 'Cancelled' },
  apptWhen: 'When',
  apptWhere: 'Where',
  apptTicket: (t) => `Your ticket: ${t}`,
  apptVideo: 'Video consultation',
  apptCheckIn: 'Check in now',
  apptCheckInHint: 'You can check in from 60 minutes before your appointment. Then watch the queue screen for your ticket number.',
  apptCheckedInOk: (t) => (t ? `You are checked in. Your ticket number is ${t}.` : 'You are checked in.'),
  apptJoin: 'Join video consultation',
  apptJoinHint: 'The link opens 15 minutes before the start.',
  apptReschedule: 'Change time',
  apptRescheduleTitle: 'Choose a new time',
  apptRescheduledOk: 'Your appointment has been moved.',
  apptCancel: 'Cancel appointment',
  apptCancelTitle: 'Cancel this appointment?',
  apptCancelReason: 'Reason (optional – please do not include health details)',
  apptCancelConfirm: 'Yes, cancel it',
  apptCancelledOk: 'Your appointment has been cancelled.',
  apptBookTitle: 'Book an appointment',
  apptBookIntro: 'Some appointment types, such as vaccinations, can be booked here directly.',
  apptType: 'Appointment type',
  apptSite: 'Clinic',
  apptChoose: 'Choose…',
  apptFindSlots: 'Show free times',
  apptNoSlots: 'No free times in the next 60 days. Please try another clinic or contact Occupational Health.',
  apptChooseSlot: 'Free times',
  apptBook: 'Book this time',
  apptBookedOk: (w) => `Booked for ${w}. You will get a reminder.`,
  apptRules: 'Bookings need at least 24 hours’ notice and can be up to 60 days ahead. One open booking per type.',
  apptAlreadyBooked: 'You already have an open booking of this type – change or cancel it instead.',
  apptNoTypes: 'No appointment types can be booked online at the moment.',

  absTitle: 'My absence',
  absIntro: 'Your recorded absence periods and the sick certificates you have sent.',
  absPrivacy: 'Your certificate is seen by Occupational Health only. Your manager and HR see only the dates and whether a valid certificate was received – never the reason or the certificate itself.',
  absEpisodes: 'Absence periods',
  absEpisodesEmpty: 'No absence periods recorded.',
  absPeriod: 'Period',
  absOngoing: 'ongoing',
  absDays: (n) => (n === 1 ? '1 day' : `${n} days`),
  absTypes: { SICKNESS: 'Sickness', INJURY_WORK: 'Injury at work', INJURY_NON_WORK: 'Injury outside work', MATERNITY_RELATED: 'Pregnancy-related', OTHER_AUTHORISED: 'Other authorised absence' },
  absCertified: 'Certificate received',
  absNotCertified: 'No certificate',
  certValidity: { PENDING: 'Certificate being checked', VALID: 'Certificate valid', INVALID: 'Certificate not accepted' },
  absUploadTitle: 'Send a sick certificate',
  absUploadIntro: 'Take a photo of your certificate or choose a file, then enter the dates it covers.',
  absTakePhoto: 'Take a photo',
  absChooseFile: 'Choose a file',
  absFileHint: 'PDF, JPEG or PNG, up to 10 MB. The file is sent securely and not stored on this device.',
  absSelectedFile: (n) => `Selected: ${n}`,
  absFrom: 'Unfit for work from',
  absTo: 'Until',
  absEpisode: 'Absence period',
  absEpisodeNone: 'Not sure / not listed',
  absUpload: 'Send certificate',
  absUploadedOk: 'Thank you – your certificate has been received and will be checked.',
  absCertificates: 'My certificates',
  absCertificatesEmpty: 'You have not sent any certificates yet.',
  certStatus: { RECEIVED: 'Received – being checked', VALID: 'Valid', INVALID: 'Not accepted' },
  certStatusHint: {
    RECEIVED: 'Occupational Health will check it shortly.',
    VALID: 'Your certificate has been accepted.',
    INVALID: 'Your certificate could not be accepted. Occupational Health will contact you.',
  },
  certReceived: (d) => `Sent ${d}`,

  advTitle: 'Advice reports for your manager or HR',
  advIntro: 'Your manager or HR asked Occupational Health for advice about your work. Before anything is shared, you can read the report, add a comment and decide whether it is released.',
  advEmpty: 'No advice reports.',
  advEmptyHint: 'If your manager or HR asks Occupational Health for advice about you, the report will be shown to you here first.',
  advStatus: { SUBMITTED: 'Requested', ACCEPTED: 'Being prepared', REPORT_DRAFTED: 'Being prepared', AWAITING_EMPLOYEE: 'Waiting for your decision', RELEASED: 'Released', WITHHELD: 'Not released', WITHDRAWN: 'Withdrawn' },
  advRole: { MANAGER: 'manager', HR: 'HR' },
  advRequestedBy: (r) => `Requested by your ${r}`,
  advFitness: 'Fitness for work',
  fitness: { FIT: 'Fit for work', FIT_WITH_ADJUSTMENTS: 'Fit with adjustments', TEMPORARILY_UNFIT: 'Temporarily unfit for work', UNFIT_LONG_TERM: 'Unfit for work in the longer term' },
  advAdjustments: 'Recommended adjustments',
  advRestrictions: 'Work restrictions',
  advPrognosis: 'Expected return',
  prognosis: {
    RTW_WITHIN_2_WEEKS: 'Return to work expected within 2 weeks',
    RTW_WITHIN_4_WEEKS: 'Return to work expected within 4 weeks',
    RTW_WITHIN_12_WEEKS: 'Return to work expected within 12 weeks',
    UNCERTAIN: 'Not yet clear',
    RTW_UNLIKELY: 'Return to the current role is unlikely',
  },
  advExpectedReturn: 'Expected return date',
  advReviewDate: 'Next review',
  advAnswers: 'Answers to the questions asked',
  advNone: 'None',
  advWhatSeesTitle: (r) => `What your ${r} will see`,
  advSeesConsent: (r) => `If you consent, your ${r} receives exactly this report – fitness for work, adjustments, restrictions, expected return and the answers above – together with your comment.`,
  advSeesDecline: (r) => `If you do not consent, your ${r} only sees that the report was not released. You can talk to Occupational Health about a new version.`,
  advNeverSees: 'They never see a diagnosis, your medical history or anything you told Occupational Health in confidence.',
  advChoice: 'Your decision',
  advConsent: 'I consent to this report being released',
  advDecline: 'I do not consent',
  advComment: 'Comment for your manager or HR',
  advCommentHint: 'Shown with the report if you consent. Please do not include medical details.',
  advDeclineReason: 'Why do you not consent?',
  advDeclineReasonHint: 'Seen by Occupational Health only, to help prepare a new version.',
  advSubmit: 'Continue',
  advConfirmConsentTitle: 'Release this report?',
  advConfirmDeclineTitle: 'Do not release this report?',
  advConfirmConsent: (r) => `The report and your comment will be sent to your ${r}. This cannot be undone.`,
  advConfirmDecline: (r) => `Your ${r} will only see that the report was not released.`,
  advConfirmYes: 'Yes, confirm',
  advConsentedOk: 'Thank you – the report has been released.',
  advDeclinedOk: 'Your decision has been recorded – the report has not been released.',
  advYourComment: 'Your comment',
  advReleasedOn: (d) => `Released ${d}`,
  advWithheld: 'You decided not to release this report.',
  advOtherTitle: 'Advice from Occupational Health',

  survTitle: 'My health checks',
  survIntro: 'Health checks and vaccinations required for your role. Your manager only sees whether you are up to date – never results.',
  survProgrammes: 'Health check programmes',
  survProgrammesEmpty: 'Your role currently needs no regular health checks.',
  survStatus: { CURRENT: 'Up to date', DUE: 'Due soon', OVERDUE: 'Overdue', SCHEDULED: 'Booked', EXEMPT: 'Exempt' },
  survNextDue: 'Next due',
  survAppointment: (w) => `Appointment: ${w}`,
  survBookHint: 'You will receive an invitation. You can also see your bookings in “My appointments”.',
  survImms: 'Vaccinations',
  survImmsEmpty: 'No vaccinations are required for your role.',
  immStatus: { COMPLETE: 'Complete', DUE: 'Due', OVERDUE: 'Overdue', TITRE_NEEDED: 'Blood test needed' },
  immVaccine: 'Vaccine',
  immLastDose: 'Last dose',
  immNextDue: 'Next due',
  immDoses: (g, r) => `${g} of ${r} doses`,
  survCertTitle: 'Vaccination certificate',
  survCertIntro: 'Get a signed certificate of your vaccinations, for example for travel. It will appear in “My documents”.',
  survCertRequest: 'Create vaccination certificate',
  survCertOk: 'Your vaccination certificate is ready in “My documents”.',
  survCertOpen: 'Go to My documents',
  crewTitle: 'Crew medical certificate',
  crewStatus: { VALID: 'Valid', EXPIRING: 'Expiring soon', EXPIRED: 'Expired', SUSPENDED: 'Suspended' },
  crewValidUntil: (d) => `Valid until ${d}`,
  pregTitle: 'Pregnancy declaration (crew)',
  pregIntro: 'If you are pregnant, please tell the aeromedical team so they can review your cosmic radiation dose and duties with you.',
  pregWho: 'Only the aeromedical examiner (AME) and the Chief Medical Officer see this. Crew scheduling and your manager are not told.',
  pregStart: 'Make a pregnancy declaration',
  pregDate: 'Date of declaration',
  pregSubmit: 'Send declaration',
  pregOk: 'Thank you – the aeromedical team will contact you.',

  preTitle: 'Pre-employment health assessment',
  preIntro: 'Before you start, Occupational Health checks that the role is safe for you. Your answers are confidential to Occupational Health – HR only receives the outcome (for example “fit”).',
  preNone: 'No pre-employment assessment found.',
  preNoneHint: 'If you were offered a role, you will receive an invitation by e-mail.',
  preOffer: 'Offer reference',
  preJob: 'Role',
  preProposedStart: 'Proposed start',
  preStatusLabel: 'Status',
  preStatus: { INVITED: 'Invited', QUESTIONNAIRE_DONE: 'Questionnaire completed', BOOKED: 'Assessment booked', EXAMINED: 'Assessment done', OUTCOME_RECORDED: 'Outcome sent to HR', WITHDRAWN: 'Withdrawn' },
  preProgress: (d, t) => `${d} of ${t} steps done`,
  preDone: 'Done',
  preTodo: 'To do',
  preStepQuestionnaire: '1. Health questionnaire',
  preStepConsent: '2. Consent',
  preStepDocuments: '3. Documents',
  preStepBook: '4. Book your assessment',
  preQIntro: 'Please answer honestly. Only Occupational Health clinicians read your answers.',
  preQSubmit: 'Send answers',
  preQOk: 'Thank you – your answers have been sent.',
  preQDone: 'You have completed the questionnaire.',
  preQEmpty: 'There are no questions for this role.',
  preConsentIntro: 'We need your consent to:',
  prePurposes: {
    PRE_EMPLOYMENT_ASSESSMENT: 'carry out your pre-employment health assessment, and',
    HR_STATUS_FEED: 'tell HR the outcome only (fit / fit with adjustments / unfit) – never your answers or results.',
  },
  preConsentCheckbox: 'I give my consent',
  preConsentSubmit: 'Save consent',
  preConsentOk: 'Thank you – your consent has been recorded.',
  preConsentDone: 'You have given your consent.',
  preDocsIntro: 'Upload documents Occupational Health asked for, such as vaccination records or reports from your doctor (optional).',
  preDocTitle: 'Document name',
  preDocUpload: 'Upload document',
  preDocOk: 'Document uploaded.',
  preDocsList: 'Uploaded documents',
  preBookIntro: 'Choose where and from when you can attend; we will book the first free assessment.',
  preBookNeeds: 'Please complete the questionnaire and give consent first.',
  preSite: 'Clinic',
  preSiteAny: 'Any clinic',
  preEarliest: 'Earliest date',
  preBookSubmit: 'Book assessment',
  preBookedOk: (w) => `Your assessment is booked for ${w}.`,
  preAppointment: 'Your assessment',
  preWhatHrSees: 'HR sees only the status of your assessment and the outcome.',
};

const fr: OhStrings = {
  nav: { appointments: 'Rendez-vous', absence: 'Absences', surveillance: 'Bilans de santé', preEmployment: 'Visite d’embauche' },
  tilesTitle: 'Rendez-vous et bilans de santé',
  tileAppointments: 'Mes rendez-vous',
  tileAbsence: 'Absences et arrêts de travail',
  tileAdvice: 'Rapports d’avis à examiner',
  tileSurveillance: 'Bilans de santé et vaccinations',
  fieldErrors: {
    required: 'Veuillez remplir ce champ.',
    fileType: 'Veuillez choisir un fichier PDF, JPEG ou PNG.',
    fileSize: 'Le fichier dépasse 10 Mo.',
    dateInvalid: 'Veuillez saisir une date valide.',
    dateOrder: 'La date de fin doit être égale ou postérieure à la date de début.',
    dateFuture: 'La date ne peut pas être aussi éloignée dans le futur.',
    spanTooLong: 'Un certificat peut couvrir au maximum un an.',
    tooLong: 'Ce texte est trop long.',
  },
  clinicalContent: 'Veuillez retirer toute information de santé ou médicale de votre texte – il peut être montré à votre responsable ou aux RH.',
  cancel: 'Annuler',
  sending: 'Envoi…',
  back: 'Retour',
  yes: 'Oui',
  no: 'Non',
  optional: '(facultatif)',
  statusLabel: 'Statut',
  opensNewTab: '(s’ouvre dans un nouvel onglet)',

  apptTitle: 'Mes rendez-vous',
  apptIntro: 'Vos rendez-vous de Santé au travail. Les rappels ne contiennent jamais d’informations de santé.',
  apptEmpty: 'Vous n’avez aucun rendez-vous.',
  apptEmptyHint: 'Lorsque la Santé au travail vous fixe un rendez-vous, ou que vous en réservez un ci-dessous, il apparaîtra ici.',
  apptUpcoming: 'À venir',
  apptPast: 'Passés et annulés',
  apptStatus: { BOOKED: 'Réservé', CHECKED_IN: 'Enregistré', IN_PROGRESS: 'En cours', ATTENDED: 'Effectué', NO_SHOW: 'Manqué', CANCELLED: 'Annulé' },
  apptWhen: 'Quand',
  apptWhere: 'Où',
  apptTicket: (t) => `Votre ticket : ${t}`,
  apptVideo: 'Téléconsultation',
  apptCheckIn: 'S’enregistrer maintenant',
  apptCheckInHint: 'Vous pouvez vous enregistrer à partir de 60 minutes avant votre rendez-vous. Surveillez ensuite l’écran d’appel pour votre numéro.',
  apptCheckedInOk: (t) => (t ? `Vous êtes enregistré(e). Votre numéro de ticket est ${t}.` : 'Vous êtes enregistré(e).'),
  apptJoin: 'Rejoindre la téléconsultation',
  apptJoinHint: 'Le lien s’ouvre 15 minutes avant le début.',
  apptReschedule: 'Changer l’horaire',
  apptRescheduleTitle: 'Choisir un nouvel horaire',
  apptRescheduledOk: 'Votre rendez-vous a été déplacé.',
  apptCancel: 'Annuler le rendez-vous',
  apptCancelTitle: 'Annuler ce rendez-vous ?',
  apptCancelReason: 'Motif (facultatif – n’indiquez pas d’informations de santé)',
  apptCancelConfirm: 'Oui, annuler',
  apptCancelledOk: 'Votre rendez-vous a été annulé.',
  apptBookTitle: 'Prendre rendez-vous',
  apptBookIntro: 'Certains types de rendez-vous, comme les vaccinations, peuvent être réservés directement ici.',
  apptType: 'Type de rendez-vous',
  apptSite: 'Centre médical',
  apptChoose: 'Choisir…',
  apptFindSlots: 'Afficher les créneaux libres',
  apptNoSlots: 'Aucun créneau libre dans les 60 prochains jours. Essayez un autre centre ou contactez la Santé au travail.',
  apptChooseSlot: 'Créneaux libres',
  apptBook: 'Réserver ce créneau',
  apptBookedOk: (w) => `Réservé pour le ${w}. Vous recevrez un rappel.`,
  apptRules: 'Les réservations doivent être faites au moins 24 heures à l’avance et jusqu’à 60 jours. Une réservation ouverte par type.',
  apptAlreadyBooked: 'Vous avez déjà une réservation ouverte de ce type – modifiez-la ou annulez-la.',
  apptNoTypes: 'Aucun type de rendez-vous ne peut être réservé en ligne pour le moment.',

  absTitle: 'Mes absences',
  absIntro: 'Vos périodes d’absence enregistrées et les arrêts de travail que vous avez envoyés.',
  absPrivacy: 'Votre arrêt de travail n’est vu que par la Santé au travail. Votre responsable et les RH ne voient que les dates et si un certificat valide a été reçu – jamais le motif ni le certificat lui-même.',
  absEpisodes: 'Périodes d’absence',
  absEpisodesEmpty: 'Aucune période d’absence enregistrée.',
  absPeriod: 'Période',
  absOngoing: 'en cours',
  absDays: (n) => (n === 1 ? '1 jour' : `${n} jours`),
  absTypes: { SICKNESS: 'Maladie', INJURY_WORK: 'Accident du travail', INJURY_NON_WORK: 'Accident hors travail', MATERNITY_RELATED: 'Liée à la grossesse', OTHER_AUTHORISED: 'Autre absence autorisée' },
  absCertified: 'Certificat reçu',
  absNotCertified: 'Pas de certificat',
  certValidity: { PENDING: 'Certificat en cours de vérification', VALID: 'Certificat valide', INVALID: 'Certificat non accepté' },
  absUploadTitle: 'Envoyer un arrêt de travail',
  absUploadIntro: 'Prenez une photo de votre certificat ou choisissez un fichier, puis indiquez les dates couvertes.',
  absTakePhoto: 'Prendre une photo',
  absChooseFile: 'Choisir un fichier',
  absFileHint: 'PDF, JPEG ou PNG, 10 Mo maximum. Le fichier est envoyé de façon sécurisée et n’est pas conservé sur cet appareil.',
  absSelectedFile: (n) => `Sélectionné : ${n}`,
  absFrom: 'Inapte au travail du',
  absTo: 'Jusqu’au',
  absEpisode: 'Période d’absence',
  absEpisodeNone: 'Je ne sais pas / non listée',
  absUpload: 'Envoyer le certificat',
  absUploadedOk: 'Merci – votre certificat a été reçu et sera vérifié.',
  absCertificates: 'Mes certificats',
  absCertificatesEmpty: 'Vous n’avez encore envoyé aucun certificat.',
  certStatus: { RECEIVED: 'Reçu – en vérification', VALID: 'Valide', INVALID: 'Non accepté' },
  certStatusHint: {
    RECEIVED: 'La Santé au travail va le vérifier prochainement.',
    VALID: 'Votre certificat a été accepté.',
    INVALID: 'Votre certificat n’a pas pu être accepté. La Santé au travail vous contactera.',
  },
  certReceived: (d) => `Envoyé le ${d}`,

  advTitle: 'Rapports d’avis pour votre responsable ou les RH',
  advIntro: 'Votre responsable ou les RH ont demandé un avis à la Santé au travail sur votre travail. Avant tout partage, vous pouvez lire le rapport, ajouter un commentaire et décider s’il est transmis.',
  advEmpty: 'Aucun rapport d’avis.',
  advEmptyHint: 'Si votre responsable ou les RH demandent un avis à la Santé au travail à votre sujet, le rapport vous sera d’abord présenté ici.',
  advStatus: { SUBMITTED: 'Demandé', ACCEPTED: 'En préparation', REPORT_DRAFTED: 'En préparation', AWAITING_EMPLOYEE: 'En attente de votre décision', RELEASED: 'Transmis', WITHHELD: 'Non transmis', WITHDRAWN: 'Retiré' },
  advRole: { MANAGER: 'responsable', HR: 'service RH' },
  advRequestedBy: (r) => `Demandé par votre ${r}`,
  advFitness: 'Aptitude au travail',
  fitness: { FIT: 'Apte au travail', FIT_WITH_ADJUSTMENTS: 'Apte avec aménagements', TEMPORARILY_UNFIT: 'Temporairement inapte au travail', UNFIT_LONG_TERM: 'Inapte au travail à long terme' },
  advAdjustments: 'Aménagements recommandés',
  advRestrictions: 'Restrictions de travail',
  advPrognosis: 'Retour prévu',
  prognosis: {
    RTW_WITHIN_2_WEEKS: 'Retour au travail prévu sous 2 semaines',
    RTW_WITHIN_4_WEEKS: 'Retour au travail prévu sous 4 semaines',
    RTW_WITHIN_12_WEEKS: 'Retour au travail prévu sous 12 semaines',
    UNCERTAIN: 'Pas encore déterminé',
    RTW_UNLIKELY: 'Retour au poste actuel peu probable',
  },
  advExpectedReturn: 'Date de retour prévue',
  advReviewDate: 'Prochain réexamen',
  advAnswers: 'Réponses aux questions posées',
  advNone: 'Aucun',
  advWhatSeesTitle: (r) => `Ce que votre ${r} verra`,
  advSeesConsent: (r) => `Si vous consentez, votre ${r} reçoit exactement ce rapport – aptitude, aménagements, restrictions, retour prévu et réponses ci-dessus – avec votre commentaire.`,
  advSeesDecline: (r) => `Si vous ne consentez pas, votre ${r} voit seulement que le rapport n’a pas été transmis. Vous pouvez discuter d’une nouvelle version avec la Santé au travail.`,
  advNeverSees: 'Il ne voit jamais de diagnostic, vos antécédents médicaux ni ce que vous avez confié à la Santé au travail.',
  advChoice: 'Votre décision',
  advConsent: 'Je consens à la transmission de ce rapport',
  advDecline: 'Je ne consens pas',
  advComment: 'Commentaire pour votre responsable ou les RH',
  advCommentHint: 'Affiché avec le rapport si vous consentez. N’indiquez pas d’informations médicales.',
  advDeclineReason: 'Pourquoi ne consentez-vous pas ?',
  advDeclineReasonHint: 'Vu uniquement par la Santé au travail, pour préparer une nouvelle version.',
  advSubmit: 'Continuer',
  advConfirmConsentTitle: 'Transmettre ce rapport ?',
  advConfirmDeclineTitle: 'Ne pas transmettre ce rapport ?',
  advConfirmConsent: (r) => `Le rapport et votre commentaire seront envoyés à votre ${r}. Cette action est définitive.`,
  advConfirmDecline: (r) => `Votre ${r} verra seulement que le rapport n’a pas été transmis.`,
  advConfirmYes: 'Oui, confirmer',
  advConsentedOk: 'Merci – le rapport a été transmis.',
  advDeclinedOk: 'Votre décision a été enregistrée – le rapport n’a pas été transmis.',
  advYourComment: 'Votre commentaire',
  advReleasedOn: (d) => `Transmis le ${d}`,
  advWithheld: 'Vous avez décidé de ne pas transmettre ce rapport.',
  advOtherTitle: 'Conseils de la Santé au travail',

  survTitle: 'Mes bilans de santé',
  survIntro: 'Bilans de santé et vaccinations requis pour votre poste. Votre responsable voit seulement si vous êtes à jour – jamais les résultats.',
  survProgrammes: 'Programmes de suivi',
  survProgrammesEmpty: 'Votre poste ne nécessite actuellement aucun suivi régulier.',
  survStatus: { CURRENT: 'À jour', DUE: 'Bientôt dû', OVERDUE: 'En retard', SCHEDULED: 'Réservé', EXEMPT: 'Exempté' },
  survNextDue: 'Prochaine échéance',
  survAppointment: (w) => `Rendez-vous : ${w}`,
  survBookHint: 'Vous recevrez une convocation. Vos réservations figurent aussi dans « Mes rendez-vous ».',
  survImms: 'Vaccinations',
  survImmsEmpty: 'Aucune vaccination n’est requise pour votre poste.',
  immStatus: { COMPLETE: 'Complète', DUE: 'À faire', OVERDUE: 'En retard', TITRE_NEEDED: 'Prise de sang nécessaire' },
  immVaccine: 'Vaccin',
  immLastDose: 'Dernière dose',
  immNextDue: 'Prochaine échéance',
  immDoses: (g, r) => `${g} dose(s) sur ${r}`,
  survCertTitle: 'Certificat de vaccination',
  survCertIntro: 'Obtenez un certificat signé de vos vaccinations, par exemple pour voyager. Il apparaîtra dans « Mes documents ».',
  survCertRequest: 'Créer le certificat de vaccination',
  survCertOk: 'Votre certificat de vaccination est disponible dans « Mes documents ».',
  survCertOpen: 'Aller à Mes documents',
  crewTitle: 'Certificat médical navigant',
  crewStatus: { VALID: 'Valide', EXPIRING: 'Expire bientôt', EXPIRED: 'Expiré', SUSPENDED: 'Suspendu' },
  crewValidUntil: (d) => `Valide jusqu’au ${d}`,
  pregTitle: 'Déclaration de grossesse (navigants)',
  pregIntro: 'Si vous êtes enceinte, informez l’équipe aéromédicale afin qu’elle examine avec vous votre dose de rayonnement cosmique et vos fonctions.',
  pregWho: 'Seuls le médecin examinateur aéronautique (AME) et le médecin-chef voient cette déclaration. La planification des équipages et votre responsable ne sont pas informés.',
  pregStart: 'Faire une déclaration de grossesse',
  pregDate: 'Date de la déclaration',
  pregSubmit: 'Envoyer la déclaration',
  pregOk: 'Merci – l’équipe aéromédicale vous contactera.',

  preTitle: 'Visite médicale d’embauche',
  preIntro: 'Avant votre prise de poste, la Santé au travail vérifie que le poste est sûr pour vous. Vos réponses restent confidentielles – les RH ne reçoivent que le résultat (par exemple « apte »).',
  preNone: 'Aucune visite d’embauche trouvée.',
  preNoneHint: 'Si un poste vous a été proposé, vous recevrez une invitation par e-mail.',
  preOffer: 'Référence de l’offre',
  preJob: 'Poste',
  preProposedStart: 'Date de début prévue',
  preStatusLabel: 'Statut',
  preStatus: { INVITED: 'Invité(e)', QUESTIONNAIRE_DONE: 'Questionnaire rempli', BOOKED: 'Visite réservée', EXAMINED: 'Visite effectuée', OUTCOME_RECORDED: 'Résultat transmis aux RH', WITHDRAWN: 'Retiré' },
  preProgress: (d, t) => `${d} étape(s) sur ${t} terminée(s)`,
  preDone: 'Terminé',
  preTodo: 'À faire',
  preStepQuestionnaire: '1. Questionnaire de santé',
  preStepConsent: '2. Consentement',
  preStepDocuments: '3. Documents',
  preStepBook: '4. Réserver votre visite',
  preQIntro: 'Veuillez répondre honnêtement. Seuls les professionnels de la Santé au travail lisent vos réponses.',
  preQSubmit: 'Envoyer les réponses',
  preQOk: 'Merci – vos réponses ont été envoyées.',
  preQDone: 'Vous avez rempli le questionnaire.',
  preQEmpty: 'Aucune question pour ce poste.',
  preConsentIntro: 'Nous avons besoin de votre consentement pour :',
  prePurposes: {
    PRE_EMPLOYMENT_ASSESSMENT: 'réaliser votre visite médicale d’embauche, et',
    HR_STATUS_FEED: 'communiquer aux RH uniquement le résultat (apte / apte avec aménagements / inapte) – jamais vos réponses ni vos résultats.',
  },
  preConsentCheckbox: 'Je donne mon consentement',
  preConsentSubmit: 'Enregistrer le consentement',
  preConsentOk: 'Merci – votre consentement a été enregistré.',
  preConsentDone: 'Vous avez donné votre consentement.',
  preDocsIntro: 'Téléversez les documents demandés par la Santé au travail, comme un carnet de vaccination ou un compte rendu de votre médecin (facultatif).',
  preDocTitle: 'Nom du document',
  preDocUpload: 'Téléverser le document',
  preDocOk: 'Document téléversé.',
  preDocsList: 'Documents téléversés',
  preBookIntro: 'Choisissez le lieu et la date à partir de laquelle vous êtes disponible ; nous réserverons la première visite libre.',
  preBookNeeds: 'Veuillez d’abord remplir le questionnaire et donner votre consentement.',
  preSite: 'Centre médical',
  preSiteAny: 'N’importe quel centre',
  preEarliest: 'Date au plus tôt',
  preBookSubmit: 'Réserver la visite',
  preBookedOk: (w) => `Votre visite est réservée pour le ${w}.`,
  preAppointment: 'Votre visite',
  preWhatHrSees: 'Les RH voient seulement l’état d’avancement de votre visite et son résultat.',
};

const ar: OhStrings = {
  nav: { appointments: 'المواعيد', absence: 'الغياب', surveillance: 'الفحوصات الصحية', preEmployment: 'فحص ما قبل التوظيف' },
  tilesTitle: 'المواعيد والفحوصات الصحية',
  tileAppointments: 'مواعيدي',
  tileAbsence: 'الغياب والإجازات المرضية',
  tileAdvice: 'تقارير مشورة بانتظار مراجعتك',
  tileSurveillance: 'الفحوصات الصحية والتطعيمات',
  fieldErrors: {
    required: 'يرجى تعبئة هذا الحقل.',
    fileType: 'يرجى اختيار ملف PDF أو JPEG أو PNG.',
    fileSize: 'حجم الملف أكبر من 10 ميغابايت.',
    dateInvalid: 'يرجى إدخال تاريخ صحيح.',
    dateOrder: 'يجب أن يكون تاريخ الانتهاء في يوم البدء أو بعده.',
    dateFuture: 'لا يمكن أن يكون التاريخ بعيدًا إلى هذا الحد في المستقبل.',
    spanTooLong: 'يمكن أن تغطي الشهادة سنة واحدة كحد أقصى.',
    tooLong: 'هذا النص طويل جدًا.',
  },
  clinicalContent: 'يرجى إزالة أي تفاصيل صحية أو طبية من النص – فقد يُعرض على مديرك أو الموارد البشرية.',
  cancel: 'إلغاء',
  sending: 'جارٍ الإرسال…',
  back: 'رجوع',
  yes: 'نعم',
  no: 'لا',
  optional: '(اختياري)',
  statusLabel: 'الحالة',
  opensNewTab: '(يفتح في علامة تبويب جديدة)',

  apptTitle: 'مواعيدي',
  apptIntro: 'مواعيدك لدى الصحة المهنية. لا تتضمن التذكيرات أي تفاصيل صحية.',
  apptEmpty: 'ليس لديك مواعيد.',
  apptEmptyHint: 'عندما تحجز لك الصحة المهنية موعدًا، أو تحجز أنت موعدًا أدناه، سيظهر هنا.',
  apptUpcoming: 'القادمة',
  apptPast: 'السابقة والملغاة',
  apptStatus: { BOOKED: 'محجوز', CHECKED_IN: 'تم تسجيل الوصول', IN_PROGRESS: 'جارٍ', ATTENDED: 'تم الحضور', NO_SHOW: 'لم يتم الحضور', CANCELLED: 'ملغى' },
  apptWhen: 'الموعد',
  apptWhere: 'المكان',
  apptTicket: (t) => `رقم تذكرتك: ${t}`,
  apptVideo: 'استشارة عبر الفيديو',
  apptCheckIn: 'سجّل وصولك الآن',
  apptCheckInHint: 'يمكنك تسجيل وصولك قبل موعدك بـ 60 دقيقة. ثم راقب شاشة الانتظار لرقم تذكرتك.',
  apptCheckedInOk: (t) => (t ? `تم تسجيل وصولك. رقم تذكرتك هو ${t}.` : 'تم تسجيل وصولك.'),
  apptJoin: 'الانضمام إلى استشارة الفيديو',
  apptJoinHint: 'يصبح الرابط متاحًا قبل البدء بـ 15 دقيقة.',
  apptReschedule: 'تغيير الوقت',
  apptRescheduleTitle: 'اختر وقتًا جديدًا',
  apptRescheduledOk: 'تم تغيير موعدك.',
  apptCancel: 'إلغاء الموعد',
  apptCancelTitle: 'هل تريد إلغاء هذا الموعد؟',
  apptCancelReason: 'السبب (اختياري – يرجى عدم ذكر تفاصيل صحية)',
  apptCancelConfirm: 'نعم، ألغِ الموعد',
  apptCancelledOk: 'تم إلغاء موعدك.',
  apptBookTitle: 'حجز موعد',
  apptBookIntro: 'يمكن حجز بعض أنواع المواعيد، مثل التطعيمات، مباشرة من هنا.',
  apptType: 'نوع الموعد',
  apptSite: 'العيادة',
  apptChoose: 'اختر…',
  apptFindSlots: 'عرض الأوقات المتاحة',
  apptNoSlots: 'لا توجد أوقات متاحة خلال الأيام الستين القادمة. يرجى تجربة عيادة أخرى أو التواصل مع الصحة المهنية.',
  apptChooseSlot: 'الأوقات المتاحة',
  apptBook: 'احجز هذا الوقت',
  apptBookedOk: (w) => `تم الحجز في ${w}. ستتلقى تذكيرًا.`,
  apptRules: 'يجب الحجز قبل 24 ساعة على الأقل وحتى 60 يومًا مقدمًا. حجز مفتوح واحد لكل نوع.',
  apptAlreadyBooked: 'لديك بالفعل حجز مفتوح من هذا النوع – قم بتغييره أو إلغائه بدلًا من ذلك.',
  apptNoTypes: 'لا يمكن حجز أي نوع من المواعيد عبر الإنترنت حاليًا.',

  absTitle: 'غيابي',
  absIntro: 'فترات غيابك المسجلة والشهادات المرضية التي أرسلتها.',
  absPrivacy: 'لا يطّلع على شهادتك سوى الصحة المهنية. يرى مديرك والموارد البشرية التواريخ فقط وما إذا تم استلام شهادة صالحة – وليس السبب أو الشهادة نفسها.',
  absEpisodes: 'فترات الغياب',
  absEpisodesEmpty: 'لا توجد فترات غياب مسجلة.',
  absPeriod: 'الفترة',
  absOngoing: 'مستمرة',
  absDays: (n) => (n === 1 ? 'يوم واحد' : `${n} أيام`),
  absTypes: { SICKNESS: 'مرض', INJURY_WORK: 'إصابة في العمل', INJURY_NON_WORK: 'إصابة خارج العمل', MATERNITY_RELATED: 'مرتبط بالحمل', OTHER_AUTHORISED: 'غياب آخر مصرّح به' },
  absCertified: 'تم استلام الشهادة',
  absNotCertified: 'لا توجد شهادة',
  certValidity: { PENDING: 'الشهادة قيد التحقق', VALID: 'الشهادة صالحة', INVALID: 'لم تُقبل الشهادة' },
  absUploadTitle: 'إرسال شهادة مرضية',
  absUploadIntro: 'التقط صورة لشهادتك أو اختر ملفًا، ثم أدخل التواريخ التي تغطيها.',
  absTakePhoto: 'التقاط صورة',
  absChooseFile: 'اختيار ملف',
  absFileHint: 'PDF أو JPEG أو PNG، بحد أقصى 10 ميغابايت. يُرسل الملف بأمان ولا يُحفظ على هذا الجهاز.',
  absSelectedFile: (n) => `الملف المختار: ${n}`,
  absFrom: 'غير لائق للعمل من',
  absTo: 'حتى',
  absEpisode: 'فترة الغياب',
  absEpisodeNone: 'غير متأكد / غير مدرجة',
  absUpload: 'إرسال الشهادة',
  absUploadedOk: 'شكرًا لك – تم استلام شهادتك وسيتم التحقق منها.',
  absCertificates: 'شهاداتي',
  absCertificatesEmpty: 'لم ترسل أي شهادات بعد.',
  certStatus: { RECEIVED: 'تم الاستلام – قيد التحقق', VALID: 'صالحة', INVALID: 'غير مقبولة' },
  certStatusHint: {
    RECEIVED: 'ستتحقق منها الصحة المهنية قريبًا.',
    VALID: 'تم قبول شهادتك.',
    INVALID: 'تعذّر قبول شهادتك. ستتواصل معك الصحة المهنية.',
  },
  certReceived: (d) => `أُرسلت في ${d}`,

  advTitle: 'تقارير المشورة لمديرك أو للموارد البشرية',
  advIntro: 'طلب مديرك أو الموارد البشرية مشورة من الصحة المهنية بشأن عملك. قبل مشاركة أي شيء، يمكنك قراءة التقرير وإضافة تعليق وتقرير ما إذا كان سيُرسل.',
  advEmpty: 'لا توجد تقارير مشورة.',
  advEmptyHint: 'إذا طلب مديرك أو الموارد البشرية مشورة من الصحة المهنية بشأنك، فسيُعرض عليك التقرير هنا أولًا.',
  advStatus: { SUBMITTED: 'مطلوب', ACCEPTED: 'قيد الإعداد', REPORT_DRAFTED: 'قيد الإعداد', AWAITING_EMPLOYEE: 'بانتظار قرارك', RELEASED: 'تم الإرسال', WITHHELD: 'لم يُرسل', WITHDRAWN: 'تم السحب' },
  advRole: { MANAGER: 'مديرك', HR: 'الموارد البشرية' },
  advRequestedBy: (r) => `بطلب من ${r}`,
  advFitness: 'اللياقة للعمل',
  fitness: { FIT: 'لائق للعمل', FIT_WITH_ADJUSTMENTS: 'لائق مع تعديلات', TEMPORARILY_UNFIT: 'غير لائق للعمل مؤقتًا', UNFIT_LONG_TERM: 'غير لائق للعمل على المدى الطويل' },
  advAdjustments: 'التعديلات الموصى بها',
  advRestrictions: 'قيود العمل',
  advPrognosis: 'العودة المتوقعة',
  prognosis: {
    RTW_WITHIN_2_WEEKS: 'العودة إلى العمل متوقعة خلال أسبوعين',
    RTW_WITHIN_4_WEEKS: 'العودة إلى العمل متوقعة خلال 4 أسابيع',
    RTW_WITHIN_12_WEEKS: 'العودة إلى العمل متوقعة خلال 12 أسبوعًا',
    UNCERTAIN: 'غير واضح بعد',
    RTW_UNLIKELY: 'العودة إلى الدور الحالي غير مرجحة',
  },
  advExpectedReturn: 'تاريخ العودة المتوقع',
  advReviewDate: 'المراجعة التالية',
  advAnswers: 'الإجابات على الأسئلة المطروحة',
  advNone: 'لا يوجد',
  advWhatSeesTitle: (r) => `ما الذي سيراه ${r}`,
  advSeesConsent: (r) => `إذا وافقت، يتلقى ${r} هذا التقرير كما هو تمامًا – اللياقة للعمل والتعديلات والقيود والعودة المتوقعة والإجابات أعلاه – مع تعليقك.`,
  advSeesDecline: (r) => `إذا لم توافق، فلن يرى ${r} سوى أن التقرير لم يُرسل. يمكنك مناقشة نسخة جديدة مع الصحة المهنية.`,
  advNeverSees: 'لن يرى أبدًا أي تشخيص أو تاريخك الطبي أو أي شيء أخبرت به الصحة المهنية بسرية.',
  advChoice: 'قرارك',
  advConsent: 'أوافق على إرسال هذا التقرير',
  advDecline: 'لا أوافق',
  advComment: 'تعليق لمديرك أو للموارد البشرية',
  advCommentHint: 'يُعرض مع التقرير إذا وافقت. يرجى عدم ذكر تفاصيل طبية.',
  advDeclineReason: 'لماذا لا توافق؟',
  advDeclineReasonHint: 'لا تطّلع عليه سوى الصحة المهنية، للمساعدة في إعداد نسخة جديدة.',
  advSubmit: 'متابعة',
  advConfirmConsentTitle: 'هل تريد إرسال هذا التقرير؟',
  advConfirmDeclineTitle: 'هل تريد عدم إرسال هذا التقرير؟',
  advConfirmConsent: (r) => `سيُرسل التقرير وتعليقك إلى ${r}. لا يمكن التراجع عن ذلك.`,
  advConfirmDecline: (r) => `لن يرى ${r} سوى أن التقرير لم يُرسل.`,
  advConfirmYes: 'نعم، تأكيد',
  advConsentedOk: 'شكرًا لك – تم إرسال التقرير.',
  advDeclinedOk: 'تم تسجيل قرارك – لم يُرسل التقرير.',
  advYourComment: 'تعليقك',
  advReleasedOn: (d) => `أُرسل في ${d}`,
  advWithheld: 'قررت عدم إرسال هذا التقرير.',
  advOtherTitle: 'مشورة من الصحة المهنية',

  survTitle: 'فحوصاتي الصحية',
  survIntro: 'الفحوصات الصحية والتطعيمات المطلوبة لوظيفتك. لا يرى مديرك سوى ما إذا كنت ملتزمًا بالمواعيد – وليس النتائج أبدًا.',
  survProgrammes: 'برامج الفحص الصحي',
  survProgrammesEmpty: 'لا تتطلب وظيفتك حاليًا أي فحوصات صحية دورية.',
  survStatus: { CURRENT: 'محدّث', DUE: 'مستحق قريبًا', OVERDUE: 'متأخر', SCHEDULED: 'محجوز', EXEMPT: 'معفى' },
  survNextDue: 'الاستحقاق التالي',
  survAppointment: (w) => `الموعد: ${w}`,
  survBookHint: 'ستتلقى دعوة. يمكنك أيضًا رؤية حجوزاتك في «مواعيدي».',
  survImms: 'التطعيمات',
  survImmsEmpty: 'لا تتطلب وظيفتك أي تطعيمات.',
  immStatus: { COMPLETE: 'مكتمل', DUE: 'مستحق', OVERDUE: 'متأخر', TITRE_NEEDED: 'يلزم فحص دم' },
  immVaccine: 'اللقاح',
  immLastDose: 'آخر جرعة',
  immNextDue: 'الاستحقاق التالي',
  immDoses: (g, r) => `${g} من ${r} جرعات`,
  survCertTitle: 'شهادة التطعيم',
  survCertIntro: 'احصل على شهادة موقّعة بتطعيماتك، مثلًا للسفر. ستظهر في «مستنداتي».',
  survCertRequest: 'إنشاء شهادة التطعيم',
  survCertOk: 'شهادة التطعيم جاهزة في «مستنداتي».',
  survCertOpen: 'الانتقال إلى مستنداتي',
  crewTitle: 'الشهادة الطبية للطاقم',
  crewStatus: { VALID: 'صالحة', EXPIRING: 'تنتهي قريبًا', EXPIRED: 'منتهية', SUSPENDED: 'معلّقة' },
  crewValidUntil: (d) => `صالحة حتى ${d}`,
  pregTitle: 'إقرار الحمل (للطاقم)',
  pregIntro: 'إذا كنتِ حاملًا، يرجى إبلاغ فريق طب الطيران لمراجعة جرعة الإشعاع الكوني ومهامك معك.',
  pregWho: 'لا يطّلع على ذلك سوى طبيب الطيران المعتمد (AME) وكبير الأطباء. لا يتم إبلاغ جدولة الطاقم أو مديرك.',
  pregStart: 'تقديم إقرار الحمل',
  pregDate: 'تاريخ الإقرار',
  pregSubmit: 'إرسال الإقرار',
  pregOk: 'شكرًا لكِ – سيتواصل معكِ فريق طب الطيران.',

  preTitle: 'التقييم الصحي قبل التوظيف',
  preIntro: 'قبل أن تبدأ، تتحقق الصحة المهنية من أن الوظيفة آمنة لك. إجاباتك سرية لدى الصحة المهنية – ولا تتلقى الموارد البشرية سوى النتيجة (مثل «لائق»).',
  preNone: 'لم يتم العثور على تقييم ما قبل التوظيف.',
  preNoneHint: 'إذا عُرضت عليك وظيفة، فستتلقى دعوة عبر البريد الإلكتروني.',
  preOffer: 'مرجع العرض',
  preJob: 'الوظيفة',
  preProposedStart: 'تاريخ البدء المقترح',
  preStatusLabel: 'الحالة',
  preStatus: { INVITED: 'مدعو', QUESTIONNAIRE_DONE: 'تم إكمال الاستبيان', BOOKED: 'تم حجز التقييم', EXAMINED: 'تم التقييم', OUTCOME_RECORDED: 'أُرسلت النتيجة إلى الموارد البشرية', WITHDRAWN: 'تم السحب' },
  preProgress: (d, t) => `تم إنجاز ${d} من ${t} خطوات`,
  preDone: 'تم',
  preTodo: 'مطلوب',
  preStepQuestionnaire: '1. الاستبيان الصحي',
  preStepConsent: '2. الموافقة',
  preStepDocuments: '3. المستندات',
  preStepBook: '4. احجز تقييمك',
  preQIntro: 'يرجى الإجابة بصدق. لا يقرأ إجاباتك سوى أطباء الصحة المهنية.',
  preQSubmit: 'إرسال الإجابات',
  preQOk: 'شكرًا لك – تم إرسال إجاباتك.',
  preQDone: 'لقد أكملت الاستبيان.',
  preQEmpty: 'لا توجد أسئلة لهذه الوظيفة.',
  preConsentIntro: 'نحتاج إلى موافقتك على:',
  prePurposes: {
    PRE_EMPLOYMENT_ASSESSMENT: 'إجراء تقييمك الصحي قبل التوظيف، و',
    HR_STATUS_FEED: 'إبلاغ الموارد البشرية بالنتيجة فقط (لائق / لائق مع تعديلات / غير لائق) – وليس إجاباتك أو نتائجك أبدًا.',
  },
  preConsentCheckbox: 'أمنح موافقتي',
  preConsentSubmit: 'حفظ الموافقة',
  preConsentOk: 'شكرًا لك – تم تسجيل موافقتك.',
  preConsentDone: 'لقد منحت موافقتك.',
  preDocsIntro: 'حمّل المستندات التي طلبتها الصحة المهنية، مثل سجلات التطعيم أو تقارير طبيبك (اختياري).',
  preDocTitle: 'اسم المستند',
  preDocUpload: 'تحميل المستند',
  preDocOk: 'تم تحميل المستند.',
  preDocsList: 'المستندات المحمّلة',
  preBookIntro: 'اختر المكان والتاريخ الذي يمكنك الحضور ابتداءً منه؛ وسنحجز أول تقييم متاح.',
  preBookNeeds: 'يرجى إكمال الاستبيان ومنح الموافقة أولًا.',
  preSite: 'العيادة',
  preSiteAny: 'أي عيادة',
  preEarliest: 'أقرب تاريخ',
  preBookSubmit: 'حجز التقييم',
  preBookedOk: (w) => `تم حجز تقييمك في ${w}.`,
  preAppointment: 'تقييمك',
  preWhatHrSees: 'لا ترى الموارد البشرية سوى حالة تقييمك ونتيجته.',
};

const OH: Record<string, OhStrings> = { en, fr, ar };

export function ohStrings(code: string | undefined): OhStrings {
  return OH[baseLang(code)] ?? en;
}

/** Localised error text; a 422 `clinical_content…` gets a specific message. Never shows problem `detail`. */
export function ohErrorText(lang: string, e: unknown): string {
  if (e instanceof ApiError && e.status === 422 && e.problem?.code?.startsWith('clinical_content')) return ohStrings(lang).clinicalContent;
  return errorText(portalStrings(lang), e);
}
