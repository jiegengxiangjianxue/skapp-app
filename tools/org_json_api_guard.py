#!/usr/bin/env python3
"""拦截「编译期对着 org.json:json、运行时对着 Android 内置 org.json」的 API 越界。

## 为什么需要这个守卫（vc19 真机闪退的根因）

`:engine` 用 `compileOnly(org.json:json:20240303)` 提供 org.json，而**运行期用的是 Android
平台内置的 `org.json`**。两者不是同一个库：

    android-35 的 android.jar -> JSONObject 48 个方法，没有 toMap()
    org.json:json:20240303    -> JSONObject 101 个方法，有 toMap()

于是 `json.toMap()` 能编译、能过 JVM 单测（测试也跑在那个 jar 上），但真机上
`NoSuchMethodError` 直接崩——这正是 vc19 前「一进决策页就闪退」的真因。
更坑的是 Kotlin **成员优先于扩展**：哪怕自己写了 `private fun JSONObject.toMap()`，
调用也会被解析成平台成员，自己那份成为死代码。

## 为什么只认字节码

源码 grep 看不见「成员 vs 扩展」的解析结果，也看不见编译器最终选了哪个方法。
所以本守卫按 JVMS 解析 class 文件常量池，把 Methodref/InterfaceMethodref 还原成
`owner.method(参数个数)`，再与 `android.jar` 的实际方法集逐项比对。

**注意**：class 里的方法引用是三个独立常量（`org/json/JSONObject`、`toMap`、
`()Ljava/util/Map;`），并不存在 `"org/json/JSONObject.toMap"` 这个字符串。第一版守卫拿后者做
子串匹配，恒为 0 命中——假阴性守卫比没有守卫更危险，因此本版自带正反向自检。

## 用法

    python3 tools/org_json_api_guard.py                                  # 只查黑名单
    python3 tools/org_json_api_guard.py --android-jar <android.jar>      # 白名单逐项比对（推荐）
    python3 tools/org_json_api_guard.py --self-test                      # 自检解析器

有 `--android-jar` 时做「白名单」全量比对（能发现任何平台缺失的方法）；
没有时退回黑名单（至少挡住已知炸弹）。发现命中即 exit 1。
"""
from __future__ import annotations

import re
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

# 已知会炸的方法（无 android.jar 时的兜底）。
BANNED = {
    "org/json/JSONObject.toMap": "Android 平台 org.json 没有 toMap()（任何 API 版本都没有）；改用 toPlainMap()",
    "org/json/JSONObject.toList": "Android 平台 org.json 没有 toList()；数组请用 JSONArray.toPlainList()",
    "org/json/JSONArray.toList": "Android 平台 org.json 没有 toList()；改用 toPlainList()",
}

# 正控：扫到的 org.json 引用里至少要有这些方法之一，否则说明扫错目录 / 解析器坏了。
CONTROL_METHODS = {"opt", "optJSONObject", "optString", "optInt", "keys", "length", "get"}

DEFAULT_DIRS = [
    "engine/build/classes",
    "ui/build/tmp/kotlin-classes",
    "ui/build/intermediates",
    "app/build/intermediates",
]

PRIMITIVES = set("VZBCSIJFD")

# 这些方法即使 android.jar 里该类没显式声明，运行时也一定可用（继承自 java.lang.Object）
OBJECT_METHODS = {("hashCode", 0), ("equals", 1), ("toString", 0), ("getClass", 0)}


def _arity(descriptor: str) -> int:
    """从 `(Ljava/lang/String;I)V` 里数出参数个数。"""
    try:
        body = descriptor[descriptor.index("(") + 1:descriptor.index(")")]
    except ValueError:
        return -1
    n = 0
    i = 0
    while i < len(body):
        ch = body[i]
        if ch == "[":
            i += 1
            continue
        if ch == "L":
            i = body.find(";", i)
            if i < 0:
                break
            i += 1
            n += 1
            continue
        if ch in PRIMITIVES:
            i += 1
            n += 1
            continue
        i += 1
    return n


