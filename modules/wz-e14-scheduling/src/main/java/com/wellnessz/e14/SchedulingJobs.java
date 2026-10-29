package com.wellnessz.e14;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.e14.booking.AppointmentService;
import com.wellnessz.e14.equipment.EquipmentService;
import com.wellnessz.integration.JobRunGuard;

/**
 * E14 scheduled jobs, each run once per run key across all instances ({@link JobRunGuard}): {@code appointment-reminders}
 * and {@code appointment-no-show} every 15 minutes, {@code equipment-calibration} daily.
 */
@Component
public class SchedulingJobs {

    private static final Logger log = LoggerFactory.getLogger(SchedulingJobs.class);

    private final JobRunGuard guard;
    private final AppointmentService appointments;
    private final EquipmentService equipment;
    private final Clock clock;

    public SchedulingJobs(JobRunGuard guard, AppointmentService appointments, EquipmentService equipment, Clock clock) {
        this.guard = guard;
        this.appointments = appointments;
        this.equipment = equipment;
        this.clock = clock;
    }

    private static String bucket(Instant now) {
        Instant t = now.truncatedTo(ChronoUnit.MINUTES);
        long minute = t.atZone(ZoneOffset.UTC).getMinute();
        return t.minus(minute % 15, ChronoUnit.MINUTES).toString();
    }

    @Scheduled(cron = "${wellnessz.scheduling.reminder-cron:0 */15 * * * *}", zone = "UTC")
    public void reminders() {
        Instant now = clock.instant();
        if (guard.tryStart("appointment-reminders", bucket(now))) {
            AppointmentService.JobRun r = appointments.sendReminders(now);
            log.info("appointment-reminders: checked={} reminded={}", r.processed(), r.changed());
        }
    }

    @Scheduled(cron = "${wellnessz.scheduling.no-show-cron:0 5/15 * * * *}", zone = "UTC")
    public void noShows() {
        Instant now = clock.instant();
        if (guard.tryStart("appointment-no-show", bucket(now))) {
            AppointmentService.JobRun r = appointments.detectNoShows(now);
            log.info("appointment-no-show: checked={} marked={}", r.processed(), r.changed());
        }
    }

    @Scheduled(cron = "${wellnessz.scheduling.calibration-cron:0 40 2 * * *}", zone = "UTC")
    public void calibration() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (guard.tryStart("equipment-calibration", today.toString())) {
            log.info("equipment-calibration: alerts={}", equipment.calibrationAlerts(today));
        }
    }
}
