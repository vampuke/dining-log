#!/usr/bin/env python3
"""Execute current source slices with CONTROL fixtures and pinned text matchers.

No Gradle, emulator, downloads, screenshots or physical-input claims. The adapter,
exposure condition and progress assertion are extracted, not reimplemented.
"""
import argparse
import hashlib
import json
import re
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = Path(__file__).resolve().parent
PRODUCT = ROOT / "android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product"
parser = argparse.ArgumentParser()
parser.add_argument("--receipt", required=True, type=Path)
args = parser.parse_args()
args.receipt.mkdir(parents=True, exist_ok=True)
source_path = PRODUCT / "Pr18Evidence.kt"
dialog_path = PRODUCT / "Pr18DialogAcceptanceTest.kt"
production_path = ROOT / "android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/UpdateDialog.kt"
source, dialog, production = (p.read_text() for p in (source_path, dialog_path, production_path))


def slice_between(text, start, end):
    offset = text.index(start)
    return text[offset:text.index(end, offset)].rstrip() + "\n"


def assertion_call(text):
    start = text.index('evidence.target("update-progress").assertText')
    opening = text.index("(", text.index(".assertText", start))
    depth, quoted, escaped = 0, False, False
    for pos in range(opening, len(text)):
        char = text[pos]
        if quoted:
            if escaped: escaped = False
            elif char == "\\": escaped = True
            elif char == '"': quoted = False
        elif char == '"': quoted = True
        elif char == "(": depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0: return text[start:pos + 1]
    raise AssertionError("Progress assertion has no closing parenthesis")


full_bounds = slice_between(source, "internal fun pr18FullBounds", "internal fun pr18OuterCoordinates")
outer_coordinates = slice_between(source, "internal fun pr18OuterCoordinates", "internal fun pr18TouchDetails")
geometry = slice_between(source, "    private data class TouchGeometry", "    private fun touchGeometry")
adapter = slice_between(source, "    private fun withOverlays", "    private fun logTouchGeometry")
exposure = slice_between(source, "            val exposed =", "            if (exposed && stable == g)")
progress_assertion = assertion_call(dialog)
progress_expression = slice_between(production, "GridText(if (state.stage == UpdateStage.VERIFYING)",
                                    ', Modifier.testTag("update-progress")').removeprefix("GridText(").strip()
filters = (EVIDENCE / "pinned/Filters.kt").read_text()
assertions = (EVIDENCE / "pinned/Assertions.kt").read_text()
pinned_manifest = json.loads((EVIDENCE / "pinned/source-manifest.json").read_text())
for entry in pinned_manifest["entries"]:
    if entry.get("saved"):
        assert hashlib.sha256((ROOT / entry["saved"]).read_bytes()).hexdigest() == entry["full_sha256"]
matchers = "\n".join(slice_between(filters, "fun " + name + "(", "\n/**") for name in ("hasText", "hasTextExactly"))
text_assertions = "\n".join(slice_between(assertions, "fun SemanticsNodeInteraction." + name + "(", "\n/**")
                             for name in ("assertTextContains", "assertTextEquals"))
generated = '''package top.alanvampuke.dininglog.product
import android.graphics.Rect as AndroidRect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import top.alanvampuke.dininglog.product.SemanticsProperties.Text
import top.alanvampuke.dininglog.product.SemanticsProperties.InputText
import top.alanvampuke.dininglog.product.SemanticsProperties.EditableText
''' + full_bounds + outer_coordinates + matchers + text_assertions + '''
class AdapterProbe(val compose: FixtureCompose, val windows: List<AccessibilityWindowInfo>) {
    val logs = mutableListOf<String>()
    private fun ownerWindow(view: View) = view.window
    private fun record(line: String) { logs += line }
    private fun interactiveWindows() = windows
    private fun windowRectangles(window: AccessibilityWindowInfo) = window.rectangles
    private fun keyboardRectangles(windows: List<AccessibilityWindowInfo>): List<AndroidRect> =
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.flatMap { it.rectangles }
''' + geometry + adapter + '''
    private fun observedGeometry(node: SemanticsNode): TouchGeometry {
        val view = node.root.view
        val full = pr18FullBounds(node)
        return withOverlays(node, TouchGeometry(view, view.window, full, node.boundsInRoot,
            full, node.boundsInRoot, full.center, Offset(view.x.toFloat(), view.y.toFloat()),
            view.visible, emptyList(), "CONTROL synthetic geometry"))
    }
    fun exposed(node: SemanticsNode): Boolean {
        val g = observedGeometry(node)
''' + exposure + '''
        return exposed
    }
    fun overlays(node: SemanticsNode) = observedGeometry(node).overlays
    fun target(tag: String) = SemanticsNodeInteraction(compose.nodes.single {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag
    })
    fun progress(state: ProgressState) {
        val evidence = this
''' + progress_assertion + '''
    }
}
fun productionProgress(state: ProgressState): String = ''' + progress_expression + "\n"


