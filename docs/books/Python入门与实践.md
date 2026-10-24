# Python 入门与实践

Python 是一门强调可读性的通用编程语言。它既适合用几行代码自动处理文件，也能用于 Web 服务、数据分析和机器学习。本书面向第一次系统学习 Python 的读者，以 Python 3.10 及以上版本为基础，从运行第一段程序开始，逐步完成一个能够保存数据的命令行记账工具。

阅读建议：先理解概念，再运行示例，最后修改一处输入观察结果。遇到错误时先阅读最后一行异常信息，再沿着回溯定位代码。代码示例中的数字与结果均可直接核对。

## 1. 准备环境：解释器与虚拟环境

Python 解释器负责执行程序。安装 Python 3 后，在终端确认版本：

```bash
python --version
```

Windows 安装器通常提供 `py` 启动器，也可以运行 `py --version`。macOS 和 Linux 常使用 `python3`。后续命令中的 `python` 指你当前选择的 Python 3 解释器。

每个项目最好使用独立的虚拟环境，避免不同项目的第三方依赖互相影响。虚拟环境不是另一台机器，而是一个独立的解释器入口和依赖安装目录。

```bash
python -m venv .venv
```

Windows PowerShell 激活命令：

```powershell
.\.venv\Scripts\Activate.ps1
```

macOS / Linux 激活命令：

```bash
source .venv/bin/activate
```

如果不想激活，也可以直接调用虚拟环境中的解释器：Windows 使用 `.\.venv\Scripts\python.exe`，macOS / Linux 使用 `.venv/bin/python`。安装依赖时推荐 `python -m pip install 包名`，这样可以明确 pip 属于哪个解释器。结束后运行 `deactivate` 退出已激活的环境。

## 2. 第一个程序与缩进

把下面的代码保存为 `hello.py`，再运行 `python hello.py`：

```python
name = "Python"
print(f"你好，{name}！")
```

输出为 `你好，Python！`。`print()` 将内容写到终端，前缀 `f` 表示格式化字符串，花括号中的表达式会被替换为对应的值。

Python 用缩进表示代码块，而不是依靠大括号。约定使用四个空格，不混用 Tab 和空格：

```python
age = 18
if age >= 18:
    print("已成年")
else:
    print("未成年")
```

冒号后换行并缩进，是 `if`、`for`、`while`、函数定义等语句的共同结构。`#` 后面是注释，应说明意图或原因，避免只是重复代码。

## 3. 变量、类型与数值运算

变量名绑定到对象，赋值时不需要提前声明类型。同一个名字可以重新绑定到其他类型，但实际项目应保持含义稳定。

| 类型 | 含义 | 示例 |
| --- | --- | --- |
| `int` | 整数 | `18`、`-3` |
| `float` | 浮点数 | `1.75`、`0.1` |
| `str` | 字符串 | `"你好"` |
| `bool` | 布尔值 | `True`、`False` |
| `NoneType` | 缺少或尚无结果 | `None` |

```python
age = 18
height = 1.75
name = "小林"
is_student = True
result = None

print(type(age).__name__)  # int
print(7 / 2)              # 3.5，普通除法
print(7 // 2)             # 3，向下取整除法
print(7 % 2)              # 1，余数
print(2 ** 3)             # 8，乘方
```

`//` 是向下取整，例如 `-7 // 2` 得到 `-4`，不是 `-3`。浮点数使用二进制表示，`0.1 + 0.2` 通常不严格等于 `0.3`；金额计算应使用整数分或 `decimal.Decimal`。

## 4. 字符串、输入与类型转换

字符串不可变，索引从 0 开始，切片左闭右开。下面几行可分别运行：

```python
text = "Python"
print(text[0])       # P
print(text[-1])      # n
print(text[0:3])     # Pyt
print(text.lower())  # python

raw = "  hello  "
print(raw.strip())   # hello
print("a,b,c".split(","))  # ['a', 'b', 'c']
```

`input()` 返回字符串，即使输入的是数字，也需要转换后才能进行数值运算：

```python
raw_age = input("请输入年龄：")
age = int(raw_age)
print(f"明年你将 {age + 1} 岁。")
```

如果输入 `十八`，`int()` 会抛出 `ValueError`。不要假设所有输入都合法，第 9 章会演示如何处理这种错误。多行文本可使用三引号；在字符串中写反斜杠路径时，可以使用原始字符串，例如 `r"C:\books\hello.py"`。

