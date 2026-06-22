#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from platforms import Platform, active_platform, hex32

ROOT_DIR = Path(__file__).resolve().parent.parent
SIM_DIR = Path(__file__).resolve().parent
SELFTEST_BUILD = SIM_DIR / "selftest" / "build"
DEFAULT_MODE = os.environ.get("CL1_TEST_MODE", "bus").strip().lower() or "bus"

BUILD_SCRIPT = SIM_DIR / "build.sh"
SELFTEST_SCRIPT = SIM_DIR / "build_test_programs.sh"
DEFAULT_RISCV_DV_ROOT = ROOT_DIR.parent / "riscv-dv"
DEFAULT_RISCV_DV_SUITE = DEFAULT_RISCV_DV_ROOT / "verification_output" / "rv32imc_mmode_directed_suite"

IMAGE_SUFFIXES = (".elf", ".bin", ".hex")
IMAGE_EXT_BY_NAME = {"elf": ".elf", "bin": ".bin", "hex": ".hex"}
RISCV_DV_SUFFIXES = (".bin", ".elf", ".o")
RISCV_DV_CASE_CONTAINER_DIRS = ("testcases", "asm_test", "directed_asm_test")
CSV_FIELDS = ["pc", "instr", "gpr", "csr", "binary", "mode", "instr_str", "operand", "pad"]
COMPARE_SUMMARY_RE = re.compile(
    r"^\[(?P<status>PASSED|FAILED)\]: (?P<matched>\d+) matched(?:, (?P<mismatch>\d+) mismatch)?$",
    re.MULTILINE,
)

GROUP_TITLES = {
    "smoke": "Smoke",
    "core": "Core Behavior",
    "interrupt": "Interrupts",
    "harness": "Simulation Harness",
}

ABI_NAMES = [
    "zero",
    "ra",
    "sp",
    "gp",
    "tp",
    "t0",
    "t1",
    "t2",
    "s0",
    "s1",
    "a0",
    "a1",
    "a2",
    "a3",
    "a4",
    "a5",
    "a6",
    "a7",
    "s2",
    "s3",
    "s4",
    "s5",
    "s6",
    "s7",
    "s8",
    "s9",
    "s10",
    "s11",
    "t3",
    "t4",
    "t5",
    "t6",
]

COMMIT_RE = re.compile(
    r"^CMT order=(?P<order>\d+)\s+"
    r"pc=0x(?P<pc>[0-9a-fA-F]+)\s+"
    r"insn=0x(?P<insn>[0-9a-fA-F]+)\s+"
    r"rd=(?P<rd>\d+)\s+"
    r"wdata=0x(?P<wdata>[0-9a-fA-F]+)\s+"
    r"trap=(?P<trap>\d+)\s*$"
)


@dataclass(frozen=True)
class Case:
    name: str
    image: Path
    symbol_elf: Path | None = None
    spike_log: Path | None = None


@dataclass
class CaseResult:
    case: Case
    run_ok: bool
    run_elapsed: float
    run_summary: str
    run_log: Path
    compare_status: str = "skip"
    compare_summary: str = "compare disabled"
    compare_log: Path | None = None


@dataclass
class CheckResult:
    name: str
    ok: bool
    summary: str
    group: str = "general"


@dataclass(frozen=True)
class TraceEntry:
    row_index: int
    pc: str
    binary: str
    instr_str: str
    gpr: list[str]
    csr: list[str]
    mode: str


@dataclass(frozen=True)
class CompareSummary:
    status: str
    matched: int
    mismatches: int
    text: str


@dataclass(frozen=True)
class CompareSample:
    sample_index: int
    note: str
    spike: TraceEntry | None
    rtl: TraceEntry | None


def supports_color() -> bool:
    return sys.stdout.isatty()


def colorize(text: str, color: str) -> str:
    if not supports_color():
        return text
    colors = {
        "green": "\033[32m",
        "red": "\033[31m",
        "yellow": "\033[33m",
        "cyan": "\033[36m",
        "bold": "\033[1m",
    }
    return f"{colors[color]}{text}\033[0m"


def sim_bin(mode: str) -> Path:
    return SIM_DIR / "build" / mode / "cl1_verilator"


def build_env(mode: str, platform: Platform) -> dict[str, str]:
    env = os.environ.copy()
    env["CL1_TEST_MODE"] = mode
    env["CL1_PLATFORM"] = platform.name
    env["CL1_ADDRESS_PROFILE"] = platform.name
    return env


def run_command(cmd: list[str], *, env: dict[str, str] | None = None, cwd: Path | None = None) -> int:
    return subprocess.run(cmd, env=env, cwd=cwd).returncode


def ensure_sim_built(binary: Path, mode: str, platform: Platform, build_script: Path = BUILD_SCRIPT) -> None:
    subprocess.run([str(build_script)], check=True, env=build_env(mode, platform))
    if not binary.exists():
        raise RuntimeError(f"simulator was not produced: {binary}")


def ensure_selftests_built(mode: str, platform: Platform) -> None:
    subprocess.run([str(SELFTEST_SCRIPT)], check=True, env=build_env(mode, platform))


def strip_separator(args: list[str]) -> list[str]:
    if args and args[0] == "--":
        return args[1:]
    return args


def output_lines(output: str) -> list[str]:
    return [line.strip() for line in output.splitlines() if line.strip()]


def summarize_output(output: str, fallback: str = "(no simulator output)") -> str:
    lines = output_lines(output)
    if not lines:
        return fallback

    sim_verdicts = ("[sim] PASS", "[sim] FAIL", "[sim] TIMEOUT", "[sim] LOAD-ERROR")
    for line in reversed(lines):
        if line.startswith(sim_verdicts):
            return line

    for line in reversed(lines):
        if line.startswith("Summary:") or line.startswith("Regression summary:"):
            return line

    for line in lines:
        if line.startswith("Usage:"):
            return f"cli: {line}"

    for line in reversed(lines):
        if line.startswith("[guest]"):
            return line

    return lines[-1]


def sanitize_case_name(name: str) -> str:
    return name.replace("/", "__")


def preference_map(prefer_image_type: str) -> dict[str, int]:
    ordered = [IMAGE_EXT_BY_NAME[prefer_image_type]]
    ordered.extend(ext for ext in IMAGE_SUFFIXES if ext not in ordered)
    return {ext: index for index, ext in enumerate(ordered)}


def discover_image_cases(root: Path, prefer_image_type: str) -> list[Case]:
    preference = preference_map(prefer_image_type)
    groups: dict[str, list[Path]] = {}
    for ext in IMAGE_SUFFIXES:
        for path in root.rglob(f"*{ext}"):
            if not path.is_file():
                continue
            key = str(path.relative_to(root).with_suffix(""))
            groups.setdefault(key, []).append(path)

    cases: list[Case] = []
    for key in sorted(groups):
        paths = sorted(groups[key], key=lambda path: preference[path.suffix.lower()])
        chosen = paths[0]
        symbol_elf = next((path for path in paths if path.suffix.lower() == ".elf"), None)
        cases.append(Case(name=key, image=chosen, symbol_elf=symbol_elf))
    return cases


def choose_riscv_dv_image(paths: list[Path]) -> Path:
    for suffix in RISCV_DV_SUFFIXES:
        match = next((path for path in paths if path.suffix.lower() == suffix), None)
        if match is not None:
            return match
    raise RuntimeError("no runnable image found")


def choose_riscv_dv_symbol_elf(paths: list[Path], image: Path) -> Path | None:
    if image.suffix.lower() in (".elf", ".o"):
        return image
    for suffix in (".elf", ".o"):
        match = next((path for path in paths if path.suffix.lower() == suffix), None)
        if match is not None:
            return match
    return None


