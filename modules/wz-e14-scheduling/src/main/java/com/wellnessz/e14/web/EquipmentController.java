package com.wellnessz.e14.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e14.equipment.EquipmentService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E14-F04 equipment register, calibration, cold chain (manual and IoT readings), excursions and reports. */
@RestController
@RequestMapping("/api/v1")
public class EquipmentController {

    static final String CATEGORY = "AUDIOMETER|SPIROMETER|ECG|VISION_SCREENER|VACCINE_FRIDGE|OTHER";
    static final String TAG = "[A-Za-z0-9._-]{1,64}";
    static final String DEVICE = "[A-Za-z0-9._-]{1,100}";

    private final EquipmentService equipment;
    private final CurrentUserProvider users;

    public EquipmentController(EquipmentService equipment, CurrentUserProvider users) {
        this.equipment = equipment;
        this.users = users;
    }

    @GetMapping("/scheduling/equipment")
    @PreAuthorize(E14Roles.EQUIPMENT_READ)
    public List<EquipmentService.EquipmentView> list(@RequestParam(required = false) @Pattern(regexp = CatalogueController.SITE) String siteCode,
                                                     @RequestParam(required = false) @Pattern(regexp = CATEGORY) String category) {
        return equipment.list(siteCode, category);
    }

    @GetMapping("/scheduling/equipment/calibration-due")
    @PreAuthorize(E14Roles.CALIBRATION_DUE)
    public List<EquipmentService.EquipmentView> calibrationDue(@RequestParam(required = false, defaultValue = "30") @Min(0) @Max(366) int withinDays) {
        return equipment.calibrationDue(withinDays);
    }

    @GetMapping("/scheduling/equipment/{id}")
    @PreAuthorize(E14Roles.EQUIPMENT_READ)
    public EquipmentService.EquipmentView one(@PathVariable UUID id) {
        return equipment.one(id);
    }

    public record EquipmentRequest(@NotBlank @Pattern(regexp = TAG) String assetTag, @Pattern(regexp = DEVICE) String deviceId,
                                   @NotBlank @Pattern(regexp = CATEGORY) String category, @Size(max = 200) String model,
                                   @Size(max = 100) String serialNumber, @NotBlank @Pattern(regexp = CatalogueController.SITE) String siteCode,
                                   @Min(1) @Max(120) Integer calibrationIntervalMonths, LocalDate lastCalibratedOn, LocalDate calibrationDueOn,
                                   @DecimalMin("-90") @DecimalMax("60") BigDecimal minTempC, @DecimalMin("-90") @DecimalMax("60") BigDecimal maxTempC) {
        EquipmentService.EquipmentCommand command() {
            return new EquipmentService.EquipmentCommand(assetTag, deviceId, category, model, serialNumber, siteCode, calibrationIntervalMonths,
                    lastCalibratedOn, calibrationDueOn, minTempC, maxTempC);
        }
    }

    @PostMapping("/scheduling/equipment")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(E14Roles.EQUIPMENT_WRITE)
    public EquipmentService.EquipmentView register(@Valid @RequestBody EquipmentRequest r) {
        return equipment.register(users.require(), r.command());
    }

    @PutMapping("/scheduling/equipment/{id}")
    @PreAuthorize(E14Roles.EQUIPMENT_WRITE)
    public EquipmentService.EquipmentView update(@PathVariable UUID id, @Valid @RequestBody EquipmentRequest r) {
        return equipment.update(users.require(), id, r.command());
    }

    public record CalibrationRequest(@NotNull LocalDate performedOn, @NotBlank @Size(max = 200) String vendor,
                                     @NotBlank @Pattern(regexp = "[A-Za-z0-9./_-]{1,100}") String certificateNumber,
                                     @NotBlank @Pattern(regexp = "PASS|FAIL") String result, LocalDate nextDueOn,
                                     @Size(max = 14_000_000) String documentBase64) {
    }

