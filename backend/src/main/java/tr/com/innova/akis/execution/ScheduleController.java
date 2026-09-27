package tr.com.innova.akis.execution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** CRUD + pause/resume + preview for cron schedules (V049/V058, {@link ScheduleService}). */
@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/schedules")
final class ScheduleController {

    private final ScheduleService schedules;

    ScheduleController(ScheduleService schedules) { this.schedules = schedules; }

    record CreateRequest(
            String kod, String ad, UUID publicationUuid, String cronExpression, String timeZone,
            ScheduleService.ConflictPolicy conflictPolicy, ScheduleService.MisfirePolicy misfirePolicy,
            SchedulePublicationPolicy publicationPolicy, ScheduleService.Status desiredStatus,
            Instant startsAt, Instant endsAt) {
    }

    record UpdateRequest(
            long expectedVersion, String kod, String ad, UUID publicationUuid, String cronExpression, String timeZone,
            ScheduleService.ConflictPolicy conflictPolicy, ScheduleService.MisfirePolicy misfirePolicy,
            SchedulePublicationPolicy publicationPolicy, ScheduleService.Status desiredStatus,
            Instant startsAt, Instant endsAt) {
    }

    record VersionedRequest(long expectedVersion) {
    }

    record PreviewRequest(String cronExpression, String timeZone, Instant startsAt, Instant endsAt) {
    }

    @GetMapping
    List<ScheduleService.View> list(@PathVariable UUID projectUuid) {
        return schedules.list(projectUuid);
    }

    @GetMapping("/{scheduleUuid}")
    ScheduleService.View get(@PathVariable UUID projectUuid, @PathVariable UUID scheduleUuid) {
        return schedules.get(projectUuid, scheduleUuid);
    }

    @GetMapping("/{scheduleUuid}/events")
    List<ScheduleService.TriggerEvent> triggerEvents(@PathVariable UUID projectUuid, @PathVariable UUID scheduleUuid) {
        return schedules.triggerEvents(projectUuid, scheduleUuid);
    }

    @PostMapping
    ScheduleService.View create(@PathVariable UUID projectUuid, @RequestBody CreateRequest request) {
        return schedules.create(
                projectUuid, request.kod(), request.ad(), request.publicationUuid(),
                request.cronExpression(), request.timeZone(),
                request.conflictPolicy() == null ? ScheduleService.ConflictPolicy.SKIP : request.conflictPolicy(),
                request.misfirePolicy() == null ? ScheduleService.MisfirePolicy.SKIP : request.misfirePolicy(),
                request.publicationPolicy() == null ? SchedulePublicationPolicy.LATEST_ACTIVE : request.publicationPolicy(),
                request.desiredStatus() == null ? ScheduleService.Status.ASKIDA : request.desiredStatus(),
                request.startsAt(), request.endsAt());
    }

    @PutMapping("/{scheduleUuid}")
    ScheduleService.View update(@PathVariable UUID projectUuid, @PathVariable UUID scheduleUuid, @RequestBody UpdateRequest request) {
        return schedules.update(projectUuid, scheduleUuid, request.expectedVersion(), request.kod(), request.ad(), request.publicationUuid(),
                request.cronExpression(), request.timeZone(), request.conflictPolicy(), request.misfirePolicy(),
                request.publicationPolicy() == null ? SchedulePublicationPolicy.LATEST_ACTIVE : request.publicationPolicy(),
                request.desiredStatus() == null ? ScheduleService.Status.ASKIDA : request.desiredStatus(),
                request.startsAt(), request.endsAt());
    }

    @PostMapping("/{scheduleUuid}/pause")
    ScheduleService.View pause(@PathVariable UUID projectUuid, @PathVariable UUID scheduleUuid, @RequestBody VersionedRequest request) {
        return schedules.pause(projectUuid, scheduleUuid, request.expectedVersion());
    }

    @PostMapping("/{scheduleUuid}/resume")
    ScheduleService.View resume(@PathVariable UUID projectUuid, @PathVariable UUID scheduleUuid, @RequestBody VersionedRequest request) {
        return schedules.resume(projectUuid, scheduleUuid, request.expectedVersion());
    }

    @DeleteMapping("/{scheduleUuid}")
    void delete(@PathVariable UUID projectUuid, @PathVariable UUID scheduleUuid, @RequestParam long expectedVersion) {
        schedules.delete(projectUuid, scheduleUuid, expectedVersion);
    }

    @PostMapping("/preview")
    ScheduleService.Preview preview(@PathVariable UUID projectUuid, @RequestBody PreviewRequest request) {
        return schedules.preview(projectUuid, request.cronExpression(), request.timeZone(), request.startsAt(), request.endsAt());
    }
}
