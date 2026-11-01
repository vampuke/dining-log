# 迁移 SQL 实测验证

唯一 DDL 来源：design/PLAN.md 3.3 完整 SQL block，迁移逐字一致。
SHA-256：`191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b`。

实测日期：2026-11-01 UTC。环境：956 MB VPS，sqlite3 CLI 3.37.2。
运行命令：`bash backend/tests/sql/verify.sh /tmp/t.db`。已有的 `/tmp/t.db`
已事先保留到 `/tmp/dining-p1-preserved.VFC4Cc/t.db`；没有覆盖旧数据。
复跑请省略参数使用新临时目录，或指定尚不存在的数据库路径。

结果：14 张表、30 个显式索引、1 个不可变 trigger；46 次预期拒绝。
两实体的 0、51、43.5、4.5 tenths，非法日期与餐别、跨账号组合外键、
JSON diff、全部 15 个不可变核心字段、0 行 CAS 和后续语句失败均已验证。
合法评分边界/null、合法闰日/未来日期、同日两次到店、可编辑历史元数据均通过。
`foreign_key_check` 无输出，`quick_check` 返回 `ok`。

PLAN.md 12.2 的旧统计为 13/29，本次以其 3.3 当前完整 DDL 为准，未修改方案。
该结果是本地 SQLite 验证；Cloudflare Workers pool D1 结果另记 P1_DONE.md，
真实 Cloudflare D1 与免费档 CPU 性能留待获授权的后续阶段。

以下为原始命令/输出，预期拒绝的 exit=19 是测试通过条件。

