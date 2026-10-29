package com.wellnessz.documents;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1")
@Validated
public class DocumentController {

    private static final String EMP = "[A-Za-z0-9_\\-]{1,64}";

    private final DocumentService documents;
    private final RetentionService retention;
    private final CurrentUserProvider users;

    public DocumentController(DocumentService documents, RetentionService retention, CurrentUserProvider users) {
        this.documents = documents;
        this.retention = retention;
        this.users = users;
    }

    public record TemplateView(String code, String type, String title, List<DocumentTemplates.MergeField> mergeFields, String audience,
                               boolean containsClinical) {
    }

    public record GenerateRequest(@NotBlank @Pattern(regexp = "[A-Z_]{1,60}") String templateCode, @NotBlank @Pattern(regexp = EMP) String employeeId,
                                  UUID caseId, @Size(max = 40) Map<String, @Size(max = 4000) String> fields) {
    }

    public record VersionRequest(@Size(max = 40) Map<String, @Size(max = 4000) String> fields) {
    }

    public record ShareRequest(@NotNull DocumentShare.Target target, @Pattern(regexp = "[A-Z0-9_]{2,40}") String partnerOrg, UUID consentId) {
    }

    public record HoldRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record RetentionRequest(int retentionYears, @NotBlank @Pattern(regexp = "LAST_ENCOUNTER|DOCUMENT_DATE|EMPLOYMENT_END") String trigger,
                                   @NotBlank @Size(max = 500) String legalBasis) {
    }

    @GetMapping("/documents/templates")
    @PreAuthorize(Roles.OH_TEAM_SPEL + " or hasRole('HR')")
    public List<TemplateView> templates() {
        return documents.templates(users.require()).stream()
                .map(t -> new TemplateView(t.code(), t.type(), t.title(), t.mergeFields(), t.audience(), t.containsClinical())).toList();
    }

    @PostMapping("/documents/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.OH_TEAM_SPEL + " or hasRole('HR')")
    public DocumentService.DocumentView generate(@Valid @RequestBody GenerateRequest r) {
        return documents.generate(users.require(), r.templateCode(), r.employeeId(), r.caseId(), r.fields());
    }

    @PostMapping("/documents/{id}/sign")
    @PreAuthorize(Roles.OH_TEAM_SPEL + " or hasRole('HR')")
    public DocumentService.DocumentView sign(@PathVariable UUID id) {
        return documents.sign(users.require(), id);
    }

    @PostMapping("/documents/{id}/new-version")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public DocumentService.DocumentView newVersion(@PathVariable UUID id, @Valid @RequestBody VersionRequest r) {
        return documents.newVersion(users.require(), id, r.fields());
    }

    @PostMapping("/documents/{id}/share")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public DocumentService.ShareView share(@PathVariable UUID id, @Valid @RequestBody ShareRequest r) {
        return documents.share(users.require(), id, r.target(), r.partnerOrg(), r.consentId());
    }

    @DeleteMapping("/documents/{id}/shares/{shareId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public void revoke(@PathVariable UUID id, @PathVariable UUID shareId) {
        documents.revokeShare(users.require(), id, shareId);
    }

    @GetMapping("/documents")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<DocumentService.DocumentView> list(@RequestParam @Pattern(regexp = EMP) String employeeId, @RequestParam(required = false) UUID caseId,
                                                   @RequestParam(required = false) @Pattern(regexp = "[A-Z_]{1,40}") String type) {
        return documents.list(users.require(), employeeId, caseId, type);
    }

    @GetMapping("/documents/search")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<DocumentService.SearchHit> search(@RequestParam @Pattern(regexp = EMP) String employeeId, @RequestParam @Size(min = 3, max = 200) String q) {
        return documents.search(users.require(), employeeId, q);
    }

    @GetMapping("/documents/{id}")
    public DocumentService.DocumentView get(@PathVariable UUID id) {
        return documents.get(users.require(), id);
    }

    @GetMapping("/documents/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        DocumentService.Content c = documents.content(users.require(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(c.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(c.bytes());
    }

    @PostMapping(value = "/documents/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.OH_TEAM_SPEL + " or hasRole('CLINIC_RECEPTION')")
    public DocumentService.DocumentView scan(@RequestPart("file") MultipartFile file, @RequestParam @Pattern(regexp = EMP) String employeeId,
                                             @RequestParam(required = false) UUID caseId,
                                             @RequestParam(required = false) @Pattern(regexp = "EXTERNAL_REPORT|OTHER") String documentType) {
        try {
            return documents.scan(users.require(), employeeId, caseId, documentType, file.getBytes());
        } catch (IOException e) {
            throw ApiException.badRequest("upload_failed", "Upload could not be read");
        }
    }

    @GetMapping("/me/documents")
    public List<DocumentService.DocumentView> mine() {
        return documents.sharedWithMe(users.require());
    }

    @PostMapping("/me/documents/{id}/acknowledge")
    public DocumentService.DocumentView acknowledge(@PathVariable UUID id) {
        return documents.acknowledge(users.require(), id);
    }

    @GetMapping("/workspace/documents")
    @PreAuthorize("hasAnyRole('MANAGER','HR')")
    public List<DocumentService.DocumentView> workspace() {
        return documents.sharedWithWorkspace(users.require());
    }

    @GetMapping("/partner/documents")
    @PreAuthorize("hasRole('PARTNER_PROVIDER')")
    public List<DocumentService.DocumentView> partner() {
        return documents.sharedWithPartner(users.require());
    }

    @GetMapping("/documents/retention")
    @PreAuthorize("hasAnyRole('DPO','OH_ADMIN')")
    public List<RetentionService.RuleView> retention() {
        return retention.schedule();
    }

    @PutMapping("/documents/retention/{recordClass}")
    @PreAuthorize("hasRole('DPO')")
    public RetentionService.RuleView updateRetention(@PathVariable @Pattern(regexp = "[A-Z_]{1,40}") String recordClass,
                                                     @Valid @RequestBody RetentionRequest r) {
        return retention.update(users.require(), recordClass, r.retentionYears(), r.trigger(), r.legalBasis());
    }

    @GetMapping("/documents/retention/due")
    @PreAuthorize("hasRole('DPO')")
    public List<RetentionService.DueItem> due() {
        return retention.due();
    }

    @PostMapping("/documents/{id}/legal-hold")
    @PreAuthorize("hasRole('DPO')")
    public DocumentService.DocumentView hold(@PathVariable UUID id, @Valid @RequestBody HoldRequest r) {
        return documents.legalHold(users.require(), id, true, r.reason());
    }

    @DeleteMapping("/documents/{id}/legal-hold")
    @PreAuthorize("hasRole('DPO')")
    public DocumentService.DocumentView release(@PathVariable UUID id) {
        return documents.legalHold(users.require(), id, false, null);
    }
}
