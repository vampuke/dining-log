# PR #16 第二轮 CI 修复任务书

你是实现者。PR #16 的 `android` 与两个 instrumentation job 全部失败在**同一个编译错误**。

## 失败证据

CI run `41585417020` / job `125749642625`：

```
> Task :app:compileDebugAndroidTestKotlin FAILED
e: Pr16UiTest.kt:124:54 Unresolved reference. None of the following candidates is
   applicable because of a receiver type mismatch:
   val GestureScope.height: Int
e: Pr16UiTest.kt:124:61 None of the following candidates is applicable:
   fun compareTo(other: Int): Int
   ...
```

对应代码 `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr16UiTest.kt:124`：

```kotlin
assertTrue(toggle.getUnclippedBoundsInRoot().height >= 48.dp)
```

## 根因（已确认）

`getUnclippedBoundsInRoot()` 返回 `DpRect`。`DpRect.height` **是扩展属性，在 `androidx.compose.ui.unit.DpRect` 上声明**，必须显式 import 才可用。

`Pr16UiTest.kt` 的 import 里只有 `androidx.compose.ui.unit.Density` 和 `androidx.compose.ui.unit.dp`，**缺 `androidx.compose.ui.unit.DpRect`**。

所以 `.height` 解析失败，编译器退而把 `.height` 匹配成 Compose 测试手势作用域里的 `GestureScope.height: Int`（receiver 不匹配 → 报错），然后 `>=` 又只能找 `Int.compareTo`，于是 `48.dp` 也不匹配。

**参考**：同目录 `P8VisualTest.kt:32` 就有 `import androidx.compose.ui.unit.DpRect`，它能正常用 `getUnclippedBoundsInRoot()`。

## 修法

在 `Pr16UiTest.kt` 的 import 区加入：

```kotlin
import androidx.compose.ui.unit.DpRect
```

只加这一行。**不要**改断言、不要换成 `bounds.height.value`、不要引入别的库。

## 必须自查的同类问题

加完 import 后，把 `Pr16UiTest.kt` **整个文件**扫一遍，看还有没有别的「需要 import 但没 import 的扩展属性/函数」，尤其是：

- `getUnclippedBoundsInRoot()` 上的 `.height` / `.width` / `.top` / `.bottom` / `.left` / `.right`（都在 `DpRect` 里）
- `assertTextEquals`、`assertHasClickAction` 来自 `androidx.compose.ui.test.*`（已 import）
- `Role`、`SemanticsActions`、`SemanticsProperties`（已 import）
- `Dp` 与 `DpRect` 混用

只要发现同类漏洞，一并补 import。**不要**改动任何测试逻辑或断言。

## 硬性约束

- **必须检出 `pr14-rebased`**（PR #16 的 head）。不许新建分支。
  `git fetch origin && git checkout pr14-rebased && git pull origin pr14-rebased`
- 只改 import 行（以及确实缺失的同级 import），不动测试逻辑、不动产品代码。
- 不要碰 `backend/`、`contracts/`、`p8_protected_hashes.json`、`p7_baseline.json`、
  `verify_bite_visuals.py`。
- 不要改 `versionCode`/`versionName`，不要合并 PR，不要发版。
- 本机约 956MB 内存：**禁止** `gradle` / `npm` / `docker` / 任何重编译。
  只允许静态编辑、`python3`、`git`。
- 改完必须跑：
  ```bash
  cd /root/dining_log && python3 -B android/tools/verify_p6_visuals.py; echo exit=$?
  ```
  必须 `exit=0`。

## 完成标准

`pr14-rebased` 上有新 commit 并 push，`verify_p6_visuals.py` 本地 exit 0。
干完就停，不要合并、不要发版、不要做别的改动。
