"""fibonacci.py 的单元测试。

运行方式（在项目根目录）：
    py -m unittest discover -s test -p "test_*.py" -v
"""

import os
import sys
import unittest

# 让测试可以直接导入同目录下的 fibonacci 模块
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fibonacci as fib  # noqa: E402


class TestFibonacciValues(unittest.TestCase):
    def test_known_values(self):
        expected = {1: 1, 2: 1, 3: 2, 4: 3, 5: 5, 10: 55, 11: 89, 12: 144, 20: 6765}
        for n, value in expected.items():
            with self.subTest(n=n):
                self.assertEqual(fib.fibonacci(n), value)

    def test_recurrence_relation(self):
        for n in range(3, 25):
            with self.subTest(n=n):
                self.assertEqual(
                    fib.fibonacci(n),
                    fib.fibonacci(n - 1) + fib.fibonacci(n - 2),
                )

    def test_invalid_input(self):
        for n in (0, -1, -100):
            with self.subTest(n=n):
                with self.assertRaises(ValueError):
                    fib.fibonacci(n)

    def test_large_index(self):
        # 大整数支持：F(100) 是已知值
        self.assertEqual(fib.fibonacci(100), 354224848179261915075)


class TestHeartSuffix(unittest.TestCase):
    def test_heart_constant(self):
        self.assertEqual(fib.HEART, "\u2665")

    def test_format_line_ends_with_heart(self):
        line = fib.format_line(10)
        self.assertTrue(line.endswith(" " + fib.HEART), f"实际输出: {line!r}")
        self.assertEqual(line, "fibonacci(10) = 55 \u2665")

    def test_format_line_contains_value(self):
        for n in (1, 7, 12, 30):
            with self.subTest(n=n):
                line = fib.format_line(n)
                self.assertIn(str(fib.fibonacci(n)), line)
                self.assertTrue(line.endswith(fib.HEART))


class TestRandomTargets(unittest.TestCase):
    def test_returns_three_unique_sorted_values(self):
        targets = fib.pick_random_targets(3, low=1, high=30)
        self.assertEqual(len(targets), 3)
        self.assertEqual(len(set(targets)), 3, "下标不应重复")
        self.assertEqual(targets, sorted(targets), "结果应升序")
        for n in targets:
            self.assertGreaterEqual(n, 1)
            self.assertLessEqual(n, 30)

    def test_count_exceeds_range(self):
        with self.assertRaises(ValueError):
            fib.pick_random_targets(5, low=1, high=3)


class TestReport(unittest.TestCase):
    def test_report_lines(self):
        targets = [10, 11, 12]
        report = fib.build_report(targets)
        lines = report.strip().splitlines()

        self.assertEqual(lines[0], "斐波那契数列计算结果")
        self.assertEqual(lines[1], "=" * 30)
        self.assertEqual(
            lines[2:],
            [
                "fibonacci(10) = 55 \u2665",
                "fibonacci(11) = 89 \u2665",
                "fibonacci(12) = 144 \u2665",
            ],
        )
        # 每个数值行都以爱心结尾
        for line in lines[2:]:
            self.assertTrue(line.endswith(fib.HEART))

    def test_report_ends_with_newline(self):
        self.assertTrue(fib.build_report([1, 2, 3]).endswith("\n"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
