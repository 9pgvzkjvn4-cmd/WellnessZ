import { ApiError } from '../auth/AuthClient';
import { errorText, portalStrings } from './portal';
import { baseLang } from './strings';

/**
 * UI strings of the increment-4 screens (my treatment plan, telehealth consultations) in English, Arabic and French.
 * Unknown languages fall back to English.
 */
export interface CareStrings {
  nav: { treatment: string; telehealth: string };
  tilesTitle: string;
  tileTreatment: string;
  tileTelehealth: string;
  clinicalContent: string;
  consentRequired: string;
  sending: string;
  back: string;
  opensNewTab: string;
  refresh: string;
  optional: string;

  /* Treatment plan */
  trTitle: string;
  trIntro: string;
  trPrivacy: string;
  trEmpty: string;
  trEmptyHint: string;
  planStatus: Record<string, string>;
  trPlanFor: (ref: string) => string;
  trExpectedRecovery: string;
  trGoals: string;
  trGoalsEmpty: string;
  trInterventions: string;
  trInterventionsEmpty: string;
  interventionTypes: Record<string, string>;
  trHowOften: string;
  trPeriod: string;
  trWithProvider: string;
  trFollowUps: string;
  trFollowUpsEmpty: string;
  followUpTypes: Record<string, string>;
  followUpStatus: Record<string, string>;
  trDue: string;
  trBooked: string;
  trConfirm: string;
  trDecline: string;
  trRespondHint: string;
  trDeclineTitle: string;
  trDeclineReason: string;
  trDeclineReasonHint: string;
  trDeclineConfirm: string;
  trConfirmedOk: string;
  trDeclinedOk: string;
  trYouConfirmed: string;
  trYouDeclined: string;
  trExternal: string;
  trExternalIntro: string;
  trExternalEmpty: string;
  trWhen: string;
  trWhere: string;
  trService: string;
  trInstructions: string;
  trRef: string;
  referralStatus: Record<string, string>;

  /* Telehealth */
  thTitle: string;
  thIntro: string;
  thEmpty: string;
  thEmptyHint: string;
  thUpcoming: string;
  thPast: string;
  sessionStatus: Record<string, string>;
  mode: Record<string, string>;
  thWhen: string;
  thDuration: (minutes: number) => string;
  thHow: string;
  thWith: string;
  thOhClinician: string;
  thPartner: (name: string) => string;
  thLayover: (city: string) => string;
  thLayoverNoCity: string;
  thLayoverHint: string;
  thConsentTitle: string;
  thConsentIntro: string;
  thRecordedTitle: string;
  thRecAttendance: string;
  thRecChat: string;
  thRecNote: string;
  thRecNoVideo: string;
  thNoteOhOnly: string;
  thPartnerSees: string;
  thWithdraw: string;
  thConsentCheckbox: string;
  thConsentGive: string;
  thConsentOk: string;
  thConsentGiven: string;
  thJoin: string;
  thJoinBefore: (when: string) => string;
  thJoinEnded: string;
  thJoinNeedsConsent: string;
  thOpenRoom: string;
  thLinkReady: string;
  thLinkExpires: (time: string) => string;
  thChatMode: string;

  /* Secure chat */
  chTitle: string;
  chIntro: string;
  chEmergency: string;
  chEmpty: string;
  chYou: string;
  chOh: string;
  chMessage: string;
  chCount: (used: number, max: number) => string;
  chSend: string;
  chReadOnly: string;
  chShow: string;
  chHide: string;
}

