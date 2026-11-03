# PR #16 第三轮 CI 修复任务书

你是实现者。同一个编译错误连续两次没修对，这次按**指定写法**改，不要自己发挥。

## 失败证据

CI run `41587050097` / job `125754894312`（commit `3998ece`，已加 `import androidx.compose.ui.unit.DpRect`）：

```
> Task :app:compileDebugAndroidTestKotlin FAILED
e: Pr16UiTest.kt:125:54 Unresolved reference. None of the following candidates is
   applicable because of a receiver type mismatch:
   val GestureScope.height: Int
e: Pr16UiTest.kt:125:61 None of the following candidates is applicable:
   fun compareTo(other: Byte/Short/Int/Long/Float/Double): Int
```

## 为什么上一轮修错了（别再重复）

加上 `import androidx.compose.ui.unit.DpRect` **不解决问题**。

`DpRect` 是**类**，而 `DpRect.height` 是**同包内的顶层扩展属性**（声明在 `androidx.compose.ui.unit` 包下，和类同名文件里）。

- `import androidx.compose.ui.unit.DpRect` → 只导入**类**
- Kotlin 的扩展属性必须**逐个 import 属性本身**（`import androidx.compose.ui.unit.height`），导入类不会顺带导入扩展

所以 `.height` 依然解析失败，编译器退回去匹配 `GestureScope.height: Int`（receiver 不匹配）。

## 指定修法（照抄，不要换别的方案）

把 `Pr16UiTest.kt:125` 这一行：

```kotlin
assertTrue(toggle.getUnclippedBoundsInRoot().height >= 48.dp)
```

改成：

```kotlin
val bounds = toggle.getUnclippedBoundsInRoot()
assertTrue(bounds.bottom - bounds.top >= 48.dp)
```

**为什么这个写法一定能编译**：
- `DpRect.bottom` / `DpRect.top` 是 **`DpRect` 类的成员属性**，不需要任何 import
- `Dp.minus` / `Dp.compareTo` 是 **`Dp` 值类的成员运算符**，同样不需要额外 import

## 同时删掉无效的 import

删掉 `Pr16UiTest.kt` 里这一行（它不起作用，留着是噪音）：

```kotlin
import androidx.compose.ui.unit.DpRect
```

**但先确认**：全文件只有第 125 行用到 `DpRect` 相关的东西（已确认：只有 125 行的 `.height`），删掉 import 安全。

## 硬性约束

- **必须检出 `pr14-rebased`**，不许新建分支：
  `git fetch origin && git checkout pr14-rebased && git pull origin pr14-rebased`
- 只改这 2 处（125 行断言写法 + 删无效 import）。**不动**任何其他断言、不动产品代码。
- 不要碰 `backend/`、`contracts/`、`p8_protected_hashes.json`、`p7_baseline.json`、
  `verify_bite_visuals.py`。
- 不要改 `versionCode`/`versionName`，不要合并 PR，不要发版。
- 本机约 956MB 内存：**禁止** `gradle` / `npm` / `docker` / 任何重编译。
- 改完必须跑并贴出结果：
  ```bash
  cd /root/dining_log && python3 -B android/tools/verify_p6_visuals.py; echo exit=$?
  ```
  必须 `exit=0`。

## 完成标准

`pr14-rebased` 上新 commit 并 push，`verify_p6_visuals.py` 本地 exit 0。
干完就停。
