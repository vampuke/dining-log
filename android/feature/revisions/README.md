修改详情、历史旧新值、说明、隐藏恢复、重新应用与三栏比较在 [HistoryScreens.kt](../src/main/kotlin/top/alanvampuke/dininglog/feature/revisions/HistoryScreens.kt)。历史摘要与完整 diff 分开缓存，完整详情按需读取；重新应用先核对当前值，仅提交选中字段，重新 CAS。