def parse_method_refs(blob: bytes) -> set[tuple[str, str, int]]:
    """解析常量池，返回 {(owner, method, arity)}。"""
    if len(blob) < 10 or blob[:4] != b"\xca\xfe\xba\xbe":
        return set()
    try:
        count = struct.unpack_from(">H", blob, 8)[0]
        pos = 10
        utf8: dict[int, str] = {}
        classes: dict[int, int] = {}
        nats: dict[int, tuple[int, int]] = {}
        refs: list[tuple[int, int]] = []

        idx = 1
        while idx < count:
            tag = blob[pos]
            pos += 1
            if tag == 1:
                (length,) = struct.unpack_from(">H", blob, pos)
                pos += 2
                utf8[idx] = blob[pos:pos + length].decode("utf-8", "replace")
                pos += length
            elif tag in (7, 8, 16, 19, 20):
                (n,) = struct.unpack_from(">H", blob, pos)
                pos += 2
                if tag == 7:
                    classes[idx] = n
            elif tag == 15:
                pos += 3
            elif tag in (5, 6):
                pos += 8
                idx += 1
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
                if tag == 12:
                    a, b = struct.unpack_from(">HH", blob, pos)
                    nats[idx] = (a, b)
                elif tag in (10, 11):
                    a, b = struct.unpack_from(">HH", blob, pos)
                    refs.append((a, b))
                pos += 4
            else:
                return set()
            idx += 1

        out: set[tuple[str, str, int]] = set()
        for class_idx, nat_idx in refs:
            owner_idx = classes.get(class_idx)
            owner = utf8.get(owner_idx) if owner_idx else None
            nat = nats.get(nat_idx)
            if not owner or not nat:
                continue
            name = utf8.get(nat[0])
            desc = utf8.get(nat[1], "")
            if name and "/" in owner:
                out.add((owner, name, _arity(desc)))
        return out
    except (struct.error, IndexError):
        return set()


def scan(roots: list[Path]):
    scanned = 0
    seen: set[tuple[str, str, int]] = set()
    per_file: list[tuple[Path, set[tuple[str, str, int]]]] = []
    for root in roots:
        if not root.exists():
            continue
        for cls in root.rglob("*.class"):
            scanned += 1
            try:
                refs = parse_method_refs(cls.read_bytes())
            except OSError:
                continue
            if refs:
                per_file.append((cls, refs))
                seen |= refs
    return scanned, seen, per_file


def platform_api(android_jar: Path, owners: set[str]) -> tuple[dict[str, set[tuple[str, int]]], list[str]]:
    """用 javap 读出 android.jar 里这些 org.json 类的方法集。"""
    classes = sorted({o.replace("/", ".") for o in owners if o.startswith("org/json/")})
    if not classes:
        return {}, []
    with tempfile.TemporaryDirectory() as tmp:
        out_path = Path(tmp) / "javap.txt"
        with open(out_path, "w") as fh:
            subprocess.run(
                ["javap", "-classpath", str(android_jar), *classes],
                stdout=fh, stderr=subprocess.DEVNULL, check=False,
            )
        text = out_path.read_text(errors="replace")

    api: dict[str, set[tuple[str, int]]] = {}
    current = None
    for line in text.splitlines():
        s = line.strip()
        if "class " in s and s.startswith("public"):
            # javap 头行长这样：`public class org.json.JSONObject {`，末尾带 `{`，不能直接取最后一个 token
            m = re.search(r"class\s+([\w.$]+)", s)
            current = m.group(1) if m else None
            if current is None:
                continue
            api.setdefault(current.replace(".", "/"), set())
            continue
        if current is None or "(" not in s or not s.startswith("public"):
            continue
        head, rest = s.split("(", 1)
        name = head.split()[-1]
        if not rest.rstrip().endswith(";"):
            continue
        if "." in name:
            name = "<init>"  # javap 里构造器写成全限定类名，字节码里引用名是 <init>
        args = rest.rsplit(")", 1)[0]
        arity = 0 if not args.strip() else args.count(",") + 1
        api.setdefault(current.replace(".", "/"), set()).add((name, arity))

    missing = [o for o in owners if o.startswith("org/json/") and o not in api]
    return api, missing


