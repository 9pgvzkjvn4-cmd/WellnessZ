import type { GrantablePurpose, PreferredContact, ReferralCategory } from '../api/types';

/**
 * UI strings of the self-referral form in the languages offered by `GET /languages` (E01-BL-010).
 * Template labels come from the server in the requested language; these cover the fixed parts of the form.
 * Unknown languages fall back to English (same rule as the server).
 */
export interface ReferralStrings {
  title: string;
  intro: string;
  urgent: string;
  language: string;
  progress: (done: number, total: number) => string;
  stepAbout: string;
  stepWhere: string;
  stepDescribe: string;
  stepContact: string;
  stepAttach: string;
  template: string;
  templateHint: string;
  templateSuggested: string;
  chooseTemplate: string;
  category: string;
  categories: Record<ReferralCategory, string>;
  bodyMap: string;
  bodyMapHint: string;
  front: string;
  back: string;
  selectedAreas: string;
  noneSelected: string;
  regionList: string;
  symptoms: string;
  description: string;
  descriptionHint: string;
  charsLeft: (n: number) => string;
  yes: string;
  no: string;
  select: string;
  preferredContact: string;
  contacts: Record<PreferredContact, string>;
  consentLegend: string;
  consentAssessment: string;
  consentOptional: string;
  purposes: Record<GrantablePurpose, string>;
  attachments: string;
  attachmentsHint: string;
  choosePhotos: string;
  takePhoto: string;
  remove: string;
  submit: string;
  submitting: string;
  required: string;
  errorSummary: string;
  errRequired: string;
  errTooLong: (max: number) => string;
  errConsent: string;
  errCategory: string;
  errTemplate: string;
  errFileType: (name: string) => string;
  errFileSize: (name: string) => string;
  errFileCount: (max: number) => string;
  ackTitle: string;
  ackBody: (ref: string) => string;
  ackUploads: (ok: number, total: number) => string;
  ackNext: string;
  backHome: string;
  loading: string;
}

const en: ReferralStrings = {
  title: 'Contact Occupational Health',
  intro: 'Refer yourself to Occupational Health. Only OH staff can read what you write here. It takes about 2–3 minutes.',
  urgent: 'Emergency? Call your local emergency number or airside medical response now – do not use this form.',
  language: 'Language',
  progress: (d, t) => `${d} of ${t} required items completed`,
  stepAbout: '1. What is it about?',
  stepWhere: '2. Where and how does it affect you?',
  stepDescribe: '3. Tell us more',
  stepContact: '4. Contact and consent',
  stepAttach: '5. Photos or documents (optional)',
  template: 'Your area of work',
  templateHint: 'Choose the form that fits your work. The questions below change accordingly.',
  templateSuggested: 'Suggested for your department',
  chooseTemplate: 'Choose…',
  category: 'Type of concern',
  categories: {
    INJURY: 'Injury',
    ILLNESS: 'Illness',
    DECREASE_IN_FITNESS: 'Decrease in fitness',
    PSYCHOLOGICAL: 'Psychological wellbeing',
    SUBSTANCE: 'Alcohol or substance use',
    EXPOSURE: 'Workplace exposure (fumes, noise, chemicals)',
    OTHER: 'Other',
  },
  bodyMap: 'Affected body areas',
  bodyMapHint: 'Tap the body picture or use the buttons. Tap again to remove.',
  front: 'Front',
  back: 'Back',
  selectedAreas: 'Selected',
  noneSelected: 'None selected',
  regionList: 'Body areas',
  symptoms: 'Symptoms',
  description: 'Describe your concern',
  descriptionHint: "Please do not include other people's health information.",
  charsLeft: (n) => `${n} characters left`,
  yes: 'Yes',
  no: 'No',
  select: 'Select…',
  preferredContact: 'How should OH contact you?',
  contacts: { EMAIL: 'Email', PHONE: 'Phone', IN_APP: 'In the app' },
  consentLegend: 'Consent',
  consentAssessment:
    'I agree that Occupational Health may use this information to assess my referral and contact me (required).',
  consentOptional: 'Optional – you can change these any time under "My consents"',
  purposes: {
    TREATMENT_SHARING_PARTNER: 'Share treatment information with a partner provider',
    EAP_REFERRAL: 'Referral to the Employee Assistance Programme (EAP)',
    INSURER_SHARING: 'Share relevant information with the insurer',
    WEARABLE_DATA: 'Use data from my wearable device',
    HEALTH_CAMPAIGNS: 'Invitations to health campaigns',
    RESEARCH_DEIDENTIFIED: 'Use of de-identified data for research',
  },
  attachments: 'Attachments',
  attachmentsHint: 'JPEG, PNG or PDF, up to 5 MB each, at most 5 files. Files are sent after your referral and not kept on this device.',
  choosePhotos: 'Choose files',
  takePhoto: 'Take a photo',
  remove: 'Remove',
  submit: 'Send to Occupational Health',
  submitting: 'Sending…',
  required: 'required',
  errorSummary: 'Please check the following:',
  errRequired: 'This field is required.',
  errTooLong: (m) => `Please use at most ${m} characters.`,
  errConsent: 'Please confirm your consent so OH can assess your referral.',
  errCategory: 'Please choose the type of concern.',
  errTemplate: 'Please choose your area of work.',
  errFileType: (n) => `"${n}" is not a JPEG, PNG or PDF file.`,
  errFileSize: (n) => `"${n}" is larger than 5 MB.`,
  errFileCount: (m) => `You can attach at most ${m} files.`,
  ackTitle: 'Referral sent',
  ackBody: (ref) => `Thank you. Occupational Health has received your referral. Your case reference is ${ref}.`,
  ackUploads: (ok, total) => `${ok} of ${total} attachments uploaded.`,
  ackNext: 'An OH nurse will review it and contact you the way you asked. You can follow the status on the home screen.',
  backHome: 'Back to home',
  loading: 'Loading…',
};