const en: CareStrings = {
  nav: { treatment: 'Treatment', telehealth: 'Telehealth' },
  tilesTitle: 'Treatment and consultations',
  tileTreatment: 'My treatment plan',
  tileTelehealth: 'Video & chat consultations',
  clinicalContent: 'Please do not include health or medical details in your reason – just tell us why the date does not suit you (for example a roster clash).',
  consentRequired: 'Please give your consent for this consultation first.',
  sending: 'Sending…',
  back: 'Back',
  opensNewTab: '(opens in a new tab)',
  refresh: 'Refresh',
  optional: '(optional)',

  trTitle: 'My treatment plan',
  trIntro: 'The plan you agreed with Occupational Health: what we are aiming for, what helps you get there and when we check in with you.',
  trPrivacy: 'Your treatment plan is confidential to Occupational Health and the providers treating you. Your manager and HR never see it.',
  trEmpty: 'You have no treatment plan.',
  trEmptyHint: 'If Occupational Health sets up a treatment plan with you, it will appear here.',
  planStatus: { ACTIVE: 'Current plan', COMPLETED: 'Completed', CANCELLED: 'Stopped' },
  trPlanFor: (r) => `For case ${r}`,
  trExpectedRecovery: 'We expect you to be recovered by',
  trGoals: 'What we are aiming for',
  trGoalsEmpty: 'No goals recorded yet.',
  trInterventions: 'What will help',
  trInterventionsEmpty: 'Nothing planned yet.',
  interventionTypes: {
    PHYSIOTHERAPY: 'Physiotherapy',
    MEDICATION: 'Medication',
    SPECIALIST_REVIEW: 'Specialist appointment',
    IMAGING: 'Scan or X-ray',
    SELF_CARE: 'Things you can do yourself',
    PSYCHOLOGICAL_THERAPY: 'Talking therapy',
    OH_REVIEW: 'Occupational Health review',
    OTHER: 'Other support',
  },
  trHowOften: 'How often',
  trPeriod: 'When',
  trWithProvider: 'With',
  trFollowUps: 'Check-ins with Occupational Health',
  trFollowUpsEmpty: 'No check-ins planned.',
  followUpTypes: { OH_REVIEW: 'Review with Occupational Health', PHONE: 'Phone call', PHYSIOTHERAPY: 'Physiotherapy check', SPECIALIST: 'Specialist check', TELEHEALTH: 'Video consultation' },
  followUpStatus: { PLANNED: 'Planned', BOOKED: 'Booked', CONFIRMED: 'Confirmed', DECLINED: 'Declined', DONE: 'Done', MISSED: 'Missed', CANCELLED: 'Cancelled' },
  trDue: 'Due by',
  trBooked: 'Appointment',
  trConfirm: 'I can attend',
  trDecline: 'This does not suit me',
  trRespondHint: 'Let Occupational Health know whether this works for you.',
  trDeclineTitle: 'Tell us this check-in does not suit you?',
  trDeclineReason: 'Reason (optional – for example a roster clash; please no health details)',
  trDeclineReasonHint: 'Occupational Health will contact you to find another time.',
  trDeclineConfirm: 'Send',
  trConfirmedOk: 'Thank you – you confirmed this check-in.',
  trDeclinedOk: 'Thank you – Occupational Health will find another time with you.',
  trYouConfirmed: 'You confirmed this check-in.',
  trYouDeclined: 'You said this check-in does not suit you.',
  trExternal: 'Appointments with other providers',
  trExternalIntro: 'Appointments a partner hospital, clinic or therapist has booked for you after a referral from Occupational Health.',
  trExternalEmpty: 'No appointments with other providers.',
  trWhen: 'When',
  trWhere: 'Where',
  trService: 'For',
  trInstructions: 'Instructions from the provider',
  trRef: 'Reference',
  referralStatus: { SENT: 'Referral sent', ACCEPTED: 'Accepted', SCHEDULED: 'Booked', SEEN: 'Attended', REPORT_RECEIVED: 'Attended', CLOSED: 'Closed', CANCELLED: 'Cancelled', DECLINED: 'Declined by the provider' },

  thTitle: 'Video and chat consultations',
  thIntro: 'Consultations with an Occupational Health clinician or a partner specialist by video or secure chat – also while you are away on a layover.',
  thEmpty: 'You have no consultations.',
  thEmptyHint: 'When Occupational Health books a video or chat consultation for you, it will appear here and you will get an invitation.',
  thUpcoming: 'Upcoming',
  thPast: 'Past and cancelled',
  sessionStatus: { SCHEDULED: 'Booked', IN_PROGRESS: 'In progress', COMPLETED: 'Completed', CANCELLED: 'Cancelled', NO_SHOW: 'Missed' },
  mode: { VIDEO: 'Video call', CHAT: 'Secure chat' },
  thWhen: 'When (your current time zone)',
  thDuration: (m) => `${m} minutes`,
  thHow: 'How',
  thWith: 'With',
  thOhClinician: 'Occupational Health clinician',
  thPartner: (n) => `Partner specialist: ${n}`,
  thLayover: (c) => `Crew layover consultation – ${c}`,
  thLayoverNoCity: 'Crew layover consultation',
  thLayoverHint: 'Times are shown in the time zone your phone is set to. Find a quiet, private place and use Wi-Fi if you can.',
  thConsentTitle: 'Your consent for this consultation',
  thConsentIntro: 'Before you can join, please read what happens with your information.',
  thRecordedTitle: 'What is recorded',
  thRecAttendance: 'That the consultation took place: date, time, how long and who took part.',
  thRecChat: 'Messages in the secure chat – stored encrypted.',
  thRecNote: 'The clinician’s consultation note.',
  thRecNoVideo: 'The video and sound of the call are not stored.',
  thNoteOhOnly: 'The consultation note goes into your confidential Occupational Health record only. Your manager, HR and crew scheduling never see it.',
  thPartnerSees: 'If a partner specialist holds the consultation, they see only what is needed for this consultation.',
  thWithdraw: 'You can withdraw your consent at any time under “My consents”.',
  thConsentCheckbox: 'I have read this and agree to this consultation',
  thConsentGive: 'Give consent',
  thConsentOk: 'Thank you – your consent has been recorded.',
  thConsentGiven: 'Consent given',
  thJoin: 'Join consultation',
  thJoinBefore: (w) => `You can join from 15 minutes before the start (${w}).`,
  thJoinEnded: 'This consultation has ended.',
  thJoinNeedsConsent: 'Please give your consent first.',
  thOpenRoom: 'Open the consultation room',
  thLinkReady: 'Your personal link is ready. It works once.',
  thLinkExpires: (t) => `Valid until ${t}`,
  thChatMode: 'This consultation takes place in the secure chat below.',

  chTitle: 'Secure chat',
  chIntro: 'Messages are encrypted and seen only by you and the clinician or specialist of this consultation.',
  chEmergency: 'Not for emergencies – in an emergency call the local emergency number.',
  chEmpty: 'No messages yet.',
  chYou: 'You',
  chOh: 'Clinician',
  chMessage: 'Your message',
  chCount: (u, m) => `${u} / ${m}`,
  chSend: 'Send message',
  chReadOnly: 'This consultation is closed – you can read the messages but not send new ones.',
  chShow: 'Open secure chat',
  chHide: 'Close chat',
};