## 5. 列表、元组、字典与集合

选择容器时，先确定数据之间是什么关系。

| 容器 | 适用场景 | 主要性质 |
| --- | --- | --- |
| `list` 列表 | 有顺序、需要增删的元素 | 可变，可重复 |
| `tuple` 元组 | 一组固定位置的数据 | 不可变，可重复 |
| `dict` 字典 | 根据键查找值 | 键唯一，保留插入顺序 |
| `set` 集合 | 去重、成员判断、交并差 | 不保证迭代顺序，元素唯一 |

```python
scores = [80, 95, 88]
scores.append(90)
print(scores[1])     # 95
print(len(scores))   # 4

point = (3, 5)
x, y = point

person = {"name": "小林", "age": 18}
print(person["name"])            # 小林
print(person.get("city", "未知"))  # 未知

tags = {"python", "学习", "python"}
print(len(tags))     # 2
```

字典键和集合元素必须可哈希，字符串、整数通常可以，列表和字典不可以。空字典写作 `{}`，空集合必须写作 `set()`。

列表赋值不会复制列表：`b = a` 后，两者指向同一个列表。需要独立的外层列表可用 `b = a.copy()`，但这只是浅拷贝；嵌套的可变对象仍可能共享。

## 6. 条件、循环与推导式

条件判断使用 `==` 比较值，`=` 用来赋值。`and`、`or`、`not` 组合逻辑条件；判断是否为 `None` 推荐使用 `is None`。

```python
score = 85
if score >= 90:
    level = "优秀"
elif score >= 60:
    level = "通过"
else:
    level = "需要补练"
print(level)  # 通过
```

`for` 遍历可迭代对象。`range(1, 4)` 生成 1、2、3，不包含 4；`enumerate()` 同时提供序号和值：

```python
for number in range(1, 4):
    print(number)

for index, item in enumerate(["安装", "运行", "练习"], start=1):
    print(index, item)

squares = [n * n for n in range(5) if n % 2 == 0]
print(squares)  # [0, 4, 16]
```

`while` 在条件成立时重复执行。`break` 退出当前循环，`continue` 跳过本轮剩余代码。推导式适合简洁的转换或筛选；如果逻辑太复杂，普通循环更容易阅读。

## 7. 函数：拆分可重复的工作

函数把一段行为命名，通过参数接收输入，通过 `return` 返回结果。没有执行 `return` 的函数默认返回 `None`。

```python
def average(values: list[float]) -> float:
    """计算非空数值列表的平均值。"""
    if not values:
        raise ValueError("列表不能为空")
    return sum(values) / len(values)

print(average([80, 90, 100]))  # 90.0
```

类型标注帮助读者和检查工具理解代码，但 Python 不会仅凭这些标注自动进行运行时类型校验。

默认参数只在函数定义时求值，因此不要把列表或字典直接作为需要独立状态的默认参数：

```python
def add_item(item: str, items: list[str] | None = None) -> list[str]:
    if items is None:
        items = []
    items.append(item)
    return items

print(add_item("A"))  # ['A']
print(add_item("B"))  # ['B']
```

这个写法避免多次调用意外共享同一个默认列表。函数优先完成一件事，输入与输出尽量明确，便于测试和复用。

## 8. 模块、包与标准库

一个 `.py` 文件可以作为模块，通过 `import` 复用。标准库随 Python 提供，例如 `pathlib` 处理路径，`json` 处理 JSON，`datetime` 处理日期时间。

```python
from pathlib import Path
from datetime import date

print(date(2026, 10, 13).isoformat())  # 2026-10-13
print(Path("notes") / "hello.txt")
```

安装第三方包前，确认它是否真的必要，标准库能完成的简单任务可以先不引入依赖。文件不要命名为 `json.py`、`random.py` 等标准库名字，以免遮蔽真正的模块。

入口文件常使用以下结构，保证导入模块时不会立即运行交互流程：

```python
def main() -> None:
    print("开始运行")

if __name__ == "__main__":
    main()
```

## 9. 异常处理：让失败可以理解

异常不是“程序没有任何结果”，而是执行过程中发生了无法继续当前路径的问题。应捕获能够处理的具体异常，不要无条件吞掉所有错误。