```text
$ sqlite3 --version
3.37.2 2022-01-06 13:25:41 872ba256cbf61d9290b571c0e6d82a20c224ca3ad82971edc46b29818d5dalt1
$ sqlite3 /tmp/t.db < backend/migrations/0001_initial.sql
exit=0
$ sqlite3 /tmp/t.db < backend/tests/sql/fixtures.sql
exit=0

Case: full DDL counts: tables | explicit indexes | triggers
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; SELECT\ \(SELECT\ count\(\*\)\ FROM\ sqlite_master\ WHERE\ type=\'table\'\ AND\ name\ NOT\ LIKE\ \'sqlite_%\'\)\,\ \(SELECT\ count\(\*\)\ FROM\ sqlite_master\ WHERE\ type=\'index\'\ AND\ sql\ IS\ NOT\ NULL\)\,\ \(SELECT\ count\(\*\)\ FROM\ sqlite_master\ WHERE\ type=\'trigger\'\)\;
14|30|1
exit=0

Case: password iteration default
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; SELECT\ DISTINCT\ password_iterations\ FROM\ users\;
10000
exit=0

Case: password iteration downgrade
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ users\ SET\ password_iterations=9999\;
Error: stepping, CHECK constraint failed: password_iterations >= 10000 (19)
exit=19

Case: restaurants illegal rating_tenths=0
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ restaurants\ SET\ rating_tenths=0\;
Error: stepping, CHECK constraint failed: rating_tenths IS NULL OR (
            typeof(rating_tenths) = 'integer' AND rating_tenths BETWEEN 10 AND 50
        ) (19)
exit=19

Case: restaurants illegal rating_tenths=51
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ restaurants\ SET\ rating_tenths=51\;
Error: stepping, CHECK constraint failed: rating_tenths IS NULL OR (
            typeof(rating_tenths) = 'integer' AND rating_tenths BETWEEN 10 AND 50
        ) (19)
exit=19

Case: restaurants illegal rating_tenths=43.5
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ restaurants\ SET\ rating_tenths=43.5\;
Error: stepping, CHECK constraint failed: rating_tenths IS NULL OR (
            typeof(rating_tenths) = 'integer' AND rating_tenths BETWEEN 10 AND 50
        ) (19)
exit=19

Case: restaurants illegal rating_tenths=4.5
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ restaurants\ SET\ rating_tenths=4.5\;
Error: stepping, CHECK constraint failed: rating_tenths IS NULL OR (
            typeof(rating_tenths) = 'integer' AND rating_tenths BETWEEN 10 AND 50
        ) (19)
exit=19

Case: restaurants: 10, 50 and NULL remain valid
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; BEGIN\;\ UPDATE\ restaurants\ SET\ rating_tenths=10\;\ UPDATE\ restaurants\ SET\ rating_tenths=50\;\ UPDATE\ restaurants\ SET\ rating_tenths=NULL\;\ ROLLBACK\;\ SELECT\ DISTINCT\ rating_tenths\ FROM\ restaurants\ WHERE\ rating_tenths\ IS\ NOT\ NULL\;
43
exit=0

Case: dishes illegal rating_tenths=0
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ dishes\ SET\ rating_tenths=0\;
Error: stepping, CHECK constraint failed: rating_tenths IS NULL OR (
            typeof(rating_tenths) = 'integer' AND rating_tenths BETWEEN 10 AND 50
        ) (19)
exit=19

Case: dishes illegal rating_tenths=51
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ dishes\ SET\ rating_tenths=51\;
Error: stepping, CHECK constraint failed: rating_tenths IS NULL OR (
            typeof(rating_tenths) = 'integer' AND rating_tenths BETWEEN 10 AND 50
        ) (19)
exit=19

Case: dishes illegal rating_tenths=43.5
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ dishes\ SET\ rating_tenths=43.5\;
Error: stepping, CHECK constraint failed: rating_tenths IS NULL OR (
            typeof(rating_tenths) = 'integer' AND rating_tenths BETWEEN 10 AND 50
        ) (19)
exit=19

Case: dishes illegal rating_tenths=4.5
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ dishes\ SET\ rating_tenths=4.5\;
Error: stepping, CHECK constraint failed: rating_tenths IS NULL OR (
            typeof(rating_tenths) = 'integer' AND rating_tenths BETWEEN 10 AND 50
        ) (19)
exit=19

Case: dishes: 10, 50 and NULL remain valid
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; BEGIN\;\ UPDATE\ dishes\ SET\ rating_tenths=10\;\ UPDATE\ dishes\ SET\ rating_tenths=50\;\ UPDATE\ dishes\ SET\ rating_tenths=NULL\;\ ROLLBACK\;\ SELECT\ DISTINCT\ rating_tenths\ FROM\ dishes\ WHERE\ rating_tenths\ IS\ NOT\ NULL\;
43
exit=0

Case: illegal dining_date=2026-02-30
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ visits\ SET\ dining_date=\'2026-02-30\'\;
Error: stepping, CHECK constraint failed: length(dining_date) = 10
        AND dining_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        AND dining_date BETWEEN '1900-01-01' AND '2100-12-31'
        AND date(dining_date, '+0 days') IS NOT NULL
        AND date(dining_date, '+0 days') = dining_date (19)
exit=19

Case: illegal dining_date=2023-02-29
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ visits\ SET\ dining_date=\'2023-02-29\'\;
Error: stepping, CHECK constraint failed: length(dining_date) = 10
        AND dining_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        AND dining_date BETWEEN '1900-01-01' AND '2100-12-31'
        AND date(dining_date, '+0 days') IS NOT NULL
        AND date(dining_date, '+0 days') = dining_date (19)
exit=19

Case: illegal dining_date=2026-13-01
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ visits\ SET\ dining_date=\'2026-13-01\'\;
Error: stepping, CHECK constraint failed: length(dining_date) = 10
        AND dining_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        AND dining_date BETWEEN '1900-01-01' AND '2100-12-31'
        AND date(dining_date, '+0 days') IS NOT NULL
        AND date(dining_date, '+0 days') = dining_date (19)
exit=19

Case: illegal dining_date=2026-00-01
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ visits\ SET\ dining_date=\'2026-00-01\'\;
Error: stepping, CHECK constraint failed: length(dining_date) = 10
        AND dining_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        AND dining_date BETWEEN '1900-01-01' AND '2100-12-31'
        AND date(dining_date, '+0 days') IS NOT NULL
        AND date(dining_date, '+0 days') = dining_date (19)
exit=19

Case: illegal dining_date=2026-01-00
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ visits\ SET\ dining_date=\'2026-01-00\'\;
Error: stepping, CHECK constraint failed: length(dining_date) = 10
        AND dining_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        AND dining_date BETWEEN '1900-01-01' AND '2100-12-31'
        AND date(dining_date, '+0 days') IS NOT NULL
        AND date(dining_date, '+0 days') = dining_date (19)
exit=19

Case: illegal dining_date=2026-2-01
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ visits\ SET\ dining_date=\'2026-2-01\'\;
Error: stepping, CHECK constraint failed: length(dining_date) = 10
        AND dining_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        AND dining_date BETWEEN '1900-01-01' AND '2100-12-31'
        AND date(dining_date, '+0 days') IS NOT NULL
        AND date(dining_date, '+0 days') = dining_date (19)
exit=19

Case: illegal dining_date=1899-12-31
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ visits\ SET\ dining_date=\'1899-12-31\'\;
Error: stepping, CHECK constraint failed: length(dining_date) = 10
        AND dining_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        AND dining_date BETWEEN '1900-01-01' AND '2100-12-31'
        AND date(dining_date, '+0 days') IS NOT NULL
        AND date(dining_date, '+0 days') = dining_date (19)
exit=19

Case: illegal dining_date=2101-01-01
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ visits\ SET\ dining_date=\'2101-01-01\'\;
Error: stepping, CHECK constraint failed: length(dining_date) = 10
        AND dining_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        AND dining_date BETWEEN '1900-01-01' AND '2100-12-31'
        AND date(dining_date, '+0 days') IS NOT NULL
        AND date(dining_date, '+0 days') = dining_date (19)
exit=19

Case: valid leap day and two visits on the same date
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; SELECT\ dining_date\,\ count\(\*\)\ FROM\ visits\ GROUP\ BY\ dining_date\;
2024-02-29|2
exit=0

Case: valid future date remains a local calendar date
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; BEGIN\;\ UPDATE\ visits\ SET\ dining_date=\'2100-12-31\'\;\ SELECT\ DISTINCT\ dining_date\ FROM\ visits\;\ ROLLBACK\;
2100-12-31
exit=0

Case: invalid meal
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ visits\ SET\ meal=\'snack\'\;
Error: stepping, CHECK constraint failed: meal IS NULL OR meal IN ('breakfast', 'lunch', 'dinner', 'other') (19)
exit=19

Case: cuisine requires normalized partner
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ restaurants\ SET\ cuisine=\'Sichuan\'\,\ cuisine_normalized=NULL\;
Error: stepping, CHECK constraint failed: (cuisine IS NULL AND cuisine_normalized IS NULL)
        OR (cuisine IS NOT NULL AND cuisine_normalized IS NOT NULL AND length(cuisine_normalized) > 0) (19)
exit=19

Case: cross-account composite FK: dishes
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ dishes\ SET\ user_id=\'00000000-0000-4000-8000-000000000002\'\;
Error: stepping, FOREIGN KEY constraint failed (19)
exit=19

Case: cross-account composite FK: visits
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ visits\ SET\ user_id=\'00000000-0000-4000-8000-000000000002\'\;
Error: stepping, FOREIGN KEY constraint failed (19)
exit=19

Case: cross-account composite FK: restaurant_search
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ restaurant_search\ SET\ user_id=\'00000000-0000-4000-8000-000000000002\'\;
Error: stepping, FOREIGN KEY constraint failed (19)
exit=19

Case: cross-account composite FK: refresh_tokens
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ refresh_tokens\ SET\ user_id=\'00000000-0000-4000-8000-000000000002\'\;
Error: stepping, FOREIGN KEY constraint failed (19)
exit=19

Case: cross-account composite FK: refresh_rotation_results
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ refresh_rotation_results\ SET\ user_id=\'00000000-0000-4000-8000-000000000002\'\;
Error: stepping, FOREIGN KEY constraint failed (19)
exit=19

Case: revision references a change_set owned by another account
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; INSERT\ INTO\ revisions\ \(id\,user_id\,change_set_id\,restaurant_id\,entity_type\,entity_id\,action\,entity_version_before\,entity_version_after\,diff_json\,search_text\,metadata_updated_at\,created_at\)\ SELECT\ \'60000000-0000-4000-8000-000000000002\'\,user_id\,\'40000000-0000-4000-8000-000000000002\'\,restaurant_id\,entity_type\,entity_id\,\'update\'\,1\,2\,diff_json\,search_text\,metadata_updated_at\,created_at\ FROM\ revisions\;
Error: stepping, FOREIGN KEY constraint failed (19)
exit=19

Case: revision dish must belong to the referenced restaurant
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; INSERT\ INTO\ revisions\ \(id\,user_id\,change_set_id\,restaurant_id\,entity_type\,entity_id\,dish_id\,action\,entity_version_before\,entity_version_after\,diff_json\,search_text\,metadata_updated_at\,created_at\)\ SELECT\ \'60000000-0000-4000-8000-000000000002\'\,user_id\,change_set_id\,\'10000000-0000-4000-8000-000000000002\'\,\'dish\'\,\'20000000-0000-4000-8000-000000000001\'\,\'20000000-0000-4000-8000-000000000001\'\,\'create\'\,0\,1\,diff_json\,search_text\,metadata_updated_at\,created_at\ FROM\ revisions\;
Error: stepping, FOREIGN KEY constraint failed (19)
exit=19

Case: illegal diff_json=[]
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; INSERT\ INTO\ revisions\ \(id\,user_id\,change_set_id\,restaurant_id\,entity_type\,entity_id\,action\,entity_version_before\,entity_version_after\,diff_json\,search_text\,metadata_updated_at\,created_at\)\ SELECT\ \'60000000-0000-4000-8000-000000000002\'\,user_id\,change_set_id\,restaurant_id\,entity_type\,entity_id\,\'update\'\,1\,2\,\'\[\]\'\,search_text\,metadata_updated_at\,created_at\ FROM\ revisions\;
Error: stepping, CHECK constraint failed: json_valid(diff_json)
        AND json_type(diff_json) = 'array'
        AND json_array_length(diff_json) > 0
        AND length(CAST(diff_json AS BLOB)) <= 131072 (19)
exit=19

Case: illegal diff_json={}
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; INSERT\ INTO\ revisions\ \(id\,user_id\,change_set_id\,restaurant_id\,entity_type\,entity_id\,action\,entity_version_before\,entity_version_after\,diff_json\,search_text\,metadata_updated_at\,created_at\)\ SELECT\ \'60000000-0000-4000-8000-000000000002\'\,user_id\,change_set_id\,restaurant_id\,entity_type\,entity_id\,\'update\'\,1\,2\,\'\{\}\'\,search_text\,metadata_updated_at\,created_at\ FROM\ revisions\;
Error: stepping, CHECK constraint failed: json_valid(diff_json)
        AND json_type(diff_json) = 'array'
        AND json_array_length(diff_json) > 0
        AND length(CAST(diff_json AS BLOB)) <= 131072 (19)
exit=19

Case: illegal diff_json={invalid
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; INSERT\ INTO\ revisions\ \(id\,user_id\,change_set_id\,restaurant_id\,entity_type\,entity_id\,action\,entity_version_before\,entity_version_after\,diff_json\,search_text\,metadata_updated_at\,created_at\)\ SELECT\ \'60000000-0000-4000-8000-000000000002\'\,user_id\,change_set_id\,restaurant_id\,entity_type\,entity_id\,\'update\'\,1\,2\,\'\{invalid\'\,search_text\,metadata_updated_at\,created_at\ FROM\ revisions\;
Error: stepping, CHECK constraint failed: json_valid(diff_json)
        AND json_type(diff_json) = 'array'
        AND json_array_length(diff_json) > 0
        AND length(CAST(diff_json AS BLOB)) <= 131072 (19)
exit=19

Case: immutable revision core: id
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ id=\'60000000-0000-4000-8000-000000000002\'\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: user_id
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ user_id=\'00000000-0000-4000-8000-000000000002\'\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: change_set_id
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ change_set_id=\'40000000-0000-4000-8000-000000000002\'\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: restaurant_id
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ restaurant_id=\'10000000-0000-4000-8000-000000000002\'\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: entity_type
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ entity_type=\'dish\'\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: entity_id
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ entity_id=\'20000000-0000-4000-8000-000000000001\'\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: dish_id
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ dish_id=\'20000000-0000-4000-8000-000000000001\'\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: visit_id
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ visit_id=\'30000000-0000-4000-8000-000000000001\'\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: action
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ action=\'update\'\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: entity_version_before
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ entity_version_before=1\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: entity_version_after
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ entity_version_after=2\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: diff_json
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ diff_json=\'\[\{\"field\":\"rating_tenths\"\,\"before\":40\,\"after\":43\}\]\'\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: source_revision_id
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ source_revision_id=\'60000000-0000-4000-8000-000000000002\'\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: search_text
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ search_text=\'changed\'\;
Error: stepping, revision core is immutable (19)
exit=19

Case: immutable revision core: created_at
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ created_at=2\;
Error: stepping, revision core is immutable (19)
exit=19

Case: revision note/hide metadata remains editable
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; UPDATE\ revisions\ SET\ note=\'Synthetic\ note\'\,note_search=\'synthetic\ note\'\,metadata_version=2\,metadata_updated_at=2\,deleted_at=2\;\ SELECT\ note\,metadata_version\,deleted_at\ FROM\ revisions\;
Synthetic note|2|2
exit=0

Case: zero-row CAS aborts and closes the uncommitted transaction
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; BEGIN\;\ UPDATE\ restaurants\ SET\ rating_tenths=44\,version=version+1\,updated_at=2\ WHERE\ id=\'10000000-0000-4000-8000-000000000001\'\ AND\ version=999\;\ INSERT\ INTO\ mutation_guards\ VALUES\ \(\'zero-row-cas\'\,changes\(\)\)\;\ COMMIT\;
Error: stepping, CHECK constraint failed: affected_rows = 1 (19)
exit=19

Case: later invalid revision rolls back the successful CAS and guard
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; BEGIN\;\ UPDATE\ restaurants\ SET\ rating_tenths=44\,version=version+1\,updated_at=2\ WHERE\ id=\'10000000-0000-4000-8000-000000000001\'\ AND\ version=1\;\ INSERT\ INTO\ mutation_guards\ VALUES\ \(\'later-failure\'\,changes\(\)\)\;\ INSERT\ INTO\ revisions\ \(id\,user_id\,change_set_id\,restaurant_id\,entity_type\,entity_id\,action\,entity_version_before\,entity_version_after\,diff_json\,search_text\,metadata_updated_at\,created_at\)\ SELECT\ \'60000000-0000-4000-8000-000000000002\'\,user_id\,change_set_id\,restaurant_id\,entity_type\,entity_id\,\'update\'\,1\,2\,\'\[\]\'\,search_text\,2\,2\ FROM\ revisions\;\ COMMIT\;
Error: stepping, CHECK constraint failed: json_valid(diff_json)
        AND json_type(diff_json) = 'array'
        AND json_array_length(diff_json) > 0
        AND length(CAST(diff_json AS BLOB)) <= 131072 (19)
exit=19

Case: both failed transactions leave the entity and history unchanged
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; SELECT\ rating_tenths\,version\,updated_at\,\(SELECT\ count\(\*\)\ FROM\ revisions\)\,\(SELECT\ count\(\*\)\ FROM\ mutation_guards\)\ FROM\ restaurants\ WHERE\ id=\'10000000-0000-4000-8000-000000000001\'\;
43|1|1|1|0
exit=0

Case: foreign_key_check: no rows
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; PRAGMA\ foreign_key_check\;

exit=0

Case: quick_check
$ sqlite3 -batch -bail /tmp/t.db -cmd PRAGMA\ foreign_keys\ =\ ON\; PRAGMA\ quick_check\;
ok
exit=0

PASS: migration, valid controls, 46 expected rejections, rollback, FK and integrity checks
```
