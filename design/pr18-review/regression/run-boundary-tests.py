#!/usr/bin/env python3
"""Run the real pure Kotlin boundary with existing jars; no Gradle or downloads."""
import argparse
import json
import hashlib
import pathlib
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--log-dir", type=pathlib.Path, required=True)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[3]
args.log_dir.mkdir(parents=True, exist_ok=True)
jars = pathlib.Path("/tmp/pegcheck/dl")
launcher = ":".join(str(jars / name) for name in (
    "kotlin-compiler-embeddable-1.9.24.jar", "kotlin-stdlib-1.9.24.jar",
    "trove4j-1.0.20200330.jar", "annotations-13.0.jar"))
classpath = ":".join(str(jars / name) for name in (
    "kotlin-stdlib-1.9.24.jar", "junit-4.13.2.jar", "hamcrest-core-1.3.jar"))
jvm = ["nice", "-n", "19", "java", "-Xmx192m", "-XX:+UseSerialGC", "-Djava.awt.headless=true"]
source = root / "android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr18EvidenceBoundary.kt"
tests = root / "design/pr18-review/regression/Pr18EvidenceBoundaryJvmTest.kt"
inputs = {}
for path in (source, tests):
    data = path.read_bytes()
    (args.log_dir / path.name).write_bytes(data)
    inputs[str(path.relative_to(root))] = hashlib.sha256(data).hexdigest()
(args.log_dir / "inputs.json").write_text(json.dumps(inputs, indent=2) + "\n")
out = args.log_dir / "classes"
out.mkdir(exist_ok=True)
commands = [jvm + ["-cp", launcher, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    "-no-stdlib", "-no-reflect", "-cp", classpath, "-d", str(out), str(source), str(tests)],
    jvm + ["-cp", str(out) + ":" + classpath, "org.junit.runner.JUnitCore",
        "top.alanvampuke.dininglog.product.Pr18EvidenceBoundaryJvmTest"]]
results = []
for name, command in zip(("compile", "junit"), commands):
    result = subprocess.run(command, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (args.log_dir / (name + ".log")).write_text(result.stdout)
    results.append({"name": name, "command": command, "exit_code": result.returncode})
    print(name.upper(), "exit", result.returncode)
    print(result.stdout, end="")
    if result.returncode:
        break
(args.log_dir / "commands.json").write_text(json.dumps(results, indent=2) + "\n")
raise SystemExit(results[-1]["exit_code"])
