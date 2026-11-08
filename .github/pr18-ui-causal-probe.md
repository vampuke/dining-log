# PR18 Gate A passive native probe

This candidate implements only Gate A of the external
`/tmp/dining-publish-20261108/ui-cause-r1-discussion/PLAN.md` (sole requirements).
It is `DIAGNOSTIC_MODIFIED_INSTRUMENTATION`, based on
`be8aaf8d60aa5513227a102d4cdbfcb2377b778d`. It grants no causal-gate,
correction, independent-review, CI, app or release approval. Gate B and C are
unimplemented. No production source or normal CI/release policy changes.

The four original complete-flow parameterizations retain their byte-identical
method bodies, including font100 then font200, text/scroll/SetProgress actions,
physical/48dp checks, callback deltas/exact refs, score40 and logout cancellation.
The three fixture/evidence files have explicitly whitelisted passive additions;
removing those additions reconstructs their complete be8 source bytes. Every
other be8 file is protected, including production, other instrumentation and
normal CI. `plan.json` records the exact candidate SHA, allowed delta, all source
hashes, unchanged be8 bindings and original method/source projection hashes.
Post-run validation repeats this binding; a source guard is never native proof.

The observer is enabled only by the exact original four-case class selector.
It reads the existing Activity's registry provider and keyed store members,
observes production composition locals/default extras without passing new
owners/extras or requesting another VM, and records the existing VM's public
initial `restoredTab` in a SideEffect following the unchanged MainActivityContent
call. MainActivityContent remains the first call in its original content lambda;
the opted-in observer reads and SideEffect follow it without a wrapper. It
retains only the natural outgoing save Bundle and reads its allowlisted
registry/provider/product-key/tab path after the original configuration call
returns/idles. Callback-time contents are never read. It never calls save/consume,
injects a handle, changes an ID, resets state, chooses a starting tab or captures
replacement pixels. Its own callbacks and old Activity/Bundle/runtime references
are released in finally, including failure teardown. Probe JSONL files and logcat
use `PR18_GATE_A`; their case ID is the original capture case ID.

The frozen e087 collector's original source guard rejects this candidate.
The new producer loads its exact hash-bound code from
`e08702a890bd59d1a80b7232da8f28f2bd2533ad` and reuses only public collection,
XML, native-sampling, owned-process cleanup and archive interfaces. Its separate
orchestration/source manifest is necessary because the frozen producer accepts
only unchanged be8 instrumentation. No collector monkey-patching or claim of
original golden be8 is made. Stable file/PNG checks, continuous logcat health,
owned STOP/waitid/TERM/CONT/KILL cleanup and stopped-writer sealing remain intact.

The new producer checks actual API26 and executes exactly:

```bash
cd android
./gradlew :app:connectedDebugAndroidTest --no-daemon '-Pandroid.testInstrumentationRunnerArguments.class=top.alanvampuke.dininglog.product.Pr18ScreenAcceptanceTest#f7RestaurantSearchDishVisitHistoryDetailEditAndSettingsCompleteFlowsAt100And200Percent[INK_PAPER-dark=false],top.alanvampuke.dininglog.product.Pr18ScreenAcceptanceTest#f7RestaurantSearchDishVisitHistoryDetailEditAndSettingsCompleteFlowsAt100And200Percent[INK_PAPER-dark=true],top.alanvampuke.dininglog.product.Pr18ScreenAcceptanceTest#f7RestaurantSearchDishVisitHistoryDetailEditAndSettingsCompleteFlowsAt100And200Percent[QUIET_GRID-dark=false],top.alanvampuke.dininglog.product.Pr18ScreenAcceptanceTest#f7RestaurantSearchDishVisitHistoryDetailEditAndSettingsCompleteFlowsAt100And200Percent[QUIET_GRID-dark=true]'
```

After a different Codex reviews the exact candidate, the parent can verify and
push that reviewed SHA to `refs/heads/verify/pr18-ui-causal-probe-r1-20261108`.
That push triggers only this standalone diagnostic workflow (normal push CI is
still restricted to master). The implementation session performs no push or
dispatch. Exact parent invocation:

```bash
PR18_REVIEWED_SHA='the-exact-reviewed-candidate-sha'
git push origin "$PR18_REVIEWED_SHA":refs/heads/verify/pr18-ui-causal-probe-r1-20261108
```

Once registered, an explicit workflow_dispatch rerun selects
`PR18 Gate A Passive Native Causal Probe`, ref
`verify/pr18-ui-causal-probe-r1-20261108`, and input
`reviewed_candidate_sha` equal to the exact reviewed SHA. The workflow rejects
a ref or SHA mismatch before any build.

Actions runs `python3 -B .github/scripts/pr18-ui-causal-probe.py run --live
"$PR18_GATE_A_LIVE" --timeout-seconds 1500`, then always attempts `finish --live
"$PR18_GATE_A_LIVE" --archive "$PR18_GATE_A_ARCHIVE"`. Upload retains the sealed
tar/digest, or an unsealed/setup attempt, plus the preflight source manifest.
The archive contains raw command/logs/exits/elapsed/cleanup, API/tooling/native
samples, actual XML/results/APKs/hashes, passive receipts, original captures and
metadata/text, pre/post source manifests and `gate-a.json`.

XML must show four original restaurant assertion failures, zero errors/skips
and no missing/duplicate/unrelated cases. Continuous logcat must retain all ten
original font100 callback receipts and 28 accepted native DOWN/UP pairs per case;
font200 must fail before its first touch. Every logged original capture requires
its real PNG and complete `.capture.txt`; the native window adapter additionally
requires its original complete `.txt` (including both failure captures). The
Activity-image adapter does not produce that sidecar. Metadata commitment,
encoded hash/native geometry and PNG CRCs must agree. The partial
pulled measurement file cannot substitute for complete logcat. Source identity,
actual owners/fonts/IME, zero disposed product roots, pre/post-clear membership,
complete natural save, initial composition-boundary tab and new entry IDs must
agree. Unexpected pass or changed identity/outcome, missing Bundle/capture/log,
compile/selector/setup failure or timeout preserves an incomplete/unproven
attempt and defers correction according to PLAN's stop table. The producer
preserves raw outcomes; its contract status does not manufacture behavioral RED.
New captures never fill the three historical missing QUIET dark PNGs.

Local checks are pure Python source/contracts, native actionlint and bash -n;
no Gradle, emulator, npm or large builds on the 956MB host. Durable local proof:
`/tmp/dining-publish-20261108/ui-causal-probe-r1-scratch`. Native compilation,
noninterference, complete collection and causality remain unverified until the
reviewed candidate's actual API26 experiment and independent receipt review.
