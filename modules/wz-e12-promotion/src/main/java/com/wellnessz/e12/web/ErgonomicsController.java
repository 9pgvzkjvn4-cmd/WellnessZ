package com.wellnessz.e12.web;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.wellnessz.e12.ergonomics.ErgonomicsService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E12-F03 ergonomic assessments, photos and actions tracked to closure. */
@RestController
@RequestMapping("/api/v1")
public class ErgonomicsController {

    private final ErgonomicsService ergonomics;
    private final CurrentUserProvider users;

    public ErgonomicsController(ErgonomicsService ergonomics, CurrentUserProvider users) {
        this.ergonomics = ergonomics;
        this.users = users;
    }

    @GetMapping("/ergonomics/checklists")
    @PreAuthorize(Spel.ERGO)
    public List<ErgonomicsService.ChecklistView> checklists() {
        return ergonomics.checklists();
    }

    @GetMapping("/ergonomics/assessments")
    @PreAuthorize(Spel.ERGO)
    public List<ErgonomicsService.AssessmentView> list(@RequestParam(required = false) @Pattern(regexp = "[A-Z0-9][A-Z0-9_\\-]{0,63}") String unitCode,
                                                       @RequestParam(required = false) @Pattern(regexp = "GREEN|AMBER|RED") String rag) {
        return ergonomics.list(users.require(), unitCode, rag);
    }

    @GetMapping("/ergonomics/assessments/{id}")
    @PreAuthorize(Spel.ERGO)
    public ErgonomicsService.AssessmentView get(@PathVariable UUID id) {
        return ergonomics.get(users.require(), id);
    }

    @PostMapping("/ergonomics/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.ERGO)
    public ErgonomicsService.AssessmentView create(@Valid @RequestBody ErgonomicsService.AssessmentCommand cmd) {
        return ergonomics.create(users.require(), cmd);
    }

    @PostMapping(value = "/ergonomics/assessments/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ERGONOMIST')")
    public ErgonomicsService.PhotoView photo(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                            @RequestParam(value = "title", required = false) @Size(max = 200) String title) {
        try {
            return ergonomics.photo(users.require(), id, file.getBytes(), title);
        } catch (IOException e) {
            throw ApiException.badRequest("unreadable_file", "The file could not be read");
        }
    }

    @PostMapping("/ergonomics/assessments/{id}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.ERGO)
    public ErgonomicsService.ActionView addAction(@PathVariable UUID id, @Valid @RequestBody ErgonomicsService.ActionCommand cmd) {
        return ergonomics.addAction(users.require(), id, cmd);
    }

    @PutMapping("/ergonomics/assessments/{id}/actions/{actionId}")
    @PreAuthorize(Spel.ERGO_ACTION_UPDATE)
    public ErgonomicsService.ActionView updateAction(@PathVariable UUID id, @PathVariable UUID actionId,
                                                     @Valid @RequestBody ErgonomicsService.ActionUpdate cmd) {
        return ergonomics.updateAction(users.require(), id, actionId, cmd);
    }

    @GetMapping("/ergonomics/kpi")
    @PreAuthorize(Spel.ERGO)
    public ErgonomicsService.Kpi kpi(@RequestParam(required = false) @Pattern(regexp = "[A-Z0-9][A-Z0-9_\\-]{0,63}") String unitCode) {
        return ergonomics.kpi(unitCode);
    }
}
