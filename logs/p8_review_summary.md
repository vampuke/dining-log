**P8 独立评审摘要**

审查 `p8-ux-fixes@c879a217b2f75741d8661478d803021d67aaa816`。结论 **PARTIAL**：0 BLOCKER、4 MAJOR、4 MINOR、1 NIT；A/B/C/D/F 为 PARTIAL，E 为 PASS。完整逐项评级及最小修法见 [REVIEW.md](../design/p8-review/REVIEW.md)。

验收前应修复的 MAJOR：

- 更新弹窗90%固定高度留下大片空白：[UpdateDialog.kt:23](../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/UpdateDialog.kt#L23)，证据 `light-update.png` / `dark-update.png`。
- 同进程旋转重建编辑页会覆盖未提交输入及saving：[ProductRoot.kt:165](../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductRoot.kt#L165)、[ProductViewModel.kt:172](../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductViewModel.kt#L172)。
- 外置label未成为实际输入的无障碍名称：[BiteComponents.kt:164](../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/BiteComponents.kt#L164)。
- 横屏胶囊忽略左右安全区：[QuietGridComponents.kt:85](../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/QuietGridComponents.kt#L85)。

只读校验确认颜色对比、16张证据hash/尺寸、SQLite迁移保留数据、backend/contracts零差异及版本7/0.7.0。未运行Gradle或模拟器；CI仅按材料记录。报告和本摘要提交于 `p8-review`，未修改实现、未合并PR或触发release。