const fr: CareStrings = {
  nav: { treatment: 'Traitement', telehealth: 'Télésanté' },
  tilesTitle: 'Traitement et consultations',
  tileTreatment: 'Mon plan de traitement',
  tileTelehealth: 'Consultations vidéo et chat',
  clinicalContent: "Merci de ne pas indiquer de détails médicaux dans votre motif – dites-nous simplement pourquoi la date ne vous convient pas (par exemple un conflit de planning).",
  consentRequired: "Veuillez d'abord donner votre consentement pour cette consultation.",
  sending: 'Envoi…',
  back: 'Retour',
  opensNewTab: '(s’ouvre dans un nouvel onglet)',
  refresh: 'Actualiser',
  optional: '(facultatif)',

  trTitle: 'Mon plan de traitement',
  trIntro: 'Le plan convenu avec la santé au travail : nos objectifs, ce qui vous aide à les atteindre et quand nous faisons le point avec vous.',
  trPrivacy: 'Votre plan de traitement est confidentiel : seuls la santé au travail et les prestataires qui vous soignent le voient. Votre manager et les RH ne le voient jamais.',
  trEmpty: "Vous n'avez pas de plan de traitement.",
  trEmptyHint: 'Si la santé au travail établit un plan de traitement avec vous, il apparaîtra ici.',
  planStatus: { ACTIVE: 'Plan en cours', COMPLETED: 'Terminé', CANCELLED: 'Arrêté' },
  trPlanFor: (r) => `Dossier ${r}`,
  trExpectedRecovery: 'Rétablissement prévu le',
  trGoals: 'Nos objectifs',
  trGoalsEmpty: 'Aucun objectif enregistré pour le moment.',
  trInterventions: 'Ce qui va vous aider',
  trInterventionsEmpty: 'Rien de prévu pour le moment.',
  interventionTypes: {
    PHYSIOTHERAPY: 'Kinésithérapie',
    MEDICATION: 'Médicaments',
    SPECIALIST_REVIEW: 'Rendez-vous chez un spécialiste',
    IMAGING: 'Radiographie ou examen d’imagerie',
    SELF_CARE: 'Ce que vous pouvez faire vous-même',
    PSYCHOLOGICAL_THERAPY: 'Thérapie par la parole',
    OH_REVIEW: 'Point avec la santé au travail',
    OTHER: 'Autre accompagnement',
  },
  trHowOften: 'Fréquence',
  trPeriod: 'Quand',
  trWithProvider: 'Avec',
  trFollowUps: 'Points de suivi avec la santé au travail',
  trFollowUpsEmpty: 'Aucun point de suivi prévu.',
  followUpTypes: { OH_REVIEW: 'Point avec la santé au travail', PHONE: 'Appel téléphonique', PHYSIOTHERAPY: 'Contrôle kinésithérapie', SPECIALIST: 'Contrôle chez le spécialiste', TELEHEALTH: 'Consultation vidéo' },
  followUpStatus: { PLANNED: 'Prévu', BOOKED: 'Réservé', CONFIRMED: 'Confirmé', DECLINED: 'Refusé', DONE: 'Effectué', MISSED: 'Manqué', CANCELLED: 'Annulé' },
  trDue: 'À faire avant le',
  trBooked: 'Rendez-vous',
  trConfirm: 'Je serai présent(e)',
  trDecline: 'Cela ne me convient pas',
  trRespondHint: 'Indiquez à la santé au travail si cela vous convient.',
  trDeclineTitle: 'Signaler que ce point de suivi ne vous convient pas ?',
  trDeclineReason: 'Motif (facultatif – par exemple un conflit de planning ; pas de détails médicaux)',
  trDeclineReasonHint: 'La santé au travail vous contactera pour trouver un autre moment.',
  trDeclineConfirm: 'Envoyer',
  trConfirmedOk: 'Merci – vous avez confirmé ce point de suivi.',
  trDeclinedOk: 'Merci – la santé au travail trouvera un autre moment avec vous.',
  trYouConfirmed: 'Vous avez confirmé ce point de suivi.',
  trYouDeclined: 'Vous avez indiqué que ce point de suivi ne vous convient pas.',
  trExternal: 'Rendez-vous chez d’autres prestataires',
  trExternalIntro: 'Rendez-vous fixés pour vous par un hôpital, une clinique ou un thérapeute partenaire après une orientation par la santé au travail.',
  trExternalEmpty: 'Aucun rendez-vous chez d’autres prestataires.',
  trWhen: 'Quand',
  trWhere: 'Où',
  trService: 'Pour',
  trInstructions: 'Consignes du prestataire',
  trRef: 'Référence',
  referralStatus: { SENT: 'Orientation envoyée', ACCEPTED: 'Acceptée', SCHEDULED: 'Réservé', SEEN: 'Effectué', REPORT_RECEIVED: 'Effectué', CLOSED: 'Clôturé', CANCELLED: 'Annulé', DECLINED: 'Refusée par le prestataire' },

  thTitle: 'Consultations vidéo et chat',
  thIntro: 'Consultations avec un clinicien de santé au travail ou un spécialiste partenaire, par vidéo ou chat sécurisé – y compris pendant une escale.',
  thEmpty: "Vous n'avez aucune consultation.",
  thEmptyHint: 'Lorsque la santé au travail réserve une consultation vidéo ou chat pour vous, elle apparaîtra ici et vous recevrez une invitation.',
  thUpcoming: 'À venir',
  thPast: 'Passées et annulées',
  sessionStatus: { SCHEDULED: 'Réservée', IN_PROGRESS: 'En cours', COMPLETED: 'Terminée', CANCELLED: 'Annulée', NO_SHOW: 'Manquée' },
  mode: { VIDEO: 'Appel vidéo', CHAT: 'Chat sécurisé' },
  thWhen: 'Quand (votre fuseau horaire actuel)',
  thDuration: (m) => `${m} minutes`,
  thHow: 'Comment',
  thWith: 'Avec',
  thOhClinician: 'Clinicien de santé au travail',
  thPartner: (n) => `Spécialiste partenaire : ${n}`,
  thLayover: (c) => `Consultation pendant une escale – ${c}`,
  thLayoverNoCity: 'Consultation pendant une escale',
  thLayoverHint: 'Les horaires sont affichés dans le fuseau horaire de votre téléphone. Installez-vous dans un endroit calme et privé et utilisez le Wi-Fi si possible.',
  thConsentTitle: 'Votre consentement pour cette consultation',
  thConsentIntro: 'Avant de rejoindre la consultation, lisez ce qu’il advient de vos informations.',
  thRecordedTitle: 'Ce qui est enregistré',
  thRecAttendance: 'Le fait que la consultation a eu lieu : date, heure, durée et participants.',
  thRecChat: 'Les messages du chat sécurisé – conservés chiffrés.',
  thRecNote: 'La note de consultation du clinicien.',
  thRecNoVideo: "L'image et le son de l'appel ne sont pas conservés.",
  thNoteOhOnly: 'La note de consultation est versée uniquement dans votre dossier confidentiel de santé au travail. Votre manager, les RH et la planification des équipages ne la voient jamais.',
  thPartnerSees: 'Si un spécialiste partenaire mène la consultation, il ne voit que ce qui est nécessaire pour cette consultation.',
  thWithdraw: 'Vous pouvez retirer votre consentement à tout moment dans « Mes consentements ».',
  thConsentCheckbox: "J'ai lu ces informations et j'accepte cette consultation",
  thConsentGive: 'Donner mon consentement',
  thConsentOk: 'Merci – votre consentement a été enregistré.',
  thConsentGiven: 'Consentement donné',
  thJoin: 'Rejoindre la consultation',
  thJoinBefore: (w) => `Vous pourrez la rejoindre 15 minutes avant le début (${w}).`,
  thJoinEnded: 'Cette consultation est terminée.',
  thJoinNeedsConsent: "Veuillez d'abord donner votre consentement.",
  thOpenRoom: 'Ouvrir la salle de consultation',
  thLinkReady: 'Votre lien personnel est prêt. Il ne fonctionne qu’une fois.',
  thLinkExpires: (t) => `Valable jusqu’à ${t}`,
  thChatMode: 'Cette consultation a lieu dans le chat sécurisé ci-dessous.',

  chTitle: 'Chat sécurisé',
  chIntro: 'Les messages sont chiffrés et visibles uniquement par vous et le clinicien ou spécialiste de cette consultation.',
  chEmergency: "Pas pour les urgences – en cas d'urgence, appelez le numéro d'urgence local.",
  chEmpty: 'Aucun message pour le moment.',
  chYou: 'Vous',
  chOh: 'Clinicien',
  chMessage: 'Votre message',
  chCount: (u, m) => `${u} / ${m}`,
  chSend: 'Envoyer le message',
  chReadOnly: 'Cette consultation est close – vous pouvez lire les messages mais pas en envoyer.',
  chShow: 'Ouvrir le chat sécurisé',
  chHide: 'Fermer le chat',
};

