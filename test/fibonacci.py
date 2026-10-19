"""计算斐波那契数列第 n 项的值。

约定：F(1) = 1, F(2) = 1, F(n) = F(n-1) + F(n-2)  (n >= 3)

输出格式：每个数值后面追加一个小爱心，例如 "fibonacci(10) = 55 ♥"
"""

import os
import random
import sys

# 追加在每个数值后面的小爱心
HEART = "\u2665"


def fibonacci(n: int) -> int:
    """返回斐波那契数列第 n 项（n >= 1）。

    使用迭代方式，时间复杂度 O(n)，空间复杂度 O(1)，
    并支持任意大的整数（Python 原生大整数）。
    """
    if n < 1:
        raise ValueError(f"n 必须是正整数，收到: {n}")
    a, b = 1, 1
    for _ in range(n - 1):
        a, b = b, a + b
    return a


def format_line(n: int) -> str:
    """把第 n 项格式化为带小爱心的输出行。"""
    return f"fibonacci({n}) = {fibonacci(n)} {HEART}"


def pick_random_targets(count: int = 3, low: int = 1, high: int = 30) -> list:
    """随机挑选 count 个不重复的下标，范围 [low, high]。"""
    if count > high - low + 1:
        raise ValueError("count 超过可选范围")
    return sorted(random.sample(range(low, high + 1), count))


def build_report(targets) -> str:
    """生成写入 result.txt 的完整文本内容。"""
    lines = [format_line(n) for n in targets]
    header = ["斐波那契数列计算结果", "=" * 30]
    return "\n".join(header + lines) + "\n"


def main() -> None:
    # Windows 控制台可能是 cp1252 等编码，避免打印中文/符号时崩溃
    try:
        sys.stdout.reconfigure(errors="replace")
    except AttributeError:
        pass

    # 随机计算 3 个下标
    targets = pick_random_targets(3)

    print(build_report(targets), end="")

    # 结果文件写在脚本所在目录，避免受当前工作目录影响
    output_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "result.txt")
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(build_report(targets))

    print(f"\nResult written to {output_path}")


if __name__ == "__main__":
    main()