const fr: ReferralStrings = {
  title: 'Contacter la Santé au travail',
  intro: "Adressez-vous à la Santé au travail. Seul le personnel SST peut lire ce que vous écrivez. Cela prend environ 2 à 3 minutes.",
  urgent: "Urgence ? Appelez le numéro d'urgence local ou l'assistance médicale côté piste – n'utilisez pas ce formulaire.",
  language: 'Langue',
  progress: (d, t) => `${d} sur ${t} éléments obligatoires remplis`,
  stepAbout: '1. De quoi s’agit-il ?',
  stepWhere: '2. Où et comment cela vous affecte-t-il ?',
  stepDescribe: '3. Dites-nous en plus',
  stepContact: '4. Contact et consentement',
  stepAttach: '5. Photos ou documents (facultatif)',
  template: 'Votre domaine de travail',
  templateHint: 'Choisissez le formulaire adapté à votre travail. Les questions ci-dessous changent en conséquence.',
  templateSuggested: 'Suggéré pour votre service',
  chooseTemplate: 'Choisir…',
  category: 'Type de problème',
  categories: {
    INJURY: 'Blessure',
    ILLNESS: 'Maladie',
    DECREASE_IN_FITNESS: 'Baisse d’aptitude',
    PSYCHOLOGICAL: 'Bien-être psychologique',
    SUBSTANCE: 'Alcool ou substances',
    EXPOSURE: 'Exposition professionnelle (fumées, bruit, produits chimiques)',
    OTHER: 'Autre',
  },
  bodyMap: 'Zones du corps concernées',
  bodyMapHint: 'Touchez le schéma du corps ou utilisez les boutons. Touchez à nouveau pour retirer.',
  front: 'Face',
  back: 'Dos',
  selectedAreas: 'Sélection',
  noneSelected: 'Aucune sélection',
  regionList: 'Zones du corps',
  symptoms: 'Symptômes',
  description: 'Décrivez votre problème',
  descriptionHint: "Merci de ne pas inclure d'informations de santé concernant d'autres personnes.",
  charsLeft: (n) => `${n} caractères restants`,
  yes: 'Oui',
  no: 'Non',
  select: 'Choisir…',
  preferredContact: 'Comment la SST doit-elle vous contacter ?',
  contacts: { EMAIL: 'E-mail', PHONE: 'Téléphone', IN_APP: "Dans l'application" },
  consentLegend: 'Consentement',
  consentAssessment:
    "J'accepte que la Santé au travail utilise ces informations pour évaluer ma demande et me contacter (obligatoire).",
  consentOptional: 'Facultatif – modifiable à tout moment dans « Mes consentements »',
  purposes: {
    TREATMENT_SHARING_PARTNER: 'Partager les informations de traitement avec un prestataire partenaire',
    EAP_REFERRAL: "Orientation vers le programme d'aide aux employés (PAE)",
    INSURER_SHARING: "Partager les informations pertinentes avec l'assureur",
    WEARABLE_DATA: 'Utiliser les données de mon objet connecté',
    HEALTH_CAMPAIGNS: 'Invitations aux campagnes de santé',
    RESEARCH_DEIDENTIFIED: 'Utilisation de données anonymisées pour la recherche',
  },
  attachments: 'Pièces jointes',
  attachmentsHint:
    "JPEG, PNG ou PDF, 5 Mo maximum chacun, 5 fichiers au plus. Les fichiers sont envoyés après votre demande et ne sont pas conservés sur l'appareil.",
  choosePhotos: 'Choisir des fichiers',
  takePhoto: 'Prendre une photo',
  remove: 'Retirer',
  submit: 'Envoyer à la Santé au travail',
  submitting: 'Envoi…',
  required: 'obligatoire',
  errorSummary: 'Veuillez vérifier les points suivants :',
  errRequired: 'Ce champ est obligatoire.',
  errTooLong: (m) => `Veuillez utiliser au plus ${m} caractères.`,
  errConsent: 'Veuillez confirmer votre consentement pour que la SST puisse évaluer votre demande.',
  errCategory: 'Veuillez choisir le type de problème.',
  errTemplate: 'Veuillez choisir votre domaine de travail.',
  errFileType: (n) => `« ${n} » n'est pas un fichier JPEG, PNG ou PDF.`,
  errFileSize: (n) => `« ${n} » dépasse 5 Mo.`,
  errFileCount: (m) => `Vous pouvez joindre au plus ${m} fichiers.`,
  ackTitle: 'Demande envoyée',
  ackBody: (ref) => `Merci. La Santé au travail a bien reçu votre demande. Votre référence de dossier est ${ref}.`,
  ackUploads: (ok, total) => `${ok} pièce(s) jointe(s) sur ${total} envoyée(s).`,
  ackNext: "Un(e) infirmier(ère) SST l'examinera et vous contactera comme demandé. Suivez le statut sur l'écran d'accueil.",
  backHome: "Retour à l'accueil",
  loading: 'Chargement…',
};