def discover_riscv_dv_flat_cases(suite_root: Path) -> list[Case]:
    search_root = suite_root
    for candidate in RISCV_DV_CASE_CONTAINER_DIRS:
        candidate_path = suite_root / candidate
        if candidate_path.is_dir():
            search_root = candidate_path
            break

    groups: dict[str, list[Path]] = {}
    for suffix in RISCV_DV_SUFFIXES:
        for path in search_root.rglob(f"*{suffix}"):
            if not path.is_file():
                continue
            key = str(path.relative_to(search_root).with_suffix(""))
            groups.setdefault(key, []).append(path)

    cases: list[Case] = []
    for key in sorted(groups):
        paths = sorted(groups[key])
        image = choose_riscv_dv_image(paths)
        symbol_elf = choose_riscv_dv_symbol_elf(paths, image)
        base_name = Path(key).name
        spike_candidates = [
            image.parent / "spike.log",
            suite_root / "spike_sim" / f"{base_name}.log",
            suite_root / f"{base_name}.log",
        ]
        spike_log = next((path for path in spike_candidates if path.is_file()), None)
        cases.append(Case(name=key, image=image, symbol_elf=symbol_elf, spike_log=spike_log))
    return cases


def discover_riscv_dv_separated_cases(suite_root: Path) -> list[Case]:
    cases_root = suite_root / "testcases"
    cases: list[Case] = []
    for case_dir in sorted(path for path in cases_root.iterdir() if path.is_dir()):
        artifacts = sorted(
            path for path in case_dir.iterdir() if path.is_file() and path.suffix.lower() in RISCV_DV_SUFFIXES
        )
        if not artifacts:
            continue
        image = choose_riscv_dv_image(artifacts)
        symbol_elf = choose_riscv_dv_symbol_elf(artifacts, image)
        spike_log = case_dir / "spike.log"
        cases.append(
            Case(
                name=case_dir.name,
                image=image,
                symbol_elf=symbol_elf,
                spike_log=spike_log if spike_log.is_file() else None,
            )
        )
    return cases


def discover_riscv_dv_cases(suite_root: Path) -> list[Case]:
    if (suite_root / "testcases").is_dir():
        cases = discover_riscv_dv_separated_cases(suite_root)
        if cases:
            return cases
    return discover_riscv_dv_flat_cases(suite_root)


def has_riscv_dv_artifact(root: Path) -> bool:
    if not root.is_dir():
        return False
    for suffix in RISCV_DV_SUFFIXES:
        if any(path.is_file() for path in root.rglob(f"*{suffix}")):
            return True
    return False


def has_direct_riscv_dv_artifact(root: Path) -> bool:
    if not root.is_dir():
        return False
    return any(path.is_file() and path.suffix.lower() in RISCV_DV_SUFFIXES for path in root.iterdir())


def is_riscv_dv_suite_root(root: Path) -> bool:
    return any((root / container).is_dir() and has_riscv_dv_artifact(root / container) for container in RISCV_DV_CASE_CONTAINER_DIRS)


def discover_riscv_dv_suite_roots(input_root: Path) -> list[Path]:
    if is_riscv_dv_suite_root(input_root) or has_direct_riscv_dv_artifact(input_root):
        return [input_root]

    suite_roots: list[Path] = []
    seen: set[Path] = set()
    for container in RISCV_DV_CASE_CONTAINER_DIRS:
        for container_root in input_root.rglob(container):
            if not container_root.is_dir() or not has_riscv_dv_artifact(container_root):
                continue
            suite_root = container_root.parent
            if suite_root in seen:
                continue
            seen.add(suite_root)
            suite_roots.append(suite_root)

    if suite_roots:
        return sorted(suite_roots)

    if discover_riscv_dv_flat_cases(input_root):
        return [input_root]
    return []


def prefix_case(case: Case, prefix: str) -> Case:
    if not prefix:
        return case
    return Case(
        name=f"{prefix}/{case.name}",
        image=case.image,
        symbol_elf=case.symbol_elf,
        spike_log=case.spike_log,
    )


def discover_riscv_dv_input_cases(input_root: Path) -> tuple[list[Case], list[Path]]:
    suite_roots = discover_riscv_dv_suite_roots(input_root)
    multi_suite_input = len(suite_roots) > 1 or (len(suite_roots) == 1 and suite_roots[0] != input_root)
    cases: list[Case] = []

    for suite_root in suite_roots:
        prefix = ""
        if multi_suite_input:
            try:
                prefix = str(suite_root.relative_to(input_root))
            except ValueError:
                prefix = suite_root.name
        cases.extend(prefix_case(case, prefix) for case in discover_riscv_dv_cases(suite_root))

    return cases, suite_roots


def run_sim_case(
    case: Case,
    binary: Path,
    log_path: Path,
    max_cycles: int,
    extra_sim_args: Iterable[str],
    *,
    commit_log: Path | None = None,
) -> tuple[bool, float, str]:
    cmd = [str(binary), "--max-cycles", str(max_cycles)]
    if case.image.suffix.lower() == ".o":
        cmd.extend(["--image-type", "elf"])
    if commit_log is not None:
        cmd.extend(["--commit-log", str(commit_log)])
    if case.symbol_elf is not None and case.symbol_elf != case.image:
        cmd.extend(["--symbol-elf", str(case.symbol_elf)])
    cmd.extend(extra_sim_args)
    cmd.append(str(case.image))

    start = time.perf_counter()
    completed = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    elapsed = time.perf_counter() - start

    output = completed.stdout or ""
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(output, encoding="utf-8")
    return completed.returncode == 0, elapsed, summarize_output(output)


def run_image_directory(
    tests_root: Path,
    binary: Path,
    logs_dir: Path,
    max_cycles: int,
    extra_sim_args: Iterable[str],
    prefer_image_type: str,
    case_filter: str,
) -> int:
    if not binary.exists():
        print(f"error: simulator not found: {binary}", file=sys.stderr)
        return 1
    if not tests_root.exists():
        print(f"error: tests directory does not exist: {tests_root}", file=sys.stderr)
        return 1

    cases = discover_image_cases(tests_root, prefer_image_type)
    if case_filter:
        cases = [case for case in cases if case_filter in case.name]
    if not cases:
        print(f"error: no .elf/.bin/.hex tests found under {tests_root}", file=sys.stderr)
        return 1

    print(colorize(f"Running {len(cases)} test(s) with {binary} (prefer {prefer_image_type})", "cyan"))
    passed = 0
    failed = 0
    for index, case in enumerate(cases, start=1):
        log_path = logs_dir / f"{sanitize_case_name(case.name)}.log"
        ok, elapsed, summary = run_sim_case(
            case=case,
            binary=binary,
            log_path=log_path,
            max_cycles=max_cycles,
            extra_sim_args=extra_sim_args,
        )
        status = colorize("PASS", "green") if ok else colorize("FAIL", "red")
        print(f"[{index:>3}/{len(cases):>3}] {status} {case.name} ({elapsed:.2f}s)  {summary}")
        if ok:
            passed += 1
        else:
            failed += 1
            print(f"      log: {log_path}")

    total = passed + failed
    summary = f"Summary: {passed} passed, {failed} failed, {total} total"
    print(colorize(summary, "green" if failed == 0 else "red"))
    return 0 if failed == 0 else 1


def split_trace_field(text: str) -> list[str]:
    return [item for item in text.split(";") if item] if text else []


def normalize_instr_text(text: str) -> str:
    return " ".join(text.split())


