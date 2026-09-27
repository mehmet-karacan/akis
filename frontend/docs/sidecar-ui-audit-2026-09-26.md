# Frontend sidecar audit — 2026-09-26

Scope: current dirty worktree, frontend only. Existing changes were preserved. This is a code audit plus a bounded repair of the shared JSON export workflow; it is not sign-off for all AKIS requirements. No backend/database changes, migrations, commit, or push.

## Implemented: shared JSON export completion

- `src/core/ui/ExportMenu.tsx`: previously dispatched `akis:export-job-created`, but no application listener rendered `ExportJobStatus`. The initiating control now owns a shared Dialog with job status and a reopen action. Creation failures are caught and shown. The application language controls labels and request locale.
- `src/core/ui/ExportJobStatus.tsx`: localized job states, row counts and actions; explicit status retry; cancellation refresh; cleared poll timers on cleanup; stable completion callback. JSON download still uses a browser attachment link, without buffering the export in renderer memory.
- Removed the misleading VISIBLE and SELECTED menu choices. `ExportRequest` has no visible/selected row identities, so the UI could not communicate those scopes faithfully. FILTERED and ALL remain available. Row-specific scopes require a coordinated API contract change and are not completed here.
- `src/features/execution/RunsPage.tsx`: sends `includeDetails: true` for history export, matching the requirement to include child details. Returned backend data completeness still needs server integration verification.
- Job tracking is local to the mounted menu. Reload/navigation persistence and an account-wide export job center are not implemented by this change.

## Audit findings remaining

| Area | Current code evidence | Finding / next bounded task |
| --- | --- | --- |
| Shared views | `src/core/ui/ViewToggle.tsx:6-16` still defines card/list/table and restores `list` preferences | Requirement to remove list view is open. Migrate stored preferences and update DataGrid/RecordCard consumers together. |
| Icon/color standard | `src/core/theme/icons.css:23-29` applies colored backgrounds to every summary icon tone | Conflicts with icon-only coloring request. Central semantic tokens exist, but backgrounds and per-object meanings need a coordinated visual audit. |
| Navigation | `src/app/AppShell.tsx:145-148` composes existing WorkspaceNavigation plus workspace-specific subnavigation/tree | Existing collapsible structure is present; full project-wide information architecture redesign is not demonstrated by this implementation. |
| Project import | `src/features/bundles/BundleImportPage.tsx:58-60,126,147` and `src/features/bundles/api.ts:26-32` | Validation/dry-run proof and confirmation exist. UI only submits document/conflict/dryRun; no explicit plan/binding/remap editor exists here. Independently imported project execution requires end-to-end verification. |
| History export | `src/features/execution/RunsPage.tsx:157` | Details are now requested. Export filters and returned nested SQL/step completeness require provider integration tests. No server behavior was inferred from a frontend mock. |
| Run detail layout | `src/features/execution/execution.css:261-271` fixes modal height and hides outer overflow; summary becomes one column below 560px | Short mobile viewports can leave insufficient height for the flow/SQL area. This is a code-level risk, not a browser-confirmed regression in this audit. Test short viewports with populated summaries before modifying the shared layout. |
| Schedules | `src/features/execution/ScheduleEditorPanel.tsx:12-16,31-40,62` | Next-occurrence API preview is present for preset and custom modes. Hourly is only once per hour: every 2/3 hours and multiple weekdays still need custom cron. Preview effect runs even while dialog is closed. Display uses browser-zone formatDate rather than explicitly formatting the selected schedule zone. |
| Table/catalog detail | `src/features/models/DataObjectTable.tsx:60` uses DataGrid without disabling view controls | Column catalog still exposes alternate views, contrary to the table-only requirement. Catalog header/summary also uses custom layout rather than the common SummaryStrip. |
| Model filters | `src/features/models/ModelDetailPage.tsx:42-45` | Filtering is still a single text query; object type/status filters remain to be implemented. |
| Browser overflow | `src/styles.css:12`; `src/core/theme/ant-design.css:541` | Root scrolling is disabled. That alone does not prove all content is reachable; procedure workbench has a 360px height floor and requires short-viewport checks. Export dialog is tested separately at 390×740 and 1440×900. |
| Branding/footer | `src/core/brand/brand.ts`, `BrandLogo.tsx`, `CorporateFooter.tsx`; `src/app/AppShell.tsx:157` | Shared İnnova identity, 2025–2026 company text, theme-specific corporate logos and shell footer are present. Narrow login/shell/footer geometry has not been visually signed off in this audit. |

## Verification

Commands run from `frontend/`:

- `npm run test -- src/core/ui/ExportMenu.test.tsx src/core/ui/ExportJobStatus.test.tsx`: 8 passed.
- `npm run lint`: passed.
- `npm run build`: passed; Vite reports existing chunks above 500 kB.
- Full unit suite and isolated browser checks: results recorded after completion below.

Browser fixture uses only synthetic data and intercepted API requests. It does not authenticate, export production records, or modify application data. Checks cover dialog/document bounds, JSON content, download route, and reopening status. They do not certify every application route or the real export provider.