const ar: ReferralStrings = {
  title: 'التواصل مع الصحة المهنية',
  intro: 'أحِل نفسك إلى الصحة المهنية. لا يطّلع على ما تكتبه هنا سوى فريق الصحة المهنية. يستغرق ذلك نحو 2–3 دقائق.',
  urgent: 'حالة طارئة؟ اتصل برقم الطوارئ المحلي أو بالاستجابة الطبية في ساحة المطار الآن – لا تستخدم هذا النموذج.',
  language: 'اللغة',
  progress: (d, t) => `تم إكمال ${d} من ${t} من البنود المطلوبة`,
  stepAbout: '1. ما موضوع الإحالة؟',
  stepWhere: '2. أين وكيف يؤثر عليك؟',
  stepDescribe: '3. أخبرنا بالمزيد',
  stepContact: '4. التواصل والموافقة',
  stepAttach: '5. صور أو مستندات (اختياري)',
  template: 'مجال عملك',
  templateHint: 'اختر النموذج المناسب لعملك. تتغير الأسئلة أدناه وفقًا لذلك.',
  templateSuggested: 'مقترح لقسمك',
  chooseTemplate: 'اختر…',
  category: 'نوع المشكلة',
  categories: {
    INJURY: 'إصابة',
    ILLNESS: 'مرض',
    DECREASE_IN_FITNESS: 'انخفاض في اللياقة',
    PSYCHOLOGICAL: 'الصحة النفسية',
    SUBSTANCE: 'الكحول أو المواد',
    EXPOSURE: 'تعرّض في مكان العمل (أبخرة، ضوضاء، مواد كيميائية)',
    OTHER: 'أخرى',
  },
  bodyMap: 'مناطق الجسم المصابة',
  bodyMapHint: 'المس صورة الجسم أو استخدم الأزرار. المس مرة أخرى للإزالة.',
  front: 'الأمام',
  back: 'الخلف',
  selectedAreas: 'المحدد',
  noneSelected: 'لم يتم التحديد',
  regionList: 'مناطق الجسم',
  symptoms: 'الأعراض',
  description: 'صف مشكلتك',
  descriptionHint: 'يرجى عدم تضمين معلومات صحية عن أشخاص آخرين.',
  charsLeft: (n) => `متبقٍ ${n} حرفًا`,
  yes: 'نعم',
  no: 'لا',
  select: 'اختر…',
  preferredContact: 'كيف تفضّل أن تتواصل معك الصحة المهنية؟',
  contacts: { EMAIL: 'البريد الإلكتروني', PHONE: 'الهاتف', IN_APP: 'داخل التطبيق' },
  consentLegend: 'الموافقة',
  consentAssessment: 'أوافق على أن تستخدم الصحة المهنية هذه المعلومات لتقييم إحالتي والتواصل معي (مطلوب).',
  consentOptional: 'اختياري – يمكنك تغييرها في أي وقت من "موافقاتي"',
  purposes: {
    TREATMENT_SHARING_PARTNER: 'مشاركة معلومات العلاج مع مقدم خدمة شريك',
    EAP_REFERRAL: 'الإحالة إلى برنامج مساعدة الموظفين',
    INSURER_SHARING: 'مشاركة المعلومات ذات الصلة مع شركة التأمين',
    WEARABLE_DATA: 'استخدام بيانات جهازي القابل للارتداء',
    HEALTH_CAMPAIGNS: 'دعوات إلى الحملات الصحية',
    RESEARCH_DEIDENTIFIED: 'استخدام بيانات مجهولة الهوية لأغراض البحث',
  },
  attachments: 'المرفقات',
  attachmentsHint: 'JPEG أو PNG أو PDF، بحد أقصى 5 ميغابايت لكل ملف و5 ملفات. تُرسل الملفات بعد الإحالة ولا تُحفظ على هذا الجهاز.',
  choosePhotos: 'اختر الملفات',
  takePhoto: 'التقط صورة',
  remove: 'إزالة',
  submit: 'إرسال إلى الصحة المهنية',
  submitting: 'جارٍ الإرسال…',
  required: 'مطلوب',
  errorSummary: 'يرجى التحقق مما يلي:',
  errRequired: 'هذا الحقل مطلوب.',
  errTooLong: (m) => `يرجى استخدام ${m} حرفًا كحد أقصى.`,
  errConsent: 'يرجى تأكيد موافقتك حتى تتمكن الصحة المهنية من تقييم إحالتك.',
  errCategory: 'يرجى اختيار نوع المشكلة.',
  errTemplate: 'يرجى اختيار مجال عملك.',
  errFileType: (n) => `"${n}" ليس ملف JPEG أو PNG أو PDF.`,
  errFileSize: (n) => `"${n}" أكبر من 5 ميغابايت.`,
  errFileCount: (m) => `يمكنك إرفاق ${m} ملفات كحد أقصى.`,
  ackTitle: 'تم إرسال الإحالة',
  ackBody: (ref) => `شكرًا لك. استلمت الصحة المهنية إحالتك. الرقم المرجعي لحالتك هو ${ref}.`,
  ackUploads: (ok, total) => `تم رفع ${ok} من ${total} من المرفقات.`,
  ackNext: 'سيراجعها ممرض الصحة المهنية ويتواصل معك بالطريقة التي طلبتها. يمكنك متابعة الحالة من الشاشة الرئيسية.',
  backHome: 'العودة إلى الرئيسية',
  loading: 'جارٍ التحميل…',
};