def kotlin_string(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def rectangle(text, key):
    match = re.search(re.escape(key) + r"=Rect.fromLTRB\(([^)]+)\)", text)
    assert match, (key, text)
    values = [float(v.strip()) for v in match[1].split(",")]
    assert len(values) == 4
    return ",".join(str(v) for v in values)


failure_path = EVIDENCE / "ci/full-failures.json"
controls = []
for failure in json.loads(failure_path.read_text()):
    trace = failure["trace"]
    if trace["category"] != "lower_paint_plane": continue
    covers = [str(row["node_id"]) + ";" + ";".join(
        rectangle(row["raw"], key) for key in ("semanticFull", "semanticClipped", "outerFull", "outerClipped"))
        for row in trace["overlay_observations"]]
    # Startup has no recorded target ID/tree. -9001 denotes the synthetic CONTROL
    # target; its order comes from the audited source, never fabricated CI metadata.
    target_id = trace.get("target", {}).get("id", -9001)
    controls.append(str(failure["api"]) + ":" + failure["name"] + "|" + str(target_id) + "|" +
                    "|".join(rectangle(trace["geometry_raw"], key)
                    for key in ("full", "clipped", "rootVisible")) + "|" + "~".join(covers))
assert len(controls) == 56
generated += '''fun observedFailureControls(): List<ObservedFailureControl> {
    fun rect(value: String): Rect {
        val parts = value.split(',').map { it.toFloat() }
        return Rect(parts[0], parts[1], parts[2], parts[3])
    }
    val observations = ''' + kotlin_string("\n".join(controls)).replace("\n", "\\n") + '''
    return observations.lineSequence().map { line ->
        val fields = line.split('|')
        val covers = fields[5].split('~').map { value ->
            val parts = value.split(';')
            ObservedCover(parts[0].toInt(), rect(parts[1]), rect(parts[2]), rect(parts[3]), rect(parts[4]))
        }
        ObservedFailureControl(fields[0], fields[1].toInt(), rect(fields[2]), rect(fields[3]), rect(fields[4]), covers)
    }.toList()
}
'''
snapshot = args.receipt / "extracted-source.kt.txt"
snapshot.write_text(generated)
inputs = {}
for path in (source_path, dialog_path, production_path, Path(__file__).resolve(),
             EVIDENCE / "pinned/Filters.kt", EVIDENCE / "pinned/Assertions.kt", failure_path,
             *sorted((EVIDENCE / "tests").glob("*.kt"))):
    inputs[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
inputs[str(snapshot)] = hashlib.sha256(snapshot.read_bytes()).hexdigest()
head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
jars = Path("/tmp/pegcheck/dl")
launcher_names = ("kotlin-compiler-embeddable-1.9.24.jar", "kotlin-stdlib-1.9.24.jar", "trove4j-1.0.20200330.jar", "annotations-13.0.jar")
test_names = ("kotlin-stdlib-1.9.24.jar", "junit-4.13.2.jar", "hamcrest-core-1.3.jar")
jar_hashes = {name: hashlib.sha256((jars / name).read_bytes()).hexdigest() for name in set(launcher_names + test_names)}
launcher = ":".join(str(jars / name) for name in launcher_names)
classpath = ":".join(str(jars / name) for name in test_names)
jvm = ["nice", "-n", "19", "java", "-Xmx256m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-Djava.awt.headless=true"]
results = []
with tempfile.TemporaryDirectory(prefix="pr18-runtime-controls-") as temporary:
    work = Path(temporary)
    extracted = work / "ExtractedAdapter.kt"
    extracted.write_text(generated)
    fixture_out, adapter_out, test_out = (work / name for name in ("fixtures", "adapter", "tests"))
    for directory in (fixture_out, adapter_out, test_out): directory.mkdir()
    compiler = jvm + ["-cp", launcher, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect"]
    tests = EVIDENCE / "tests/RuntimeAdapterControls.kt"
    fixtures = [str(p) for p in sorted((EVIDENCE / "tests").glob("*.kt")) if p != tests]
    fixture_cp = str(fixture_out) + ":" + classpath
    adapter_cp = str(adapter_out) + ":" + fixture_cp
    commands = [compiler + ["-cp", classpath, "-module-name", "control_fixtures", "-d", str(fixture_out), *fixtures],
                compiler + ["-cp", fixture_cp, "-module-name", "source_adapter", "-d", str(adapter_out), str(extracted)],
                compiler + ["-cp", adapter_cp, "-module-name", "adversarial_tests", "-d", str(test_out), str(tests)],
                jvm + ["-cp", str(test_out) + ":" + adapter_cp, "org.junit.runner.JUnitCore",
                       "top.alanvampuke.dininglog.product.RuntimeAdapterControls"]]
    for name, command in zip(("compile-fixtures", "compile-adapter", "compile-tests", "junit"), commands):
        try:
            result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
            output, exit_code = result.stdout, result.returncode
        except subprocess.TimeoutExpired as error:
            output = error.stdout or b""
            if isinstance(output, bytes): output = output.decode(errors="replace")
            output += f"\nHARNESS TIMEOUT after {error.timeout}s; {name} did not complete.\n"
            exit_code = 124  # Explicit harness timeout status; not a process/test failure count.
        (args.receipt / (name + ".log")).write_text(output)
        results.append({"name": name, "command": command, "exit_code": exit_code})
        print(name.upper(), "exit", exit_code, flush=True)
        print(output, end="", flush=True)
        if exit_code: break
receipt = {"executed_at_utc": datetime.now(timezone.utc).isoformat(), "execution_head": head,
           "command": "python3 -B app-runtime-fix-evidence/run-adversarial-tests.py --receipt " + str(args.receipt),
           "inputs_sha256": inputs, "tool_jars_sha256": jar_hashes, "steps": results,
           "scope": "source-extracted Kotlin adapter and pinned matchers with synthetic CONTROL observations",
           "android_green": "pending_real_Actions"}
(args.receipt / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
raise SystemExit(results[-1]["exit_code"])
