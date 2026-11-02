#!/usr/bin/env python3
"""
cleanup_imports.py —— 根据扫描报告删除未使用 import

用法:
  python scripts/cleanup_imports.py [root_dir]

流程:
  1. 调用 scan_unused_imports.py 生成报告（如已有则重新生成）
  2. 读取报告，对每个文件删除列出的 import 行
  3. 返回码：0 = 成功，1 = 报告有异常

报告格式（同 scan_unused_imports.py 输出）:
  === relative/path.kt (删 N) ===
    -import fully.qualified.Name
    ...
"""

import sys
from pathlib import Path

from scan_unused_imports import find_kotlin_files, scan_file


def parse_report(report_path: Path) -> list[tuple[Path, list[str]]]:
    """解析报告，返回 [(相对路径, [未使用 import 列表])]"""
    if not report_path.exists():
        return []

    result = []
    current_file = None
    current_unused = []

    with report_path.open(encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("=== "):
                if current_file is not None:
                    result.append((current_file, current_unused))
                # 解析：=== rel/path.kt (删 N) ===
                inner = line[len("=== ") :].rstrip(" ===")
                rel = inner.split(" (删")[0].strip()
                current_file = Path(rel)
                current_unused = []
            elif line.startswith("  -import "):
                current_unused.append(line.strip())

        if current_file is not None:
            result.append((current_file, current_unused))

    return result


def apply_cleanup(project_root: Path, report_path: Path) -> bool:
    """应用清理，返回 True = 成功"""
    entries = parse_report(report_path)
    if not entries:
        print("报告中无条目，跳过")
        return True

    ok = True
    for rel, imports_to_remove in entries:
        fpath = project_root / rel
        if not fpath.exists():
            print(f"警告：文件不存在 {rel}", file=sys.stderr)
            ok = False
            continue

        text = fpath.read_text(encoding="utf-8")
        lines = text.splitlines(keepends=True)

        new_lines = []
        removed = 0
        for line in lines:
            stripped = line.strip()
            if stripped in imports_to_remove:
                removed += 1
                continue
            new_lines.append(line)

        if removed > 0:
            fpath.write_text("".join(new_lines), encoding="utf-8")
            print(f"  删除 {removed} 条：{rel}")

    return ok


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    if not root.exists():
        print(f"错误：根目录 {root} 不存在", file=sys.stderr)
        sys.exit(2)

    # 先生成/刷新报告
    from subprocess import run
    scan_result = run(
        [sys.executable, str(Path(__file__).parent / "scan_unused_imports.py"), str(root)],
        capture_output=True,
        text=True,
    )
    print(scan_result.stdout, end="")
    if scan_result.stderr:
        print(scan_result.stderr, file=sys.stderr, end="")
    if scan_result.returncode not in (0, 1):
        print("扫描失败", file=sys.stderr)
        sys.exit(2)

    report_path = root / "unused_imports_report.txt"
    if not report_path.exists():
        print("报告文件未生成，跳过清理")
        sys.exit(0)

    ok = apply_cleanup(root, report_path)
    if ok:
        print("\n✓ 清理完成，建议重跑扫描确认零残留")
        sys.exit(0)
    else:
        print("\n✗ 清理遇到异常", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
