# PR #16 CI 修复任务书（视觉回归守卫）

你是实现者。PR #16 的 CI 在 `verify_bite_visuals.py` 失败，请修掉。

## 失败证据

CI run `41584566457` / job `125746935877`：

```
File "android/tools/verify_bite_visuals.py", line 102, in <module>
    assert removed <= allowed, (relative, removed)
AssertionError: ('android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/ListScreens.kt',
                 {'"choose-restaurant"', '"history-absolute:${record.ref.id}"', '"clear-restaurant"'})
```

## 根因（已确认，不要误判）

`verify_bite_visuals.py:99` 用正则自源码提取 testTag：

```python
new_tags = set(re.findall(r'testTag\(("(?:[^"\\]|\\.)*"|tag)\)', (ROOT / relative).read_text()))
```

它**只匹配以字符串字面量（或以 `tag` 变量）直接作为参数**的写法。

而 `ListScreens.kt:216-217` 现在写的是：

```kotlin
Modifier.testTag("history-time:${record.ref.id}"),
Modifier.testTag(if (absolute) "history-absolute:${record.ref.id}" else "history-relative:${record.ref.id}"))
```

第二个参数是**条件表达式**，不是字面量，正则提取不到 → 守卫误报该 tag 被删除。

`choose-restaurant` / `clear-restaurant` 的告警是**预期内**的：`verify_bite_visuals.py:107` 已把它们列为可退役（`retired` 断言禁止它们再出现）。只有 `history-absolute` 是真问题。

## 修法（必须这样改，不要改守卫）

**不要**放宽 `verify_bite_visuals.py` 的正则，**不要**改 `p7_baseline.json`，**不要**把 tag 加进 `permitted_tag_removals`。那会削弱守卫。

正确做法：让两个分支的字面量都直接作为 `testTag(...)` 的参数出现——

```kotlin
HistoryTimeText(record.updatedAt, absolute, { absolute = !absolute },
    Modifier.testTag("history-time:${record.ref.id}"),
    if (absolute) Modifier.testTag("history-absolute:${record.ref.id}")
    else Modifier.testTag("history-relative:${record.ref.id}"))
```

这样两个字面量都字面出现在 `testTag("…")` 中，正则能提取到，绝对态 tag 依旧存在，相对态也继续有 tag。

## 同时必须做的事

1. 改完自己跑一遍守卫确认通过（本机只跑 python，不要跑 gradle）：
   ```bash
   cd /root/dining_log && python3 -B android/tools/verify_p6_visuals.py; echo "exit=$?"
   ```
   必须 `exit=0`。
2. 确认 `Pr16UiTest.kt:133` 断言 `history-absolute:time` 仍成立（用的是 `useUnmergedTree = true`，注意 tag 挂在 text 节点上要能查到）。
3. 提交并 push 到 **`pr14-rebased`**（那是 PR #16 的 head 分支，不是 `pr16-rebased`）。

## 硬性约束（上次踩过的坑，逐条遵守）

- **必须检出 `pr14-rebased`**。上一轮你新建了 `pr16-rebased` 导致 PR 拿不到修复，这次不许新建分支。
  `git fetch origin && git checkout pr14-rebased && git pull origin pr14-rebased`
- 不要合并 PR，不要发版，不要改 `versionCode`/`versionName`。
- 不要碰 `backend/`、`contracts/`、`android/tools/p8_protected_hashes.json`。
- 本机只有约 956MB 内存：**禁止** `gradle` / `npm` / `docker` / 任何重编译。只允许静态编辑、`python3`、`git`。
- Kotlin 语法、import、Compose 作用域要格外小心（DrawScope lambda 不是 composable）。
- `QuietGridComponents.kt` 的未评分空轨道逻辑、`ListScreens.kt` 的 `formatAbsoluteTime` 必须保留。

## 完成标准

`pr14-rebased` 上有一个新 commit，push 成功，且本机 `verify_p6_visuals.py` exit 0。
干完就停，不要做别的。
