#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""主代理独立验收用：统计 :app:testDebugUnitTest 的 XML 结果。

用法（构建跑完后）：
    "C:/Users/oscur/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe" \
        .task/count-tests.py

输出：测试类数 / 用例数 / failures / errors / skipped，以及失败明细。
不采信子代理自述，一律以本脚本复算结果为准。
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PROJ = Path(__file__).resolve().parent.parent
ROOT = PROJ / "app" / "build" / "test-results" / "testDebugUnitTest"


def main() -> int:
    if not ROOT.is_dir():
        print("找不到测试结果目录：%s" % ROOT)
        print("请先跑：JAVA_HOME=... ./gradlew :app:testDebugUnitTest --rerun-tasks")
        return 2

    files = sorted(ROOT.glob("TEST-*.xml"))
    if not files:
        print("目录存在但没有 TEST-*.xml：%s" % ROOT)
        return 2

    classes = 0
    tests = 0
    failures = 0
    errors = 0
    skipped = 0
    bad = []

    for f in files:
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as e:
            bad.append("%s: XML 解析失败 %s" % (f.name, e))
            continue
        # 兼容 <testsuite> 作为根 或 <testsuites> 包裹
        suites = [root] if root.tag == "testsuite" else list(root)
        for s in suites:
            if s.tag != "testsuite":
                continue
            classes += 1
            t = int(s.get("tests", 0))
            fa = int(s.get("failures", 0))
            er = int(s.get("errors", 0))
            sk = int(s.get("skipped", 0))
            tests += t
            failures += fa
            errors += er
            skipped += sk
            if fa or er:
                for tc in s.iter("testcase"):
                    for node in list(tc):
                        if node.tag in ("failure", "error"):
                            bad.append(
                                "%s.%s: %s"
                                % (tc.get("classname"), tc.get("name"),
                                   (node.get("message") or "")[:200])
                            )

    print("测试结果目录：%s" % ROOT)
    print("测试类数 : %d" % classes)
    print("用例总数 : %d" % tests)
    print("failures : %d" % failures)
    print("errors   : %d" % errors)
    print("skipped  : %d" % skipped)
    if bad:
        print("\n失败明细（%d 条）：" % len(bad))
        for line in bad:
            print("  - %s" % line)
    ok = (failures == 0 and errors == 0)
    print("\n判定：%s" % ("✅ 全绿" if ok else "❌ 有失败"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
