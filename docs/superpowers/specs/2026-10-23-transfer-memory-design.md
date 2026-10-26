# Environment transfer memory budget

The user selected an environment default of 1 GiB, inherited automatically by interfaces, followed by three new 500K measurements compared with the preserved baseline. Implement in the existing Java/React application.

Environment policy stores `transferMemoryBudgetMiB` (default 1024, range 64–65536). Environment forms expose this administrator setting. Mapping AUTO options inherit it at publication; the physical plan pins `memoryBudgetBytes`, so a later increase does not silently enlarge an existing publication. A reduced environment ceiling requires republication. Existing publication JSON remains readable.

Reuse the environment policy JSON rather than a parallel profile registry. A per-object manual budget and OS/container RSS enforcement are outside this request. The budget bounds managed transfer buffers and driver window estimates, not total JVM RSS or database-server memory. Total dataset byte quota remains separate. Worker concurrency divides available heap conservatively; user-facing fetch/batch knobs are unnecessary.

Split usable transfer memory between estimated JDBC/LOB read windows and write buffers, retaining headroom and explicit per-row/LOB safeguards. Increase AUTO read windows only within estimated metadata/observed row bounds. Preserve transactional publication, cancellation checkpoints, ordering, type fidelity and final SHA validation.

Old 100K/250K and three baseline 500K results are immutable. New 500K runs have a distinct comparison cohort/publication and three independently reset targets. Report cohort medians, process and host metrics, read/write timings, budget and environment. Do not pool old/new 500K medians. Keep PROD schedule paused.