    @PostMapping("/scheduling/equipment/{id}/calibrations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(E14Roles.BIOMEDICAL)
    public EquipmentService.CalibrationView calibrate(@PathVariable UUID id, @Valid @RequestBody CalibrationRequest r) {
        return equipment.recordCalibration(users.require(), id, new EquipmentService.CalibrationCommand(r.performedOn(), r.vendor(),
                r.certificateNumber(), r.result(), r.nextDueOn(), r.documentBase64()));
    }

    @GetMapping("/scheduling/equipment/{id}/calibrations")
    @PreAuthorize(E14Roles.EQUIPMENT_READ)
    public List<EquipmentService.CalibrationView> calibrations(@PathVariable UUID id) {
        return equipment.calibrationHistory(id);
    }

    public record StatusRequest(@NotBlank @Pattern(regexp = "IN_SERVICE|OUT_OF_SERVICE|RETIRED") String status, @NotBlank @Size(max = 500) String reason) {
    }

    @PostMapping("/scheduling/equipment/{id}/status")
    @PreAuthorize(E14Roles.BIOMEDICAL)
    public EquipmentService.EquipmentView status(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        return equipment.setStatus(users.require(), id, r.status(), r.reason());
    }

    // ------------------------------------------------------------------ cold chain

    public record ReadingRequest(@NotNull @DecimalMin("-90") @DecimalMax("60") BigDecimal temperatureC, Instant recordedAt) {
    }

    @PostMapping("/scheduling/equipment/{id}/temperatures")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(E14Roles.READINGS_WRITE)
    public EquipmentService.ReadingView reading(@PathVariable UUID id, @Valid @RequestBody ReadingRequest r) {
        return equipment.manualReading(users.require(), id, r.temperatureC(), r.recordedAt());
    }

    public record IotReadingRequest(@NotBlank @Pattern(regexp = DEVICE) String deviceId,
                                    @NotNull @DecimalMin("-90") @DecimalMax("60") BigDecimal temperatureC, @NotNull Instant recordedAt) {
    }

    @PostMapping("/integration/cold-chain/readings")
    @PreAuthorize("hasRole('INTEGRATION')")
    public EquipmentService.IotResult iot(@RequestBody @NotEmpty @Size(max = EquipmentService.MAX_IOT_BATCH) List<@Valid IotReadingRequest> readings) {
        return equipment.iotReadings(users.require(), readings.stream()
                .map(r -> new EquipmentService.IotReading(r.deviceId(), r.temperatureC(), r.recordedAt())).toList());
    }

    @GetMapping("/scheduling/equipment/{id}/temperatures")
    @PreAuthorize(E14Roles.EQUIPMENT_READ)
    public List<EquipmentService.ReadingView> temperatures(@PathVariable UUID id,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant hi = to == null ? Instant.now().plus(1, ChronoUnit.DAYS) : to;
        Instant lo = from == null ? hi.minus(31, ChronoUnit.DAYS) : from;
        if (lo.isAfter(hi) || lo.plus(400, ChronoUnit.DAYS).isBefore(hi)) {
            throw ApiException.badRequest("invalid_range", "from/to must span at most 400 days");
        }
        return equipment.temperatures(id, lo, hi);
    }

    @GetMapping("/scheduling/excursions")
    @PreAuthorize(E14Roles.EXCURSIONS_READ)
    public List<EquipmentService.ExcursionView> excursions(@RequestParam(required = false) @Pattern(regexp = "OPEN|ACKNOWLEDGED|CLOSED") String status) {
        return equipment.excursions(status);
    }

    public record AcknowledgeRequest(@NotBlank @Size(max = 1000) String action) {
    }

    @PostMapping("/scheduling/excursions/{id}/acknowledge")
    @PreAuthorize(E14Roles.EXCURSIONS_WRITE)
    public EquipmentService.ExcursionView acknowledge(@PathVariable UUID id, @Valid @RequestBody AcknowledgeRequest r) {
        return equipment.acknowledge(users.require(), id, r.action());
    }

    @PostMapping("/scheduling/excursions/{id}/close")
    @PreAuthorize(E14Roles.EXCURSIONS_WRITE)
    public EquipmentService.ExcursionView close(@PathVariable UUID id) {
        return equipment.close(users.require(), id);
    }

    @GetMapping("/scheduling/cold-chain/report")
    @PreAuthorize(E14Roles.COLD_CHAIN_REPORT)
    public EquipmentService.ColdChainReport report(@RequestParam UUID equipmentId,
                                                   @RequestParam @Pattern(regexp = CatalogueController.MONTH) String month) {
        return equipment.report(equipmentId, CatalogueController.month(month));
    }
}