def self_test() -> int:
    """构造含 org/json/JSONObject.toMap 引用的最小 class，断言解析器能抓到。"""
    def utf8(s: str) -> bytes:
        b = s.encode()
        return b"\x01" + struct.pack(">H", len(b)) + b

    pool = utf8("org/json/JSONObject")
    pool += b"\x07" + struct.pack(">H", 1)
    pool += utf8("toMap")
    pool += utf8("()Ljava/util/Map;")
    pool += b"\x0c" + struct.pack(">HH", 3, 4)
    pool += b"\x0a" + struct.pack(">HH", 2, 5)
    blob = b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 52, 7) + pool

    refs = parse_method_refs(blob)
    ok = ("org/json/JSONObject", "toMap", 0) in refs
    print(("self-test OK: " if ok else "self-test FAILED: ") + f"解析出 {sorted(refs)}")
    return 0 if ok else 1


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()

    android_jar = None
    args = argv[1:]
    if "--android-jar" in args:
        i = args.index("--android-jar")
        if i + 1 >= len(args):
            print("::error::--android-jar 缺少路径")
            return 2
        android_jar = Path(args[i + 1])
        if not android_jar.exists():
            print(f"::error::android.jar 不存在：{android_jar}")
            return 2
        args = args[:i] + args[i + 2:]

    roots = [Path(a) for a in args if not a.startswith("-")] or [Path(d) for d in DEFAULT_DIRS]

    scanned, seen, per_file = scan(roots)
    if scanned == 0:
        print("::error::没有扫到任何 .class 文件（构建目录不对？），守卫形同虚设")
        return 2

    json_refs = {r for r in seen if r[0].startswith("org/json/")}
    if not any(r[1] in CONTROL_METHODS for r in json_refs):
        print("::error::扫描结果里没有任何常见的 org.json 调用，判定为假阴性，拒绝通过")
        return 2

    problems: list[tuple[Path, str, str]] = []

    # 1) 黑名单（始终生效）
    for cls, refs in per_file:
        for owner, name, _ in refs:
            key = f"{owner}.{name}"
            if key in BANNED:
                problems.append((cls, key, BANNED[key]))

    # 2) 白名单：与 android.jar 的方法集逐项比对
    mode = "黑名单"
    if android_jar is not None:
        mode = f"白名单 {android_jar.name}"
        api, missing_classes = platform_api(android_jar, {r[0] for r in json_refs})
        for owner in missing_classes:
            problems.append((Path("-"), owner, "android.jar 里没有这个类"))
        for cls, refs in per_file:
            for owner, name, arity in refs:
                if not owner.startswith("org/json/"):
                    continue
                allowed = api.get(owner)
                if allowed is None or (name, arity) in allowed or (name, arity) in OBJECT_METHODS:
                    continue
                hint = "该方法/签名在 Android 平台 org.json 上不存在"
                problems.append((cls, f"{owner}.{name}/{arity}", hint))

    if problems:
        # 同一条引用可能同时被黑名单和黑名单外的白名单命中，按 owner.method 去重只报一次
        merged: dict[str, tuple[str, str]] = {}
        for cls, ref, why in problems:
            base = ref.rsplit("/", 1)[0] if ref.rsplit("/", 1)[-1].isdigit() else ref
            merged.setdefault(base, (why, str(cls)))
        print(f"::error::发现 {len(merged)} 处调用 Android 平台 org.json 不存在的方法（模式：{mode}）")
        for ref in sorted(merged):
            why, example = merged[ref]
            print(f"  {ref}\n      {why}\n      例：{example}")
        return 1

    print(f"org.json API 守卫通过（{mode}）：扫描 {scanned} 个 class，"
          f"{len(json_refs)} 个 org.json 方法引用全部在 Android 平台可用范围内")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
