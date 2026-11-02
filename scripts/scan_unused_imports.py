#!/usr/bin/env python3
"""
scan_unused_imports.py —— 全工程扫描未使用 import

用法:
  python scripts/scan_unused_imports.py [root_dir]

输出:
  - 报告文件：<root_dir>/unused_imports_report.txt（格式：=== 路径 (删 N) === + 逐行 -import ...）
  - 终端摘要
  - 返回码：0 = 无残留，1 = 有残留

例外规则:
  - androidx.compose.runtime.getValue / setValue：若文件正文含 ` by ` 委托语法则保留
"""

import re
import sys
from pathlib import Path

# 委托例外：这些 import 若正文含 ` by ` 则保留
DELEGATE_EXCEPTIONS = {
    "androidx.compose.runtime.getValue",
    "androidx.compose.runtime.setValue",
}


def find_kotlin_files(root: Path) -> list[Path]:
    """查找所有 .kt 文件，排除 .git 与 .venv"""
    excluded = {".git", ".venv", "build", ".gradle"}
    files = []
    for path in root.rglob("*.kt"):
        if any(part in excluded for part in path.parts):
            continue
        files.append(path)
    return files


def extract_imports(text: str) -> list[str]:
    """提取所有 import 行（完整 import 语句）"""
    lines = text.splitlines()
    imports = []
    for line in lines:
        if line.startswith("import "):
            imports.append(line.strip())
    return imports


def body_contains_name(body: str, simple_name: str) -> bool:
    """检查正文（非 import 行）中是否含 simple_name（词边界匹配）"""
    # 简单名可能是嵌套类，如 A.B.C，词边界按最后一段
    pattern = rf"\b{re.escape(simple_name)}\b"
    return re.search(pattern, body) is not None


def has_delegate_syntax(body: str) -> bool:
    """检查正文是否含 ` by ` 委托语法（如 ` by remember { ... }`）"""
    return " by " in body


def scan_file(kotlin_file: Path, project_root: Path) -> tuple[Path, list[str]]:
    """扫描单个文件，返回 (相对路径, 未使用 import 列表)"""
    text = kotlin_file.read_text(encoding="utf-8")
    lines = text.splitlines()
    body_lines = [l for l in lines if not l.startswith("import ")]
    body = "\n".join(body_lines)
    has_by = has_delegate_syntax(body)

    unused = []
    for imp in extract_imports(text):
        # imp 形如 "import kotlinx.coroutines.Dispatchers"
        fq = imp[len("import "):].strip()
        simple = fq.split(".")[-1] if "." in fq else fq

        # 例外规则
        if fq in DELEGATE_EXCEPTIONS and has_by:
            continue  # 保留

        if not body_contains_name(body, simple):
            unused.append(imp)

    return (kotlin_file.relative_to(project_root), unused)


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    if not root.exists():
        print(f"错误：根目录 {root} 不存在", file=sys.stderr)
        sys.exit(2)

    files = find_kotlin_files(root)
    if not files:
        print("未找到 Kotlin 文件")
        sys.exit(0)

    report_lines: list[str] = []
    total_unused = 0
    files_with_unused = 0

    for f in files:
        rel, unused = scan_file(f, root)
        if unused:
            total_unused += len(unused)
            files_with_unused += 1
            report_lines.append(f"=== {rel.as_posix()} (删 {len(unused)}) ===")
            report_lines.extend(f"  -{imp}" for imp in unused)

    # 写报告
    report_path = root / "unused_imports_report.txt"
    report_path.write_text("\n".join(report_lines) + "\n", encoding="utf-8")

    # 终端摘要
    print(f"扫描 {len(files)} 个 Kotlin 文件")
    print(f"有未使用 import 的文件数：{files_with_unused}")
    print(f"未使用 import 总数：{total_unused}")
    print(f"报告文件：{report_path}")

    if total_unused > 0:
        print("\n--- 前 5 个文件摘要 ---")
        for line in report_lines[:15]:
            print(line)
        print("\n...")
        sys.exit(1)
    else:
        print("\n✓ 无残留未使用 import")
        sys.exit(0)


if __name__ == "__main__":
    main()