const STRINGS: Record<string, ReferralStrings> = { en, fr, ar };

/** Built-in fallback when `GET /languages` is unavailable. */
export const DEFAULT_LANGUAGES = [
  { code: 'en', label: 'English' },
  { code: 'ar', label: 'العربية' },
  { code: 'fr', label: 'Français' },
];

/** Primary subtag, lower-case (`ar-AE` → `ar`). */
export function baseLang(code: string | undefined): string {
  return (code ?? 'en').toLowerCase().split(/[-_]/)[0] || 'en';
}

export function stringsFor(code: string | undefined): ReferralStrings {
  return STRINGS[baseLang(code)] ?? en;
}

const RTL_LANGS = new Set(['ar', 'he', 'fa', 'ur']);

/** Text direction for a language code – Arabic (and other RTL scripts) render with `dir="rtl"`. */
export function dirFor(code: string | undefined): 'rtl' | 'ltr' {
  return RTL_LANGS.has(baseLang(code)) ? 'rtl' : 'ltr';
}

/** Pick the initial form language: browser preference if offered, else the first offered, else English. */
export function initialLanguage(offered: { code: string }[], preferred: readonly string[] = []): string {
  for (const p of preferred) {
    const hit = offered.find((o) => baseLang(o.code) === baseLang(p));
    if (hit) return hit.code;
  }
  return offered.find((o) => baseLang(o.code) === 'en')?.code ?? offered[0]?.code ?? 'en';
}
