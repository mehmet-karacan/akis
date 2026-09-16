# AKIS-EXECUTION-RECOVERY-01 test matrisi

Durumlar: `PASS_U` gerçek unit testi; `BLOCKED_P` Docker/PostgreSQL yok;
`BLOCKED_O` disposable Oracle yok; `PASS_F` frontend unit/build;
`PENDING` henüz uygulama/kanıt tamamlanmadı. Bir satırdaki unit PASS, Oracle PASS
anlamına gelmez.

| ID | Unit/yerel kanıt | PostgreSQL | Oracle | Frontend | Durum |
|---|---|---|---|---|---|
| R01 | DDL/DML sonucu ayrımı modelde | BLOCKED_P | BLOCKED_O | — | PENDING |
| R02 | deferred rollback yolu ve grup sözleşmesi | BLOCKED_P | BLOCKED_O | — | PASS_U/PENDING_ENV |
| R03 | `managedNoCommitIsNotReportedSuccessfulBeforeGroupCommit` | — | BLOCKED_O | — | PASS_U/PENDING_ENV |
| R04 | planner committed unit'i skip eder | BLOCKED_P | BLOCKED_O | — | PASS_U/PENDING_ENV |
| R05 | `lostCommitResponseIsUnknownAndNeverRetriedInsideTheFacade` | — | BLOCKED_O | — | PASS_U/PENDING_O |
| R06 | `postgresCheckpointLossDoesNotCauseASecondWriteWithinTheCoordinator`, `onlyFreshTargetLocalEvidenceConfirmsAProjectedCommit` | BLOCKED_P | BLOCKED_O | — | PASS_U/PENDING_ENV |
| R07 | recovery idempotency constraint/kodu | BLOCKED_P | — | — | PENDING_P |
| R08 | mevcut commit boundary unitleri | — | BLOCKED_O | — | PASS_U/PENDING_ENV |
| R09 | hedef makbuzu doğrulanmadan skip yok | — | BLOCKED_O | — | PASS_U/PENDING_O |
| R10 | immutable work-unit/hash constraint ve manifest equality guard | BLOCKED_P | BLOCKED_O | — | PASS_U/PENDING_ENV |
| R11 | V021/V023 strict ACK migration | BLOCKED_P | — | — | PENDING_P |
| R12 | strict token fonksiyonları | BLOCKED_P | BLOCKED_O | — | PENDING |
| R13 | lease/fence mevcut unitleri | BLOCKED_P | BLOCKED_O | — | PENDING |
| R14 | fence mevcut sözleşmesi | BLOCKED_P | BLOCKED_O | — | PENDING |
| R15 | unique recovery idempotency + store | BLOCKED_P | — | API build PASS | PENDING_P/F |
| R16 | stale state/plan kontrolü | BLOCKED_P | — | API build PASS | PASS_U/PENDING_PF |
| R17 | controller proje authorization kullanır | BLOCKED_P | — | PENDING | PENDING |
| R18 | `REPLAY_DEPENDENCY` planner kararı | — | BLOCKED_O | — | PENDING |
| R19 | logical-job input snapshot şeması + canonical input hash | BLOCKED_P | BLOCKED_O | — | PASS_U/PENDING_ENV |
| R20 | history'den bağımsız snapshot şeması ve identity conflict guard | BLOCKED_P | — | — | PASS_U/PENDING_P |
| R21 | pinned hash/input planner | BLOCKED_P | — | PENDING | PENDING |
| R22 | `capturesTheScnFromTheSourceDatabase` + SCN-pinned SQL | — | BLOCKED_O | — | PASS_U/PENDING_O |
| R23 | `sourceFailureNeverFallsBackToALatestRead` | — | BLOCKED_O | — | PASS_U/PENDING_O |
| R24 | `emptyRangeProducesAnExplicitCommittedReceipt`, `duplicateOrderingKeyFailsClosedBeforeAReceiptCanAdvance` | — | BLOCKED_O | — | PASS_U/PENDING_O |
| R25 | per-chunk unique checkpoint modeli + coordinator manifest reuse | BLOCKED_P | BLOCKED_O | — | PASS_U/PENDING_ENV |
| R26 | intent-before-DML coordinator sırası | BLOCKED_P | BLOCKED_O | — | PASS_U/PENDING_ENV |
| R27 | `unknownJdbcBatchCountIsNeverInventedAsAnExactInsertCount` | — | BLOCKED_O | — | PASS_U/PENDING_O |
| R28 | `transferCountsRemainLong`; exact `BigInt` testi | — | BLOCKED_O | PASS_F | PASS_UF/PENDING_O |
| R29 | exact/unknown outcome tipleri ve `SUCCESS_NO_INFO` fail-closed | — | BLOCKED_O | exact unknown testi | PASS_UF/PENDING_O |
| R30 | `TransferBufferBudgetTest` | — | BLOCKED_O | — | PASS_U/PENDING_O |
| R31 | tek satır byte-budget red testi | — | BLOCKED_O | — | PASS_U/PENDING_O |
| R32 | workspace lifecycle şeması | BLOCKED_P | BLOCKED_O | — | PENDING |
| R33 | lifecycle resume durumları | BLOCKED_P | BLOCKED_O | — | PENDING |
| R34 | object id/structure hash modeli | BLOCKED_P | BLOCKED_O | — | PENDING |
| R35 | prefix sahiplik sayılmaz sözleşmesi | — | BLOCKED_O | — | PENDING |
| R36 | lifecycle version/retention modeli | BLOCKED_P | BLOCKED_O | — | PENDING |
| R37 | `JdbcOracleDdlLockTest`; create/grant/drop aynı session lock altında | — | BLOCKED_O | — | PASS_U/PENDING_O |
| R38 | mevcut stage validation çekirdeği | — | BLOCKED_O | — | PENDING |
| R39 | mevcut publish reconciliation çekirdeği | BLOCKED_P | BLOCKED_O | — | PENDING |
| R40 | publish/cleanup lifecycle ayrımı | — | BLOCKED_O | PENDING | PENDING |
| R41 | cancel/commit yarış adapterı yok | — | BLOCKED_O | PENDING | PENDING |
| R42 | `transactionGroupRejectsDdl` | — | BLOCKED_O | API build PASS | PASS_U/PENDING_O |
| R43 | eski capability ayrı tutuldu | BLOCKED_P | — | build PASS | PENDING_P |
| R44 | event cursor + sayfalı chunk endpoint/UI tablosu | — | — | PASS_F | PASS_UF |
| R45 | V001-V024 temiz/upgrade | BLOCKED_P | — | — | BLOCKED_P |
| R46 | mevcut Oracle V2 marker/preflight | — | BLOCKED_O | — | PENDING_O |
| R47 | default false + configured flag/handler readiness capability testi | — | — | build PASS | PASS_UF |
| R48 | transaction outcome/unknown metric tipi | — | BLOCKED_O | exact alan build PASS | PENDING |
| R49 | transaction group channel doğrulaması | — | BLOCKED_O | — | PENDING |
| R50 | policy retry bütçesi kalıcı tabloya bağlanmadı | BLOCKED_P | — | — | PENDING |

Bu matris açık kalan işi özellikle görünür bırakır. `PENDING` veya `BLOCKED_*`
satırı tamamlanmış kabul edilmez ve capability açma yetkisi vermez.