const ar: CareStrings = {
  nav: { treatment: 'العلاج', telehealth: 'الرعاية عن بُعد' },
  tilesTitle: 'العلاج والاستشارات',
  tileTreatment: 'خطة علاجي',
  tileTelehealth: 'استشارات الفيديو والمحادثة',
  clinicalContent: 'يرجى عدم ذكر تفاصيل صحية أو طبية في السبب – أخبرنا فقط لماذا لا يناسبك الموعد (مثل تعارض مع جدول المناوبات).',
  consentRequired: 'يرجى منح موافقتك على هذه الاستشارة أولًا.',
  sending: 'جارٍ الإرسال…',
  back: 'رجوع',
  opensNewTab: '(يُفتح في علامة تبويب جديدة)',
  refresh: 'تحديث',
  optional: '(اختياري)',

  trTitle: 'خطة علاجي',
  trIntro: 'الخطة التي اتفقت عليها مع الصحة المهنية: ما نهدف إليه، وما يساعدك على الوصول إليه، ومتى نتابع معك.',
  trPrivacy: 'خطة علاجك سرية لدى الصحة المهنية ومقدمي الرعاية الذين يعالجونك. لا يراها مديرك ولا الموارد البشرية أبدًا.',
  trEmpty: 'ليست لديك خطة علاج.',
  trEmptyHint: 'إذا وضعت الصحة المهنية خطة علاج معك، فستظهر هنا.',
  planStatus: { ACTIVE: 'الخطة الحالية', COMPLETED: 'مكتملة', CANCELLED: 'متوقفة' },
  trPlanFor: (r) => `للحالة ${r}`,
  trExpectedRecovery: 'نتوقع تعافيك بحلول',
  trGoals: 'ما نهدف إليه',
  trGoalsEmpty: 'لم تُسجَّل أهداف بعد.',
  trInterventions: 'ما سيساعدك',
  trInterventionsEmpty: 'لا شيء مخطط بعد.',
  interventionTypes: {
    PHYSIOTHERAPY: 'العلاج الطبيعي',
    MEDICATION: 'الأدوية',
    SPECIALIST_REVIEW: 'موعد مع أخصائي',
    IMAGING: 'أشعة أو تصوير',
    SELF_CARE: 'ما يمكنك فعله بنفسك',
    PSYCHOLOGICAL_THERAPY: 'العلاج بالحديث',
    OH_REVIEW: 'مراجعة مع الصحة المهنية',
    OTHER: 'دعم آخر',
  },
  trHowOften: 'كم مرة',
  trPeriod: 'متى',
  trWithProvider: 'مع',
  trFollowUps: 'مواعيد المتابعة مع الصحة المهنية',
  trFollowUpsEmpty: 'لا توجد مواعيد متابعة مخططة.',
  followUpTypes: { OH_REVIEW: 'مراجعة مع الصحة المهنية', PHONE: 'مكالمة هاتفية', PHYSIOTHERAPY: 'متابعة العلاج الطبيعي', SPECIALIST: 'متابعة مع أخصائي', TELEHEALTH: 'استشارة بالفيديو' },
  followUpStatus: { PLANNED: 'مخطط', BOOKED: 'محجوز', CONFIRMED: 'مؤكد', DECLINED: 'مرفوض', DONE: 'تم', MISSED: 'فائت', CANCELLED: 'ملغى' },
  trDue: 'مستحق بحلول',
  trBooked: 'الموعد',
  trConfirm: 'يمكنني الحضور',
  trDecline: 'هذا لا يناسبني',
  trRespondHint: 'أخبر الصحة المهنية إن كان هذا يناسبك.',
  trDeclineTitle: 'إبلاغنا بأن موعد المتابعة هذا لا يناسبك؟',
  trDeclineReason: 'السبب (اختياري – مثل تعارض مع جدول المناوبات؛ دون تفاصيل صحية)',
  trDeclineReasonHint: 'ستتواصل معك الصحة المهنية لإيجاد وقت آخر.',
  trDeclineConfirm: 'إرسال',
  trConfirmedOk: 'شكرًا لك – لقد أكدت موعد المتابعة هذا.',
  trDeclinedOk: 'شكرًا لك – ستجد الصحة المهنية وقتًا آخر معك.',
  trYouConfirmed: 'لقد أكدت موعد المتابعة هذا.',
  trYouDeclined: 'لقد أبلغت بأن موعد المتابعة هذا لا يناسبك.',
  trExternal: 'المواعيد مع مقدمي رعاية آخرين',
  trExternalIntro: 'مواعيد حجزها لك مستشفى أو عيادة أو معالج شريك بعد إحالة من الصحة المهنية.',
  trExternalEmpty: 'لا توجد مواعيد مع مقدمي رعاية آخرين.',
  trWhen: 'متى',
  trWhere: 'أين',
  trService: 'من أجل',
  trInstructions: 'تعليمات مقدم الرعاية',
  trRef: 'المرجع',
  referralStatus: { SENT: 'أُرسلت الإحالة', ACCEPTED: 'مقبولة', SCHEDULED: 'محجوز', SEEN: 'تم الحضور', REPORT_RECEIVED: 'تم الحضور', CLOSED: 'مغلقة', CANCELLED: 'ملغاة', DECLINED: 'رفضها مقدم الرعاية' },

  thTitle: 'استشارات الفيديو والمحادثة',
  thIntro: 'استشارات مع طبيب الصحة المهنية أو أخصائي شريك عبر الفيديو أو المحادثة الآمنة – حتى أثناء توقفك في محطة رحلة.',
  thEmpty: 'ليست لديك استشارات.',
  thEmptyHint: 'عندما تحجز الصحة المهنية استشارة فيديو أو محادثة لك، ستظهر هنا وستتلقى دعوة.',
  thUpcoming: 'القادمة',
  thPast: 'السابقة والملغاة',
  sessionStatus: { SCHEDULED: 'محجوزة', IN_PROGRESS: 'جارية', COMPLETED: 'مكتملة', CANCELLED: 'ملغاة', NO_SHOW: 'فائتة' },
  mode: { VIDEO: 'مكالمة فيديو', CHAT: 'محادثة آمنة' },
  thWhen: 'متى (بتوقيتك المحلي الحالي)',
  thDuration: (m) => `${m} دقيقة`,
  thHow: 'الطريقة',
  thWith: 'مع',
  thOhClinician: 'طبيب الصحة المهنية',
  thPartner: (n) => `أخصائي شريك: ${n}`,
  thLayover: (c) => `استشارة أثناء توقف الطاقم – ${c}`,
  thLayoverNoCity: 'استشارة أثناء توقف الطاقم',
  thLayoverHint: 'تُعرض الأوقات حسب المنطقة الزمنية المضبوطة في هاتفك. اختر مكانًا هادئًا وخاصًا واستخدم شبكة Wi-Fi إن أمكن.',
  thConsentTitle: 'موافقتك على هذه الاستشارة',
  thConsentIntro: 'قبل الانضمام، يرجى قراءة ما يحدث لمعلوماتك.',
  thRecordedTitle: 'ما يتم تسجيله',
  thRecAttendance: 'أن الاستشارة قد جرت: التاريخ والوقت والمدة والمشاركون.',
  thRecChat: 'رسائل المحادثة الآمنة – تُحفظ مشفرة.',
  thRecNote: 'ملاحظة الاستشارة التي يكتبها الطبيب.',
  thRecNoVideo: 'لا يتم حفظ صورة المكالمة أو صوتها.',
  thNoteOhOnly: 'تُحفظ ملاحظة الاستشارة في سجلك السري لدى الصحة المهنية فقط. لا يراها مديرك ولا الموارد البشرية ولا جدولة الطواقم أبدًا.',
  thPartnerSees: 'إذا أجرى الاستشارة أخصائي شريك، فلا يرى إلا ما يلزم لهذه الاستشارة.',
  thWithdraw: 'يمكنك سحب موافقتك في أي وقت من «موافقاتي».',
  thConsentCheckbox: 'قرأت هذه المعلومات وأوافق على هذه الاستشارة',
  thConsentGive: 'منح الموافقة',
  thConsentOk: 'شكرًا لك – تم تسجيل موافقتك.',
  thConsentGiven: 'تم منح الموافقة',
  thJoin: 'الانضمام إلى الاستشارة',
  thJoinBefore: (w) => `يمكنك الانضمام قبل البدء بـ 15 دقيقة (${w}).`,
  thJoinEnded: 'انتهت هذه الاستشارة.',
  thJoinNeedsConsent: 'يرجى منح موافقتك أولًا.',
  thOpenRoom: 'فتح غرفة الاستشارة',
  thLinkReady: 'رابطك الشخصي جاهز. يعمل مرة واحدة فقط.',
  thLinkExpires: (t) => `صالح حتى ${t}`,
  thChatMode: 'تجري هذه الاستشارة في المحادثة الآمنة أدناه.',

  chTitle: 'محادثة آمنة',
  chIntro: 'الرسائل مشفرة ولا يراها سواك وسوى الطبيب أو الأخصائي في هذه الاستشارة.',
  chEmergency: 'ليست للحالات الطارئة – في حالة الطوارئ اتصل برقم الطوارئ المحلي.',
  chEmpty: 'لا توجد رسائل بعد.',
  chYou: 'أنت',
  chOh: 'الطبيب',
  chMessage: 'رسالتك',
  chCount: (u, m) => `${u} / ${m}`,
  chSend: 'إرسال الرسالة',
  chReadOnly: 'هذه الاستشارة مغلقة – يمكنك قراءة الرسائل دون إرسال رسائل جديدة.',
  chShow: 'فتح المحادثة الآمنة',
  chHide: 'إغلاق المحادثة',
};

const CARE: Record<string, CareStrings> = { en, fr, ar };

export function careStrings(code: string | undefined): CareStrings {
  return CARE[baseLang(code)] ?? en;
}

/**
 * Localised error text. A 422 `clinical_content…` and a 422 `consent_required` get specific messages; the problem
 * `detail` (which may echo the rejected text) is never shown.
 */
export function careErrorText(lang: string, e: unknown): string {
  if (e instanceof ApiError && e.status === 422) {
    const code = e.problem?.code ?? '';
    if (code.startsWith('clinical_content')) return careStrings(lang).clinicalContent;
    if (code === 'consent_required') return careStrings(lang).consentRequired;
  }
  return errorText(portalStrings(lang), e);
}
