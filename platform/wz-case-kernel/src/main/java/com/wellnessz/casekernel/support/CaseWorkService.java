package com.wellnessz.casekernel.support;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.wellnessz.casekernel.model.AttachmentMeta;
import com.wellnessz.casekernel.model.CaseAttachment;
import com.wellnessz.casekernel.model.CaseAttachmentRepository;
import com.wellnessz.casekernel.model.CaseEvent;
import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.CaseTaskRepository;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/** Tasks, attachments and timeline of a case. */
@Service
public class CaseWorkService {

    static final long MAX_ATTACHMENT_BYTES = 5L * 1024 * 1024;
    static final int MAX_ATTACHMENTS = 5;

    private final CaseAccess access;
    private final CaseTaskRepository tasks;
    private final CaseAttachmentRepository attachments;
    private final CaseTimeline timeline;
    private final AuditService audit;
    private final Clock clock;

    public CaseWorkService(CaseAccess access, CaseTaskRepository tasks, CaseAttachmentRepository attachments, CaseTimeline timeline,
                           AuditService audit, Clock clock) {
        this.access = access;
        this.tasks = tasks;
        this.attachments = attachments;
        this.timeline = timeline;
        this.audit = audit;
        this.clock = clock;
    }

    public record TaskView(UUID id, UUID caseId, CaseTask.Type type, String title, Instant dueAt, String assignee,
                           CaseTask.Status status, String createdBy, Instant createdAt, Instant completedAt) {
        public static TaskView of(CaseTask t) {
            return new TaskView(t.getId(), t.getCaseId(), t.getType(), t.getTitle(), t.getDueAt(), t.getAssignee(), t.getStatus(),
                    t.getCreatedBy(), t.getCreatedAt(), t.getCompletedAt());
        }
    }

    public record TimelineEntry(Instant at, String type, String summary, String actor) {
    }

    @Transactional(readOnly = true)
    public List<TaskView> tasks(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "CASE_TASKS_READ");
        return tasks.findByCaseIdOrderByCreatedAtAsc(caseId).stream().map(TaskView::of).toList();
    }

    @Transactional
    public TaskView createTask(CurrentUser user, UUID caseId, CaseTask.Type type, String title, Instant dueAt, String assignee) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "CASE_TASK_CREATE");
        CaseTask t = tasks.save(new CaseTask(c.getId(), type, title, dueAt, assignee == null ? c.getOwnerUsername() : assignee,
                user.username(), clock.instant()));
        timeline.record(c.getId(), "TASK_CREATED", "Task created: " + type);
        return TaskView.of(t);
    }

    /** Creates a task on behalf of a module (no user context checks – caller already authorised). */
    @Transactional
    public CaseTask systemTask(UUID caseId, CaseTask.Type type, String title, Instant dueAt, String assignee, String createdBy) {
        return tasks.save(new CaseTask(caseId, type, title, dueAt, assignee, createdBy, clock.instant()));
    }

    @Transactional
    public TaskView completeTask(CurrentUser user, UUID taskId) {
        CaseTask t = tasks.findById(taskId).orElseThrow(() -> ApiException.notFound("Task"));
        access.clinical(user, t.getCaseId(), AccessPolicy.Action.UPDATE, "CASE_TASK_COMPLETE");
        t.complete(clock.instant());
        timeline.record(t.getCaseId(), "TASK_DONE", "Task completed: " + t.getType());
        return TaskView.of(t);
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "CASE_TIMELINE_READ");
        return timeline.of(caseId).stream().map(e -> new TimelineEntry(e.getOccurredAt(), e.getType(), e.getSummary(), e.getActor()))
                .toList();
    }

    public static List<TimelineEntry> toEntries(List<CaseEvent> events) {
        return events.stream().map(e -> new TimelineEntry(e.getOccurredAt(), e.getType(), e.getSummary(), e.getActor())).toList();
    }

    /** Upload by the data subject (own case) or by the OH team. Type is verified by magic bytes, not the header. */
    @Transactional
    public AttachmentMeta upload(CurrentUser user, UUID caseId, MultipartFile file) throws IOException {
        OhCase c = access.require(user, caseId, AccessPolicy.Action.READ, DisclosureLevel.SELF, "CASE_ATTACHMENT_UPLOAD");
        if (file.isEmpty() || file.getSize() > MAX_ATTACHMENT_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "attachment_too_large", "Attachments must be between 1 byte and 5 MB");
        }
        if (attachments.countByCaseId(caseId) >= MAX_ATTACHMENTS) {
            throw ApiException.conflict("too_many_attachments", "A case can hold at most " + MAX_ATTACHMENTS + " attachments");
        }
        byte[] bytes = file.getBytes();
        String type = sniff(bytes);
        if (type == null) {
            throw ApiException.badRequest("unsupported_media", "Only JPEG, PNG or PDF files are accepted");
        }
        String name = sanitiseName(file.getOriginalFilename(), type);
        CaseAttachment a = attachments.save(new CaseAttachment(c.getId(), name, type, bytes.length, sha256(bytes),
                Base64.getEncoder().encodeToString(bytes), user.username(), clock.instant()));
        timeline.record(c.getId(), "ATTACHMENT_ADDED", "Attachment added (" + type + ")");
        audit.record("CASE_ATTACHMENT_UPLOAD", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, type);
        return new AttachmentMeta(a.getId(), a.getFileName(), a.getContentType(), a.getSize(), a.getUploadedAt());
    }

    public record Download(String fileName, String contentType, byte[] content) {
    }

    @Transactional(readOnly = true)
    public Download download(CurrentUser user, UUID caseId, UUID attachmentId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "CASE_ATTACHMENT_READ");
        CaseAttachment a = attachments.findById(attachmentId).filter(x -> x.getCaseId().equals(caseId))
                .orElseThrow(() -> ApiException.notFound("Attachment"));
        audit.record("CASE_ATTACHMENT_READ", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, attachmentId.toString());
        return new Download(a.getFileName(), a.getContentType(), Base64.getDecoder().decode(a.getContentBase64()));
    }

    static String sniff(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return "application/pdf";
        }
        return null;
    }

    static String sanitiseName(String original, String type) {
        String base = original == null ? "attachment" : original.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.length() > 120) {
            base = base.substring(0, 120);
        }
        String ext = switch (type) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> ".pdf";
        };
        return base.toLowerCase(Locale.ROOT).endsWith(ext) ? base : base + ext;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