def load_trace_csv(path: Path) -> list[TraceEntry]:
    entries: list[TraceEntry] = []
    with path.open("r", encoding="utf-8", newline="") as csv_file:
        reader = csv.DictReader(csv_file)
        for row_index, row in enumerate(reader, start=1):
            entries.append(
                TraceEntry(
                    row_index=row_index,
                    pc=row["pc"],
                    binary=row["binary"],
                    instr_str=normalize_instr_text(row["instr_str"]),
                    gpr=split_trace_field(row["gpr"]),
                    csr=split_trace_field(row["csr"]),
                    mode=row["mode"],
                )
            )
    return entries


def write_trace_csv(path: Path, entries: list[TraceEntry]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for entry in entries:
            writer.writerow(
                {
                    "pc": entry.pc,
                    "instr": "",
                    "gpr": ";".join(entry.gpr),
                    "csr": ";".join(entry.csr),
                    "binary": entry.binary,
                    "mode": entry.mode,
                    "instr_str": entry.instr_str,
                    "operand": "",
                    "pad": "",
                }
            )


def convert_commit_log_to_trace_csv(log_path: Path, csv_path: Path) -> None:
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("r", encoding="utf-8") as src, csv_path.open("w", encoding="utf-8", newline="") as dst:
        writer = csv.DictWriter(dst, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for lineno, line in enumerate(src, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            match = COMMIT_RE.match(stripped)
            if not match:
                raise RuntimeError(f"unrecognized commit log format at {log_path}:{lineno}: {stripped}")

            rd = int(match.group("rd"), 10)
            gpr_update = ""
            if rd != 0:
                gpr_update = f"{ABI_NAMES[rd]}:{match.group('wdata').lower()[0:8].zfill(8)}"

            writer.writerow(
                {
                    "pc": match.group("pc").lower()[0:8].zfill(8),
                    "instr": "",
                    "gpr": gpr_update,
                    "csr": "",
                    "binary": match.group("insn").lower()[0:8].zfill(8),
                    "mode": "3",
                    "instr_str": "",
                    "operand": "",
                    "pad": "",
                }
            )


def is_mip_read(entry: TraceEntry) -> bool:
    try:
        insn = int(entry.binary, 16)
    except ValueError:
        return False
    return (insn & 0x7F) == 0x73 and ((insn >> 20) & 0xFFF) == 0x344 and ((insn >> 7) & 0x1F) != 0


def is_counter_read(entry: TraceEntry) -> bool:
    try:
        insn = int(entry.binary, 16)
    except ValueError:
        return False
    counter_csrs = {0xB00, 0xB02, 0xB80, 0xB82}
    return (insn & 0x7F) == 0x73 and ((insn >> 20) & 0xFFF) in counter_csrs and ((insn >> 7) & 0x1F) != 0


def normalize_platform_gpr(entry: TraceEntry) -> TraceEntry:
    normalize_mip = is_mip_read(entry)
    normalize_counter = is_counter_read(entry)
    if not normalize_mip and not normalize_counter:
        return entry

    normalized_gpr = []
    for update in entry.gpr:
        if ":" not in update:
            normalized_gpr.append(update)
            continue
        reg, value = update.split(":", 1)
        normalized_value = 0 if normalize_counter else int(value, 16) & ~0x888
        normalized_gpr.append(f"{reg}:{normalized_value:08x}")

    return TraceEntry(
        row_index=entry.row_index,
        pc=entry.pc,
        binary=entry.binary,
        instr_str=entry.instr_str,
        gpr=normalized_gpr,
        csr=entry.csr,
        mode=entry.mode,
    )


def apply_gpr_update(entry: TraceEntry, gpr_state: dict[str, str]) -> bool:
    if not entry.gpr:
        return False

    state_changed = False
    for update in entry.gpr:
        reg, value = update.split(":", 1)
        prev_value = gpr_state.get(reg)
        if prev_value is None:
            if int(value, 16) != 0:
                state_changed = True
        elif prev_value != value:
            state_changed = True
        gpr_state[reg] = value
    return state_changed


def count_state_changes(entries: list[TraceEntry]) -> int:
    gpr_state: dict[str, str] = {}
    count = 0
    for entry in entries:
        if apply_gpr_update(entry, gpr_state):
            count += 1
    return count


def truncate_rtl_after_terminal_ecall(
    spike_entries: list[TraceEntry],
    rtl_entries: list[TraceEntry],
) -> list[TraceEntry]:
    if not spike_entries:
        return rtl_entries

    terminal = spike_entries[-1]
    terminal_is_ecall = terminal.binary == "00000073" or normalize_instr_text(terminal.instr_str) == "ecall"
    if not terminal_is_ecall or terminal.gpr:
        return rtl_entries

    target_state_changes = count_state_changes(spike_entries)
    if target_state_changes == 0:
        return []

    rtl_state: dict[str, str] = {}
    observed_state_changes = 0
    for index, entry in enumerate(rtl_entries):
        if apply_gpr_update(entry, rtl_state):
            observed_state_changes += 1
            if observed_state_changes == target_state_changes:
                return rtl_entries[: index + 1]

    for index, entry in enumerate(rtl_entries):
        if entry.pc == terminal.pc and entry.binary == terminal.binary:
            return rtl_entries[: index + 1]
    return rtl_entries


def state_update_entries(entries: list[TraceEntry]) -> list[TraceEntry]:
    return [entry for entry in entries if entry.gpr]


def state_update_prefix_matches(spike_entries: list[TraceEntry], rtl_entries: list[TraceEntry]) -> bool:
    spike_updates = state_update_entries(spike_entries)
    rtl_updates = state_update_entries(rtl_entries)
    if len(rtl_updates) > len(spike_updates):
        return False
    for spike_entry, rtl_entry in zip(spike_updates, rtl_updates):
        if spike_entry.pc != rtl_entry.pc:
            return False
        if spike_entry.binary != rtl_entry.binary:
            return False
        if spike_entry.gpr != rtl_entry.gpr:
            return False
    return True


def truncate_after_n_state_updates(entries: list[TraceEntry], state_update_count: int) -> list[TraceEntry]:
    if state_update_count <= 0:
        return []
    observed = 0
    for index, entry in enumerate(entries):
        if entry.gpr:
            observed += 1
            if observed == state_update_count:
                return entries[: index + 1]
    return entries


def truncate_spike_to_rtl_terminal_store(
    spike_entries: list[TraceEntry],
    rtl_entries: list[TraceEntry],
) -> list[TraceEntry]:
    if not rtl_entries:
        return spike_entries
    if not state_update_prefix_matches(spike_entries, rtl_entries):
        return spike_entries

    terminal = rtl_entries[-1]
    opcode = int(terminal.binary[-2:], 16) & 0x7F if len(terminal.binary) >= 2 else 0
    if opcode == 0x23 and not terminal.gpr and not terminal.csr:
        return truncate_after_n_state_updates(spike_entries, len(state_update_entries(rtl_entries)))
    return spike_entries


def prepare_compare_csvs(spike_csv: Path, rtl_csv: Path, spike_out: Path, rtl_out: Path) -> tuple[Path, Path]:
    spike_entries = [normalize_platform_gpr(entry) for entry in load_trace_csv(spike_csv)]
    rtl_entries = [normalize_platform_gpr(entry) for entry in load_trace_csv(rtl_csv)]
    rtl_entries = truncate_rtl_after_terminal_ecall(spike_entries, rtl_entries)
    spike_entries = truncate_spike_to_rtl_terminal_store(spike_entries, rtl_entries)

    write_trace_csv(spike_out, spike_entries)
    write_trace_csv(rtl_out, rtl_entries)
    return spike_out, rtl_out


def enrich_trace_entries(entries: list[TraceEntry], reference_entries: list[TraceEntry]) -> list[TraceEntry]:
    by_pc_and_binary: dict[tuple[str, str], str] = {}
    by_binary: dict[str, set[str]] = {}
    for entry in reference_entries:
        if not entry.instr_str:
            continue
        by_pc_and_binary.setdefault((entry.pc, entry.binary), entry.instr_str)
        by_binary.setdefault(entry.binary, set()).add(entry.instr_str)

    unique_by_binary = {binary: next(iter(instrs)) for binary, instrs in by_binary.items() if len(instrs) == 1}

    enriched: list[TraceEntry] = []
    for entry in entries:
        instr_str = entry.instr_str
        if not instr_str:
            instr_str = by_pc_and_binary.get((entry.pc, entry.binary), unique_by_binary.get(entry.binary, ""))
        enriched.append(
            TraceEntry(
                row_index=entry.row_index,
                pc=entry.pc,
                binary=entry.binary,
                instr_str=instr_str,
                gpr=entry.gpr,
                csr=entry.csr,
                mode=entry.mode,
            )
        )
    return enriched


def parse_compare_summary(compare_text: str) -> CompareSummary | None:
    matches = list(COMPARE_SUMMARY_RE.finditer(compare_text))
    if not matches:
        return None
    match = matches[-1]
    return CompareSummary(
        status=match.group("status"),
        matched=int(match.group("matched")),
        mismatches=int(match.group("mismatch") or 0),
        text=match.group(0),
    )


def classify_sample(spike: TraceEntry | None, rtl: TraceEntry | None) -> str:
    if spike is not None and rtl is None:
        return "Spike still has architectural updates after RTL trace stopped matching."
    if spike is None and rtl is not None:
        return "RTL still has architectural updates after Spike trace stopped matching."
    if spike is None or rtl is None:
        return "Trace mismatch."
    if spike.pc != rtl.pc:
        return "PC diverged before the next architectural state update."
    if spike.binary != rtl.binary:
        return "Same compare slot, but the committed instruction binary differs."
    if spike.gpr != rtl.gpr:
        return "Same instruction slot, but the committed register update differs."
    return "Trace mismatch."


def collect_compare_samples(
    spike_entries: list[TraceEntry],
    rtl_entries: list[TraceEntry],
    limit: int = 8,
) -> list[CompareSample]:
    samples: list[CompareSample] = []
    spike_state: dict[str, str] = {}
    rtl_state: dict[str, str] = {}
    rtl_cursor = 0

    for spike_entry in spike_entries:
        if not apply_gpr_update(spike_entry, spike_state):
            continue

        rtl_entry: TraceEntry | None = None
        while rtl_cursor < len(rtl_entries):
            candidate = rtl_entries[rtl_cursor]
            rtl_cursor += 1
            if apply_gpr_update(candidate, rtl_state):
                rtl_entry = candidate
                break

        if rtl_entry is None:
            samples.append(CompareSample(len(samples) + 1, classify_sample(spike_entry, None), spike_entry, None))
            return samples

        if spike_entry.gpr != rtl_entry.gpr:
            samples.append(CompareSample(len(samples) + 1, classify_sample(spike_entry, rtl_entry), spike_entry, rtl_entry))
            if len(samples) >= limit:
                return samples

    while rtl_cursor < len(rtl_entries) and len(samples) < limit:
        candidate = rtl_entries[rtl_cursor]
        rtl_cursor += 1
        if apply_gpr_update(candidate, rtl_state):
            samples.append(CompareSample(len(samples) + 1, classify_sample(None, candidate), None, candidate))
    return samples


def format_trace_entry(entry: TraceEntry) -> str:
    parts = [f"pc=0x{entry.pc}", f"bin=0x{entry.binary}"]
    if entry.instr_str:
        parts.append(f'instr="{entry.instr_str}"')
    if entry.gpr:
        parts.append(f"gpr={', '.join(entry.gpr)}")
    if entry.csr:
        parts.append(f"csr={', '.join(entry.csr)}")
    return " ".join(parts)


def build_compare_report(case: Case, spike_csv: Path, rtl_csv: Path, raw_compare_log: Path) -> str:
    raw_compare_text = raw_compare_log.read_text(encoding="utf-8").strip()
    summary = parse_compare_summary(raw_compare_text)

    spike_entries = load_trace_csv(spike_csv)
    rtl_entries = enrich_trace_entries(load_trace_csv(rtl_csv), spike_entries)
    compare_samples = []
    if summary is None or summary.status != "PASSED":
        compare_samples = collect_compare_samples(spike_entries, rtl_entries)

    report: list[str] = []
    report.append(f"Compare Report: {case.name}")
    report.append("")
    report.append("Summary")
    if summary is not None:
        report.append(f"  status: {summary.status}")
        report.append(f"  matched architectural updates: {summary.matched}")
        report.append(f"  mismatched architectural updates: {summary.mismatches}")
    else:
        report.append("  status: UNKNOWN")
    report.append(f"  spike trace rows: {len(spike_entries)}")
    report.append(f"  rtl trace rows: {len(rtl_entries)}")
    report.append(f"  spike state updates: {count_state_changes(spike_entries)}")
    report.append(f"  rtl state updates: {count_state_changes(rtl_entries)}")
    report.append("")
    report.append("Artifacts")
    report.append(f"  spike csv: {spike_csv}")
    report.append(f"  rtl csv: {rtl_csv}")
    report.append(f"  raw compare log: {raw_compare_log}")

    if compare_samples:
        report.append("")
        report.append("Sample Mismatches")
        for sample in compare_samples:
            report.append(f"  {sample.sample_index}. {sample.note}")
            if sample.spike is not None:
                report.append(f"     spike row {sample.spike.row_index}: {format_trace_entry(sample.spike)}")
            if sample.rtl is not None:
                report.append(f"     rtl row {sample.rtl.row_index}: {format_trace_entry(sample.rtl)}")

    report.append("")
    report.append("Raw Compare Output")
    report.append(raw_compare_text or "(empty)")
    return "\n".join(report) + "\n"


def compare_case(case: Case, case_dir: Path, riscv_dv_root: Path) -> tuple[str, str, Path | None]:
    if case.spike_log is None:
        return "skip", "no spike.log found", None

    spike_to_csv = riscv_dv_root / "scripts" / "spike_log_to_trace_csv.py"
    trace_compare = riscv_dv_root / "scripts" / "instr_trace_compare.py"
    if not spike_to_csv.exists() or not trace_compare.exists():
        return "error", f"missing riscv-dv compare scripts under {riscv_dv_root}", None

    rtl_commit = case_dir / "rtl_commit.log"
    rtl_csv = case_dir / "rtl.csv"
    spike_csv = case_dir / "spike.csv"
    rtl_compare_csv = case_dir / "rtl.compare.csv"
    spike_compare_csv = case_dir / "spike.compare.csv"
    compare_log = case_dir / "compare.log"
    raw_compare_log = case_dir / "compare.raw.log"

    convert_commit_log_to_trace_csv(rtl_commit, rtl_csv)
    subprocess.run(
        [sys.executable, str(spike_to_csv), "--log", str(case.spike_log), "--csv", str(spike_csv)],
        check=True,
        capture_output=True,
        text=True,
    )
    prepared_spike_csv, prepared_rtl_csv = prepare_compare_csvs(
        spike_csv=spike_csv,
        rtl_csv=rtl_csv,
        spike_out=spike_compare_csv,
        rtl_out=rtl_compare_csv,
    )
    if raw_compare_log.exists():
        raw_compare_log.unlink()
    subprocess.run(
        [
            sys.executable,
            str(trace_compare),
            "--csv_file_1",
            str(prepared_spike_csv),
            "--csv_file_2",
            str(prepared_rtl_csv),
            "--csv_name_1",
            "spike",
            "--csv_name_2",
            "rtl",
            "--mismatch_print_limit",
            "20",
            "--log",
            str(raw_compare_log),
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    compare_text = raw_compare_log.read_text(encoding="utf-8")
    compare_log.write_text(build_compare_report(case, prepared_spike_csv, prepared_rtl_csv, raw_compare_log), encoding="utf-8")
    summary = parse_compare_summary(compare_text)
    if summary is None:
        return "error", "compare completed without a final status marker", compare_log
    if summary.status == "PASSED":
        return "pass", summary.text, compare_log
    if summary.status == "FAILED":
        return "fail", summary.text, compare_log
    return "error", "compare completed without a final status marker", compare_log


def run_riscv_dv_case(
    case: Case,
    binary: Path,
    work_dir: Path,
    max_cycles: int,
    extra_sim_args: list[str],
    compare: bool,
    riscv_dv_root: Path,
) -> CaseResult:
    case_dir = work_dir / sanitize_case_name(case.name)
    case_dir.mkdir(parents=True, exist_ok=True)
    run_log = case_dir / "simulator.log"
    commit_log = case_dir / "rtl_commit.log" if compare else None

    ok, elapsed, summary = run_sim_case(
        case=case,
        binary=binary,
        log_path=run_log,
        max_cycles=max_cycles,
        extra_sim_args=extra_sim_args,
        commit_log=commit_log,
    )

    compare_status = "skip"
    compare_summary = "compare disabled"
    compare_log = None
    if compare:
        if ok:
            try:
                compare_status, compare_summary, compare_log = compare_case(
                    case=case,
                    case_dir=case_dir,
                    riscv_dv_root=riscv_dv_root,
                )
            except (RuntimeError, subprocess.CalledProcessError) as exc:
                compare_status = "error"
                compare_summary = str(exc)
        else:
            compare_status = "notrun"
            compare_summary = "simulation did not pass; compare not run"

    return CaseResult(
        case=case,
        run_ok=ok,
        run_elapsed=elapsed,
        run_summary=summary,
        run_log=run_log,
        compare_status=compare_status,
        compare_summary=compare_summary,
        compare_log=compare_log,
    )


def run_riscv_dv_directory(
    input_root: Path,
    binary: Path,
    work_dir: Path,
    max_cycles: int,
    extra_sim_args: list[str],
    compare: bool,
    riscv_dv_root: Path,
    case_filter: str,
) -> int:
    if not binary.exists():
        print(f"error: simulator not found: {binary}", file=sys.stderr)
        return 1
    if not input_root.exists():
        print(f"error: input directory does not exist: {input_root}", file=sys.stderr)
        return 1

    cases, suite_roots = discover_riscv_dv_input_cases(input_root)
    if case_filter:
        cases = [case for case in cases if case_filter in case.name]
    if not cases:
        print(f"error: no runnable .bin/.elf/.o cases found under {input_root}", file=sys.stderr)
        return 1

    work_dir.mkdir(parents=True, exist_ok=True)
    print(colorize(f"Running {len(cases)} riscv-dv case(s) with {binary}", "cyan"))
    print(f"  input: {input_root}")
    if len(suite_roots) == 1 and suite_roots[0] == input_root:
        print(f"  suite: {suite_roots[0]}")
    else:
        print(f"  suites: {len(suite_roots)}")
    print(f"  artifacts: {work_dir}")
    if extra_sim_args:
        print(f"  sim args: {' '.join(extra_sim_args)}")
    if compare:
        print(f"  compare: enabled against spike logs from {riscv_dv_root}")

    run_pass = 0
    run_fail = 0
    compare_pass = 0
    compare_fail = 0
    compare_skip = 0
    compare_notrun = 0
    compare_error = 0

    for index, case in enumerate(cases, start=1):
        result = run_riscv_dv_case(
            case=case,
            binary=binary,
            work_dir=work_dir,
            max_cycles=max_cycles,
            extra_sim_args=extra_sim_args,
            compare=compare,
            riscv_dv_root=riscv_dv_root,
        )

        run_status = colorize("RUN-PASS", "green") if result.run_ok else colorize("RUN-FAIL", "red")
        line = f"[{index:>3}/{len(cases):>3}] {run_status} {result.case.name} ({result.run_elapsed:.2f}s)"
        if compare:
            compare_color = {
                "pass": "green",
                "fail": "red",
                "skip": "yellow",
                "notrun": "yellow",
                "error": "red",
            }[result.compare_status]
            line += " " + colorize(f"CMP-{result.compare_status.upper()}", compare_color)
        print(line)

        if result.run_ok:
            run_pass += 1
        else:
            run_fail += 1
            print(f"      {result.run_summary}")
            print(f"      log: {result.run_log}")

        if compare:
            if result.compare_status == "pass":
                compare_pass += 1
            elif result.compare_status == "fail":
                compare_fail += 1
                print(f"      {result.compare_summary}")
                if result.compare_log is not None:
                    print(f"      compare: {result.compare_log}")
            elif result.compare_status == "skip":
                compare_skip += 1
                print(f"      {result.compare_summary}")
            elif result.compare_status == "notrun":
                compare_notrun += 1
                print(f"      {result.compare_summary}")
            else:
                compare_error += 1
                print(f"      {result.compare_summary}")
                if result.compare_log is not None:
                    print(f"      compare: {result.compare_log}")

    run_summary = f"Run summary: {run_pass} passed, {run_fail} failed, {len(cases)} total"
    print(colorize(run_summary, "green" if run_fail == 0 else "red"))

    if compare:
        compare_summary = (
            f"Compare summary: {compare_pass} passed, {compare_fail} failed, "
            f"{compare_skip} skipped, {compare_notrun} not run, {compare_error} error"
        )
        print(colorize(compare_summary, "green" if compare_fail == 0 and compare_error == 0 else "red"))

    if run_fail != 0:
        return 1
    if compare and (compare_fail != 0 or compare_error != 0):
        return 2
    return 0


def selftest_artifact(name: str, ext: str) -> Path:
    return SELFTEST_BUILD / f"{name}.{ext}"


def run_checked_command(
    name: str,
    cmd: list[str],
    group: str = "general",
    expected_rc: int = 0,
    must_contain: list[str] | None = None,
    must_not_contain: list[str] | None = None,
    artifact: Path | None = None,
) -> CheckResult:
    completed = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    output = completed.stdout or ""

    ok = completed.returncode == expected_rc
    if must_contain:
        ok = ok and all(token in output for token in must_contain)
    if must_not_contain:
        ok = ok and all(token not in output for token in must_not_contain)
    if artifact is not None:
        ok = ok and artifact.exists() and artifact.stat().st_size > 0

    summary = summarize_output(output, f"rc={completed.returncode}")
    if expected_rc != 0:
        summary = f"expected rc={expected_rc}: {summary}"
    if not ok:
        print(colorize(f"[regression] {name} failed", "red"), file=sys.stderr)
        print("Command:", " ".join(cmd), file=sys.stderr)
        print(output, file=sys.stderr)
    return CheckResult(name=name, ok=ok, summary=summary, group=group)


def stage_case_dir(dst: Path, names: list[str]) -> None:
    dst.mkdir(parents=True, exist_ok=True)
    for name in names:
        for ext in ("elf", "bin", "hex"):
            src = selftest_artifact(name, ext)
            if src.exists():
                shutil.copy2(src, dst / src.name)


def smoke_checks(binary: Path, max_cycles: int) -> list[CheckResult]:
    return [
        run_checked_command(
            name="illegal-instruction",
            group="smoke",
            cmd=[str(binary), "--max-cycles", str(max_cycles), str(selftest_artifact("illegal_instruction_pass", "elf"))],
            must_contain=["PASS", "host exit register write"],
        )
    ]


def core_checks(binary: Path, platform: Platform, max_cycles: int) -> list[CheckResult]:
    fetch_fault_region_args = platform.selftest_region_args("fetch_fault_region")
    if not fetch_fault_region_args:
        raise RuntimeError(f"platform {platform.name} does not define selftest region fetch_fault_region")
    return [
        run_checked_command(
            name="ebreak-exception",
            group="core",
            cmd=[
                str(binary),
                "--no-ebreak-stop",
                "--max-cycles",
                str(max_cycles),
                str(selftest_artifact("ebreak_exception_pass", "elf")),
            ],
            must_contain=["PASS", "host exit register write"],
        ),
        run_checked_command(
            name="illegal-instruction",
            group="core",
            cmd=[str(binary), "--max-cycles", str(max_cycles), str(selftest_artifact("illegal_instruction_pass", "elf"))],
            must_contain=["PASS", "host exit register write"],
        ),
        run_checked_command(
            name="access-fault",
            group="core",
            cmd=[str(binary), "--max-cycles", str(max_cycles), str(selftest_artifact("access_fault_pass", "elf"))],
            must_contain=["PASS", "host exit register write"],
        ),
        run_checked_command(
            name="instruction-access-fault",
            group="core",
            cmd=[
                str(binary),
                *fetch_fault_region_args,
                "--max-cycles",
                str(max_cycles),
                str(selftest_artifact("instruction_access_fault_pass", "elf")),
            ],
            must_contain=["PASS", "host exit register write"],
        ),
    ]


def cache_checks(binary: Path, max_cycles: int) -> list[CheckResult]:
    return [
        run_checked_command(
            name="fence-i-self-modify",
            group="core",
            cmd=[str(binary), "--max-cycles", str(max_cycles), str(selftest_artifact("fence_i_self_modify_pass", "elf"))],
            must_contain=["PASS", "host exit register write"],
        ),
        run_checked_command(
            name="fence-keeps-icache",
            group="core",
            cmd=[
                str(binary),
                "--max-cycles",
                str(max_cycles),
                str(selftest_artifact("fence_does_not_invalidate_icache_pass", "elf")),
            ],
            must_contain=["PASS", "host exit register write"],
        ),
    ]


def interrupt_checks(binary: Path, max_cycles: int) -> list[CheckResult]:
    results: list[CheckResult] = []
    interrupt_cases = [
        ("interrupt-external", "interrupt_external_pass", "ext", "11", "1:16", "8:8"),
        ("interrupt-software", "interrupt_software_pass", "sft", "12", "1:16", "8:8"),
        ("interrupt-timer", "interrupt_timer_pass", "tmr", "13", "1:16", "8:8"),
        ("interrupt-global-mask", "interrupt_global_mask_pass", "sft", "21", "1:16", "8:8"),
        ("interrupt-mie-mask", "interrupt_mie_mask_pass", "sft", "22", "1:16", "8:8"),
        ("interrupt-pending-enable", "interrupt_pending_enable_pass", "sft", "23", "1:16", "64:64"),
        ("interrupt-mret-status", "interrupt_mret_status_pass", "sft", "24", "1:16", "64:64"),
        ("interrupt-priority", "interrupt_priority_pass", "all", "25", "1:16", "24:24"),
        ("interrupt-vectored-software", "interrupt_vectored_software_pass", "sft", "26", "1:16", "8:8"),
        ("interrupt-short-pulse-drop", "interrupt_short_pulse_drop_pass", "sft", "27", "1:1", "1:1"),
        ("interrupt-exception-priority", "interrupt_exception_priority_pass", "sft", "28", "1:16", "128:128"),
    ]
    for test_name, artifact_name, irq_line, seed, delay, width in interrupt_cases:
        results.append(
            run_checked_command(
                name=test_name,
                group="interrupt",
                cmd=[
                    str(binary),
                    "--irq-lines",
                    irq_line,
                    "--irq-seed",
                    seed,
                    "--irq-delay",
                    delay,
                    "--irq-width",
                    width,
                    "--max-cycles",
                    str(max_cycles),
                    str(selftest_artifact(artifact_name, "elf")),
                ],
                must_contain=["PASS", "host exit register write"],
            )
        )
    return results


def harness_checks(binary: Path, platform: Platform, max_cycles: int) -> list[CheckResult]:
    results: list[CheckResult] = []
    load_addr = hex32(platform.load_addr)
    ram_base = hex32(platform.ram_base)
    ram_size = str(platform.ram_size)
    config_region_args = platform.selftest_region_args("test_region")
    if not config_region_args:
        raise RuntimeError(f"platform {platform.name} does not define selftest region test_region")

    results.append(run_checked_command(name="help", group="harness", cmd=[str(binary), "--help"], must_contain=["Usage:"]))
    results.append(
        run_checked_command(
            name="bin-host-exit",
            group="harness",
            cmd=[
                str(binary),
                "--image-type",
                "bin",
                "--load-addr",
                load_addr,
                "--ram-base",
                ram_base,
                "--ram-size",
                ram_size,
                "--max-cycles",
                str(max_cycles),
                str(selftest_artifact("host_exit_pass", "bin")),
            ],
            must_contain=["PASS", "host exit register write"],
        )
    )
    results.append(
        run_checked_command(
            name="hex-host-exit",
            group="harness",
            cmd=[
                str(binary),
                "--image-type",
                "hex",
                "--load-addr",
                load_addr,
                "--max-cycles",
                str(max_cycles),
                str(selftest_artifact("host_exit_pass", "hex")),
            ],
            must_contain=["PASS", "host exit register write"],
        )
    )
    results.append(
        run_checked_command(
            name="bin-sidecar-elf",
            group="harness",
            cmd=[
                str(binary),
                "--symbol-elf",
                str(selftest_artifact("tohost_pass", "elf")),
                "--max-cycles",
                str(max_cycles),
                str(selftest_artifact("tohost_pass", "bin")),
            ],
            must_contain=["PASS", "tohost write"],
        )
    )
    trace_path = SELFTEST_BUILD / "trace_smoke.fst"
    if trace_path.exists():
        trace_path.unlink()
    results.append(
        run_checked_command(
            name="trace-output",
            group="harness",
            cmd=[
                str(binary),
                "--trace",
                str(trace_path),
                "--max-cycles",
                str(max_cycles),
                str(selftest_artifact("host_exit_pass", "elf")),
            ],
            must_contain=["PASS"],
            artifact=trace_path,
        )
    )
    results.append(
        run_checked_command(
            name="verbose-output",
            group="harness",
            cmd=[
                str(binary),
                "--verbose",
                "--max-cycles",
                str(max_cycles),
                str(selftest_artifact("host_exit_pass", "bin")),
            ],
            must_contain=["[sim][cycle", "PASS"],
        )
    )
    results.append(
        run_checked_command(
            name="quiet-output",
            group="harness",
            cmd=[
                str(binary),
                "--quiet",
                "--max-cycles",
                str(max_cycles),
                str(selftest_artifact("host_exit_pass", "elf")),
            ],
            must_contain=["PASS"],
            must_not_contain=["loaded"],
        )
    )
    results.append(
        run_checked_command(
            name="custom-mmio",
            group="harness",
            cmd=[
                str(binary),
                "--host-exit-addr",
                "0x10000080",
                "--uart-addr",
                "0x10000084",
                "--max-cycles",
                str(max_cycles),
                str(selftest_artifact("custom_mmio_pass", "elf")),
            ],
            must_contain=["PASS", "CMMIO"],
        )
    )
    results.append(
        run_checked_command(
            name="config-region",
            group="harness",
            cmd=[
                str(binary),
                *config_region_args,
                "--max-cycles",
                str(max_cycles),
                str(selftest_artifact("config_region_pass", "elf")),
            ],
            must_contain=["PASS", "host exit register write"],
        )
    )
    results.append(
        run_checked_command(
            name="fail-detection",
            group="harness",
            cmd=[str(binary), "--max-cycles", str(max_cycles), str(selftest_artifact("host_exit_fail", "elf"))],
            expected_rc=1,
            must_contain=["FAIL"],
        )
    )
    return results


def harness_batch_checks(mode: str, platform: Platform, max_cycles: int) -> list[CheckResult]:
    results: list[CheckResult] = []
    with tempfile.TemporaryDirectory(prefix="cl1-regression-pass-") as tmpdir:
        pass_dir = Path(tmpdir) / "harness"
        stage_case_dir(pass_dir, ["host_exit_pass", "tohost_pass", "ebreak_pass"])
        for prefer in ("elf", "bin", "hex"):
            results.append(
                run_checked_command(
                    name=f"batch-pass-{prefer}",
                    group="harness",
                    cmd=[
                        sys.executable,
                        str(Path(__file__).resolve()),
                        "test",
                        "--no-build",
                        "--test-mode",
                        mode,
                        "--address-profile",
                        platform.name,
                        "--prefer-image-type",
                        prefer,
                        "--max-cycles",
                        str(max_cycles),
                        str(pass_dir),
                    ],
                    must_contain=["Summary:", "0 failed"],
                )
            )

    with tempfile.TemporaryDirectory(prefix="cl1-regression-neg-") as tmpdir:
        neg_dir = Path(tmpdir) / "negative"
        stage_case_dir(neg_dir, ["host_exit_pass", "host_exit_fail"])
        results.append(
            run_checked_command(
                name="batch-negative",
                group="harness",
                cmd=[
                    sys.executable,
                    str(Path(__file__).resolve()),
                    "test",
                    "--no-build",
                    "--test-mode",
                    mode,
                    "--address-profile",
                    platform.name,
                    "--prefer-image-type",
                    "elf",
                    "--max-cycles",
                    str(max_cycles),
                    str(neg_dir),
                ],
                expected_rc=1,
                must_contain=["Summary:", "1 failed"],
            )
        )

    return results


def report_regression_results(results: list[CheckResult], mode: str, platform: Platform) -> int:
    failed = 0
    name_width = max((len(result.name) for result in results), default=0)
    print(f"Regression results: mode={mode}, platform={platform.name}")
    groups = list(dict.fromkeys(result.group for result in results))
    for group in groups:
        print(f"  [{GROUP_TITLES.get(group, group)}]")
        for result in (item for item in results if item.group == group):
            if result.ok:
                print(f"    {colorize('PASS', 'green')} {result.name:<{name_width}}  {result.summary}")
            else:
                failed += 1
                print(f"    {colorize('FAIL', 'red')} {result.name:<{name_width}}  {result.summary}")

    summary = f"Regression summary: {len(results) - failed} passed, {failed} failed, {len(results)} total"
    print(colorize(summary, "green" if failed == 0 else "red"))
    return 0 if failed == 0 else 1


def run_regression_suite(binary: Path, mode: str, platform: Platform, suite: str, max_cycles: int) -> int:
    results: list[CheckResult] = []
    if suite == "smoke":
        results.extend(smoke_checks(binary, max_cycles))
    elif suite == "core":
        results.extend(core_checks(binary, platform, max_cycles))
    elif suite == "interrupt":
        results.extend(interrupt_checks(binary, max_cycles))
    elif suite == "harness":
        results.extend(harness_checks(binary, platform, max_cycles))
        results.extend(harness_batch_checks(mode, platform, max_cycles))
    elif suite in ("selftest", "direct", "full"):
        results.extend(core_checks(binary, platform, max_cycles))
        if mode == "cache":
            results.extend(cache_checks(binary, max_cycles))
        results.extend(interrupt_checks(binary, max_cycles))
    elif suite == "all":
        results.extend(core_checks(binary, platform, max_cycles))
        if mode == "cache":
            results.extend(cache_checks(binary, max_cycles))
        results.extend(interrupt_checks(binary, max_cycles))
        results.extend(harness_checks(binary, platform, max_cycles))
        results.extend(harness_batch_checks(mode, platform, max_cycles))
    else:
        raise RuntimeError(f"unknown regression suite: {suite}")
    return report_regression_results(results, mode, platform)


def resolve_default_sim_arg(arg_value: str, mode: str) -> Path:
    default_sim = SIM_DIR / "build" / DEFAULT_MODE / "cl1_verilator"
    if arg_value == str(default_sim):
        return sim_bin(mode).resolve()
    return Path(arg_value).resolve()


def cmd_build(args: argparse.Namespace) -> int:
    platform = active_platform(args.address_profile or None)
    return run_command([str(BUILD_SCRIPT)], env=build_env(args.mode, platform))


def cmd_selftest(args: argparse.Namespace) -> int:
    platform = active_platform(args.address_profile or None)
    return run_command([str(SELFTEST_SCRIPT)], env=build_env(args.mode, platform))


def cmd_run(args: argparse.Namespace) -> int:
    platform = active_platform(args.address_profile or None)
    binary = sim_bin(args.mode)
    if not args.no_build:
        rc = run_command([str(BUILD_SCRIPT)], env=build_env(args.mode, platform))
        if rc != 0:
            return rc
    if not binary.exists():
        print(f"error: simulator not found: {binary}", file=sys.stderr)
        return 1

    user_sim_args = strip_separator(args.sim_args)
    if not user_sim_args:
        print("error: run requires simulator arguments ending with an image path", file=sys.stderr)
        print("example: cl1_sim.py run -- --max-cycles 5000 path/to/test.elf", file=sys.stderr)
        return 2
    sim_args = platform.sim_args() + user_sim_args
    return run_command([str(binary), *sim_args])


def cmd_test(args: argparse.Namespace) -> int:
    try:
        platform = active_platform(args.address_profile or None)
        binary = resolve_default_sim_arg(args.sim, args.mode)
        build_script = Path(args.build_script).resolve()
        if not args.no_build:
            ensure_sim_built(binary, args.mode, platform, build_script)
        extra_sim_args = platform.sim_args(load_addr=args.load_addr or None) + args.sim_arg
        print(f"  platform: {platform.name}")
        return run_image_directory(
            tests_root=Path(args.tests_dir).resolve(),
            binary=binary,
            logs_dir=SIM_DIR / "build" / args.mode / "test_logs",
            max_cycles=args.max_cycles,
            extra_sim_args=extra_sim_args,
            prefer_image_type=args.prefer_image_type,
            case_filter=args.filter,
        )
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


def cmd_regression(args: argparse.Namespace) -> int:
    try:
        platform = active_platform(args.address_profile or None)
        binary = sim_bin(args.mode)
        if not args.no_build_sim:
            ensure_sim_built(binary, args.mode, platform)
        if not args.no_build_tests:
            ensure_selftests_built(args.mode, platform)
        if not binary.exists():
            raise RuntimeError(f"simulator not found: {binary}")
        return run_regression_suite(binary, args.mode, platform, args.suite, args.max_cycles)
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


def cmd_check(args: argparse.Namespace) -> int:
    regression_suites = {
        "quick": ["smoke"],
        "selftest": ["selftest"],
        "full": ["full"],
        "harness": ["harness"],
        "all": ["all"],
    }[args.level]

    for suite in regression_suites:
        rc = cmd_regression(
            argparse.Namespace(
                mode=args.mode,
                address_profile=args.address_profile,
                suite=suite,
                max_cycles=args.max_cycles,
                no_build_sim=args.no_build_sim,
                no_build_tests=args.no_build_tests,
            )
        )
        if rc != 0:
            return rc

    if args.level != "all" or args.no_riscv_dv:
        return 0

    return cmd_riscv_dv(
        argparse.Namespace(
            mode=args.mode,
            address_profile=args.address_profile,
            suite=args.riscv_dv_suite,
            sim=str(SIM_DIR / "build" / DEFAULT_MODE / "cl1_verilator"),
            build_script=str(BUILD_SCRIPT),
            no_build=True,
            compare=True,
            max_cycles=args.riscv_dv_max_cycles,
            filter="",
            riscv_dv_root=args.riscv_dv_root,
            work_dir="",
            sim_arg=[],
        )
    )


def cmd_riscv_dv(args: argparse.Namespace) -> int:
    try:
        platform = active_platform(args.address_profile or None)
        binary = resolve_default_sim_arg(args.sim, args.mode)
        build_script = Path(args.build_script).resolve()
        if not args.no_build:
            ensure_sim_built(binary, args.mode, platform, build_script)

        input_root = Path(args.suite or DEFAULT_RISCV_DV_SUITE).resolve()
        riscv_dv_root = Path(args.riscv_dv_root or DEFAULT_RISCV_DV_ROOT).resolve()
        work_dir = (
            Path(args.work_dir).resolve()
            if args.work_dir
            else (SIM_DIR / "build" / args.mode / "riscv_dv" / input_root.name).resolve()
        )
        extra_sim_args = platform.sim_args() + platform.riscv_dv_sim_args() + args.sim_arg
        if args.compare and "--no-ebreak-stop" not in extra_sim_args:
            extra_sim_args.append("--no-ebreak-stop")
        print(f"  platform: {platform.name}")
        return run_riscv_dv_directory(
            input_root=input_root,
            binary=binary,
            work_dir=work_dir,
            max_cycles=args.max_cycles,
            extra_sim_args=extra_sim_args,
            compare=args.compare,
            riscv_dv_root=riscv_dv_root,
            case_filter=args.filter,
        )
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


def cmd_trace_csv(args: argparse.Namespace) -> int:
    try:
        convert_commit_log_to_trace_csv(Path(args.log), Path(args.csv))
        return 0
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


def cmd_clean(_: argparse.Namespace) -> int:
    for path in (SIM_DIR / "build", SIM_DIR / "selftest" / "build"):
        if path.exists():
            shutil.rmtree(path)
            print(f"removed {path.relative_to(ROOT_DIR)}")
    return 0


def add_mode(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--mode",
        "--test-mode",
        choices=("bus", "cache"),
        default=DEFAULT_MODE,
        dest="mode",
        help="simulation top-level mode (default: CL1_TEST_MODE or bus)",
    )


def add_address_profile(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--address-profile",
        "--platform",
        dest="address_profile",
        default="",
        help="CL1 platform: simple_soc or full_soc",
    )


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="CL1 Verilator simulation driver")
    subparsers = parser.add_subparsers(dest="command", metavar="<command>")

    build = subparsers.add_parser("build", help="build the Verilator simulator")
    add_mode(build)
    add_address_profile(build)
    build.set_defaults(func=cmd_build)

    selftest = subparsers.add_parser("selftest", help="build built-in bare-metal selftests")
    add_mode(selftest)
    add_address_profile(selftest)
    selftest.set_defaults(func=cmd_selftest)

    run = subparsers.add_parser("run", help="run one ELF/BIN/HEX image")
    add_mode(run)
    add_address_profile(run)
    run.add_argument("--no-build", action="store_true", help="skip simulator build")
    run.add_argument("sim_args", nargs=argparse.REMAINDER, help="arguments passed to cl1_verilator")
    run.set_defaults(func=cmd_run)

    test = subparsers.add_parser("test", help="run a directory of ELF/BIN/HEX tests")
    add_mode(test)
    add_address_profile(test)
    test.add_argument("tests_dir", nargs="?", default="tests")
    test.add_argument("--sim", default=str(SIM_DIR / "build" / DEFAULT_MODE / "cl1_verilator"))
    test.add_argument("--build-script", default=str(BUILD_SCRIPT))
    test.add_argument("--no-build", action="store_true", help="skip simulator build")
    test.add_argument("--max-cycles", type=int, default=1_000_000)
    test.add_argument("--load-addr", default="")
    test.add_argument("--prefer-image-type", choices=("elf", "bin", "hex"), default="elf")
    test.add_argument("--filter", default="")
    test.add_argument("--sim-arg", action="append", default=[])
    test.set_defaults(func=cmd_test)

    regression = subparsers.add_parser("regression", help="run grouped built-in regression suites")
    add_mode(regression)
    add_address_profile(regression)
    regression.add_argument(
        "--suite",
        choices=("smoke", "core", "interrupt", "harness", "selftest", "direct", "full", "all"),
        default="full",
    )
    regression.add_argument("--max-cycles", type=int, default=5000)
    regression.add_argument("--no-build-sim", action="store_true")
    regression.add_argument("--no-build-tests", action="store_true")
    regression.set_defaults(func=cmd_regression)

    check = subparsers.add_parser("check", help="run the unified project validation flow")
    add_mode(check)
    add_address_profile(check)
    check.add_argument(
        "--level",
        choices=("quick", "selftest", "full", "harness", "all"),
        default="full",
        help="quick=smoke, selftest/full=processor core tests, harness=simulator framework tests, all=core+harness+riscv-dv compare",
    )
    check.add_argument("--max-cycles", type=int, default=20000)
    check.add_argument("--no-build-sim", action="store_true")
    check.add_argument("--no-build-tests", action="store_true")
    check.add_argument("--no-riscv-dv", action="store_true", help="skip riscv-dv when --level all is selected")
    check.add_argument("--riscv-dv-root", default="")
    check.add_argument("--riscv-dv-suite", default="")
    check.add_argument("--riscv-dv-max-cycles", type=int, default=200000)
    check.set_defaults(func=cmd_check)

    riscv_dv = subparsers.add_parser("riscv-dv", help="run riscv-dv generated cases")
    add_mode(riscv_dv)
    add_address_profile(riscv_dv)
    riscv_dv.add_argument("suite", nargs="?", default="")
    riscv_dv.add_argument("--sim", default=str(SIM_DIR / "build" / DEFAULT_MODE / "cl1_verilator"))
    riscv_dv.add_argument("--build-script", default=str(BUILD_SCRIPT))
    riscv_dv.add_argument("--no-build", action="store_true", help="skip simulator build")
    riscv_dv.add_argument("--compare", action="store_true")
    riscv_dv.add_argument("--max-cycles", type=int, default=1_000_000)
    riscv_dv.add_argument("--filter", default="")
    riscv_dv.add_argument("--riscv-dv-root", default="")
    riscv_dv.add_argument("--work-dir", default="")
    riscv_dv.add_argument("--sim-arg", action="append", default=[])
    riscv_dv.set_defaults(func=cmd_riscv_dv)

    trace_csv = subparsers.add_parser("trace-csv", help="convert CL1 RVFI commit log to riscv-dv trace CSV")
    trace_csv.add_argument("--log", required=True)
    trace_csv.add_argument("--csv", required=True)
    trace_csv.set_defaults(func=cmd_trace_csv)

    clean = subparsers.add_parser("clean", help="remove simulator and selftest build outputs")
    clean.set_defaults(func=cmd_clean)

    return parser


def main() -> int:
    parser = make_parser()
    args = parser.parse_args()
    if not hasattr(args, "func"):
        parser.print_help()
        return 2
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