```python
def read_age(raw: str) -> int:
    try:
        age = int(raw)
    except ValueError as exc:
        raise ValueError("年龄必须是整数") from exc
    if age < 0:
        raise ValueError("年龄不能是负数")
    return age

try:
    print(read_age("十八"))
except ValueError as exc:
    print(exc)  # 年龄必须是整数
```

`try` 包住可能失败的操作，`except` 处理指定异常，`else` 可以放成功后的逻辑，`finally` 无论是否异常都会执行。使用 `with` 管理文件等资源，通常比手动在 `finally` 中关闭更清晰。

## 10. 文件与 JSON：把数据保存下来

文件路径可能相对于当前工作目录，而不是源码所在目录。读写文本时指定 UTF-8，减少不同操作系统之间的编码差异。

```python
from pathlib import Path
import json

path = Path("notes.json")
notes = [{"title": "学习 Python", "done": False}]
path.write_text(
    json.dumps(notes, ensure_ascii=False, indent=2),
    encoding="utf-8",
)
loaded = json.loads(path.read_text(encoding="utf-8"))
print(loaded[0]["title"])  # 学习 Python
```

`ensure_ascii=False` 让中文直接保留在 JSON 文本中。JSON 使用 `true`、`false`、`null`，反序列化到 Python 后对应 `True`、`False`、`None`。不能把所有 Python 对象直接保存为 JSON，例如 `datetime` 和集合通常需要先转换。

`write_text()` 会覆盖目标文件。读写重要数据前明确备份策略，不能把“保存成功”当作同时解决了并发写入和断电恢复。

## 11. 类与数据类：表达一组相关数据

当数据和行为具有明确关联时，可以定义类。`dataclass` 能为数据对象生成初始化和可读的文本表示，适合简单的结构化记录。

```python
from dataclasses import dataclass

@dataclass
class Expense:
    category: str
    cents: int

    def amount(self) -> str:
        return f"{self.cents // 100}.{self.cents % 100:02d}"

expense = Expense("午餐", 1850)
print(expense.amount())  # 18.50
```

这里用整数分保存金额，避免浮点误差；例如 18.50 元存为 1850 分。类不是必须使用的复杂结构：只有几个步骤的小脚本，使用函数和字典也可以表达清楚。

## 12. 实战：命令行记账工具

把以下完整程序保存为 `expenses.py`，运行 `python expenses.py`。它支持添加支出、查看记录和合计，并在脚本旁边保存 `expenses.json`。适合单人本地练习，不处理多个进程同时写入。

```python
import json
from decimal import Decimal, InvalidOperation
from pathlib import Path

DATA_FILE = Path(__file__).with_name("expenses.json")

def to_cents(raw: str) -> int:
    try:
        amount = Decimal(raw.strip())
    except InvalidOperation as exc:
        raise ValueError("请输入有效金额，例如 18.50") from exc
    if not amount.is_finite() or not Decimal("0") < amount <= Decimal("1000000"):
        raise ValueError("金额必须大于 0 且不超过 1000000 元")
    cents = amount * 100
    if cents != cents.to_integral_value():
        raise ValueError("金额最多保留两位小数")
    return int(cents)

def format_money(cents: int) -> str:
    return f"{cents // 100}.{cents % 100:02d}"

def load_records() -> list[dict]:
    if not DATA_FILE.exists():
        return []
    records = json.loads(DATA_FILE.read_text(encoding="utf-8"))
    if not isinstance(records, list):
        raise ValueError("数据文件必须是记录列表")
    for record in records:
        if (not isinstance(record, dict)
            or not isinstance(record.get("category"), str)
            or not record["category"].strip()
            or type(record.get("cents")) is not int
            or not 0 < record["cents"] <= 100000000):
            raise ValueError("数据文件包含无效记录")
    return records

def save_records(records: list[dict]) -> None:
    temporary = DATA_FILE.with_suffix(".json.tmp")
    temporary.write_text(
        json.dumps(records, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    temporary.replace(DATA_FILE)

def main() -> None:
    try:
        records = load_records()
    except (OSError, ValueError) as exc:
        print(f"无法读取账本：{exc}。请检查文件，避免覆盖原数据。")
        return

    while True:
        command = input("\n1 添加 / 2 查看 / 3 合计 / 0 退出：").strip()
        if command == "0":
            break
        if command == "1":
            category = input("类别：").strip()
            if not category:
                print("类别不能为空")
                continue
            try:
                cents = to_cents(input("金额（元）："))
                updated = records + [{"category": category, "cents": cents}]
                save_records(updated)
            except (ValueError, OSError) as exc:
                print(f"未保存：{exc}")
                continue
            records = updated
            print("已保存")
        elif command == "2":
            if not records:
                print("还没有记录，请先添加一笔支出。")
            for index, record in enumerate(records, start=1):
                print(index, record["category"], format_money(record["cents"]), "元")
        elif command == "3":
            total = sum(record["cents"] for record in records)
            print("合计：", format_money(total), "元")
        else:
            print("请输入 0、1、2 或 3")

if __name__ == "__main__":
    main()
```

