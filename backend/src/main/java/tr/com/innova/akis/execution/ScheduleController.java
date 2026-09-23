package tr.com.innova.akis.execution;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** CRUD + pause/resume for cron schedules (V049, {@link ScheduleService}). */
@RestController
@RequestMapping("/api/v1/projects/{projectUuid}/schedules")
final class ScheduleController {

    private final ScheduleService schedules;

    ScheduleController(ScheduleService schedules) { this.schedules = schedules; }

    record CreateRequest(
            String kod, String ad, UUID publicationUuid, String cronExpression, String timeZone,
            ScheduleService.ConflictPolicy conflictPolicy, ScheduleService.MisfirePolicy misfirePolicy) {
    }

    record VersionedRequest(long expectedVersion) {
    }

    @GetMapping
    List<ScheduleService.View> list(@PathVariable UUID projectUuid) {
        return schedules.list(projectUuid);
    }

    @GetMapping("/{scheduleUuid}")
    ScheduleService.View get(@PathVariable UUID projectUuid, @PathVariable UUID scheduleUuid) {
        return schedules.get(projectUuid, scheduleUuid);
    }

    @PostMapping
    ScheduleService.View create(@PathVariable UUID projectUuid, @RequestBody CreateRequest request) {
        return schedules.create(
                projectUuid, request.kod(), request.ad(), request.publicationUuid(),
                request.cronExpression(), request.timeZone(),
                request.conflictPolicy() == null ? ScheduleService.ConflictPolicy.SKIP : request.conflictPolicy(),
                request.misfirePolicy() == null ? ScheduleService.MisfirePolicy.SKIP : request.misfirePolicy());
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
}
