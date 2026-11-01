餐厅列表、聚合搜索、筛选、排序、分页、详情和编辑在 [ListScreens.kt](../src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/ListScreens.kt) 与 [DetailEditScreens.kt](../src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/DetailEditScreens.kt)。店内菜品从详情和编辑页进入，餐厅与首访通过一个请求创建。

页面使用共享 D2 明暗 token，订阅 ViewModel / Room 缓存；完整验收与真机缺口见仓库根目录的 P4_DONE.md / P4_BLOCKERS.md。