程序先用 `Decimal` 校验金额，再转换成整数分保存。先写临时文件，再替换账本，可以降低直接覆盖中途失败造成的风险，但不提供跨进程锁或完整的断电持久性保证。保存失败时不改变内存中的记录；旧文件解析失败时退出，避免把损坏的账本直接当作空列表覆盖。

试着添加两笔支出：午餐 18.50 元、交通 3.00 元，合计应为 **21.50 元**。退出再运行，记录应仍然存在。

## 13. 测试与调试

测试应验证可观察的结果和边界。把下面的内容保存为同目录下的 `test_expenses.py`，运行 `python -m unittest -v`：

```python
import unittest
from expenses import to_cents, format_money

class MoneyTests(unittest.TestCase):
    def test_conversion(self):
        self.assertEqual(to_cents("18.50"), 1850)
        self.assertEqual(to_cents("3"), 300)
        self.assertEqual(format_money(2150), "21.50")

    def test_invalid_amount(self):
        for raw in ["abc", "0", "-1", "1.001", "NaN", "Infinity"]:
            with self.subTest(raw=raw):
                with self.assertRaises(ValueError):
                    to_cents(raw)

if __name__ == "__main__":
    unittest.main()
```

遇到异常时，从回溯最后一行确定异常类型，再查看对应文件和行号。可以临时打印关键变量，或插入 `breakpoint()` 进入调试器。不要只修改“报错的那一行”而忽略输入为何不符合预期。

## 14. 常见问题与学习路线

| 问题 | 原因与处理 |
| --- | --- |
| `IndentationError` | 检查冒号后缩进和 Tab/空格混用 |
| `NameError` | 检查名字拼写、定义顺序与作用域 |
| `TypeError` | 检查操作对象的类型，例如不能直接把字符串与整数相加 |
| `ValueError` | 类型可能正确，但值不符合要求，例如 `int("abc")` |
| `ModuleNotFoundError` | 确认包名和当前解释器；用 `python -m pip` 安装到正确环境 |
| 找不到相对路径文件 | 检查当前工作目录，必要时基于 `__file__` 构造路径 |

建议按三个阶段继续：第一阶段熟练使用字符串、容器、条件与循环；第二阶段用函数拆分程序，并练习文件、异常和测试；第三阶段选择具体方向，例如自动化脚本、Web API 或数据分析，再学习相应框架。

## 15. 练习与进一步阅读

1. 为记账工具添加“按类别合计”，复用字典与整数分。
2. 为每笔支出记录日期，使用 `datetime.date` 并验证输入。
3. 增加删除功能，确认记录序号有效后再保存。
4. 将账本导出为 CSV，使用标准库 `csv`，不要手工拼接逗号。
5. 为上述功能补充正常情况、空数据和非法输入的测试。

可以在右侧对话中提问：“虚拟环境解决什么问题？”、“默认参数为什么不能随意用空列表？”、“记账程序如何避免浮点误差？”或“18.50 元和 3.00 元的合计是多少？请引用原文。”回答后展开来源，核对解释对应的章节。

进一步阅读：

- [Python 官方教程（中文）](https://docs.python.org/zh-cn/3/tutorial/)
- [Python 标准库文档（中文）](https://docs.python.org/zh-cn/3/library/)
- [PEP 8：Python 代码风格](https://peps.python.org/pep-0008/)

从能运行的小程序开始，逐步加入输入校验、持久化和测试。理解数据如何流动、错误如何处理，比记住更多语法更有用。
