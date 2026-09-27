package tr.com.innova.akis.execution;

/**
 * How a schedule resolves the publication to fire at tick time. Stored on the schedule row.
 *   LATEST_ACTIVE — at tick time, find the newest active publication for the same scenario and
 *                   environment as the configured publication. If none is active, fail noisily
 *                   (no silent fallback to an older or non-active publication).
 *   PINNED        — use exactly the configured publication. If it is no longer active, fail noisily.
 */
public enum SchedulePublicationPolicy {
    LATEST_ACTIVE,
    PINNED
}
