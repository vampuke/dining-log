#!/usr/bin/env bash
set -euo pipefail

DINING_BACKEND=$(cd "$(dirname "$0")/../.." && pwd)
DINING_DB=${1:-$(mktemp -d /tmp/dining-p1-sql.XXXXXX)/verify.db}
if test -e "$DINING_DB"; then
    printf 'Refusing to overwrite an existing database: %s\n' "$DINING_DB" >&2
    exit 1
fi

printf '$ sqlite3 --version\n'
sqlite3 --version
printf '$ sqlite3 %q < backend/migrations/0001_initial.sql\n' "$DINING_DB"
sqlite3 "$DINING_DB" < "$DINING_BACKEND/migrations/0001_initial.sql"
printf 'exit=0\n'
printf '$ sqlite3 %q < backend/tests/sql/fixtures.sql\n' "$DINING_DB"
sqlite3 "$DINING_DB" < "$DINING_BACKEND/tests/sql/fixtures.sql"
printf 'exit=0\n'

run_sql() {
    sqlite3 -batch -bail "$DINING_DB" -cmd 'PRAGMA foreign_keys = ON;' "$1"
}
show_command() {
    printf '$ sqlite3 -batch -bail %q -cmd %q %q\n' "$DINING_DB" 'PRAGMA foreign_keys = ON;' "$1"
}
DINING_REJECTIONS=0
expect_rejected() {
    local label=$1 sql=$2 pattern=$3 result code
    printf '\nCase: %s\n' "$label"
    show_command "$sql"
    if result=$(run_sql "$sql" 2>&1); then
        printf 'FAIL: illegal data was accepted\n' >&2
        exit 1
    else
        code=$?
    fi
    printf '%s\nexit=%s\n' "$result" "$code"
    if ! [[ "$result" =~ $pattern ]]; then
        printf 'FAIL: rejection was caused by an unexpected error\n' >&2
        exit 1
    fi
    DINING_REJECTIONS=$((DINING_REJECTIONS + 1))
}
expect_value() {
    local label=$1 sql=$2 expected=$3 result
    printf '\nCase: %s\n' "$label"
    show_command "$sql"
    result=$(run_sql "$sql")
    printf '%s\nexit=0\n' "$result"
    if [[ "$result" != "$expected" ]]; then
        printf 'FAIL: expected %q\n' "$expected" >&2
        exit 1
    fi
}

expect_value 'full DDL counts: tables | explicit indexes | triggers' \
    "SELECT (SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'), (SELECT count(*) FROM sqlite_master WHERE type='index' AND sql IS NOT NULL), (SELECT count(*) FROM sqlite_master WHERE type='trigger');" '14|30|1'
expect_value 'password iteration default' 'SELECT DISTINCT password_iterations FROM users;' '10000'
expect_rejected 'password iteration downgrade' 'UPDATE users SET password_iterations=9999;' 'CHECK constraint failed'

for table in restaurants dishes; do
    for score in 0 51 43.5 4.5; do
        expect_rejected "$table illegal rating_tenths=$score" "UPDATE $table SET rating_tenths=$score;" 'CHECK constraint failed'
    done
    expect_value "$table: 10, 50 and NULL remain valid" \
        "BEGIN; UPDATE $table SET rating_tenths=10; UPDATE $table SET rating_tenths=50; UPDATE $table SET rating_tenths=NULL; ROLLBACK; SELECT DISTINCT rating_tenths FROM $table WHERE rating_tenths IS NOT NULL;" '43'
done

for date in 2026-02-30 2023-02-29 2026-13-01 2026-00-01 2026-01-00 2026-2-01 1899-12-31 2101-01-01; do
    expect_rejected "illegal dining_date=$date" "UPDATE visits SET dining_date='$date';" 'CHECK constraint failed'
done
expect_value 'valid leap day and two visits on the same date' \
    "SELECT dining_date, count(*) FROM visits GROUP BY dining_date;" '2024-02-29|2'
expect_value 'valid future date remains a local calendar date' \
    "BEGIN; UPDATE visits SET dining_date='2100-12-31'; SELECT DISTINCT dining_date FROM visits; ROLLBACK;" '2100-12-31'
expect_rejected 'invalid meal' "UPDATE visits SET meal='snack';" 'CHECK constraint failed'
expect_rejected 'cuisine requires normalized partner' "UPDATE restaurants SET cuisine='Sichuan', cuisine_normalized=NULL;" 'CHECK constraint failed'

DINING_USER_B='00000000-0000-4000-8000-000000000002'
for table in dishes visits restaurant_search refresh_tokens refresh_rotation_results; do
    expect_rejected "cross-account composite FK: $table" "UPDATE $table SET user_id='$DINING_USER_B';" 'FOREIGN KEY constraint failed'
done
expect_rejected 'revision references a change_set owned by another account' \
    "INSERT INTO revisions (id,user_id,change_set_id,restaurant_id,entity_type,entity_id,action,entity_version_before,entity_version_after,diff_json,search_text,metadata_updated_at,created_at) SELECT '60000000-0000-4000-8000-000000000002',user_id,'40000000-0000-4000-8000-000000000002',restaurant_id,entity_type,entity_id,'update',1,2,diff_json,search_text,metadata_updated_at,created_at FROM revisions;" 'FOREIGN KEY constraint failed'
expect_rejected 'revision dish must belong to the referenced restaurant' \
    "INSERT INTO revisions (id,user_id,change_set_id,restaurant_id,entity_type,entity_id,dish_id,action,entity_version_before,entity_version_after,diff_json,search_text,metadata_updated_at,created_at) SELECT '60000000-0000-4000-8000-000000000002',user_id,change_set_id,'10000000-0000-4000-8000-000000000002','dish','20000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001','create',0,1,diff_json,search_text,metadata_updated_at,created_at FROM revisions;" 'FOREIGN KEY constraint failed'

for diff in '[]' '{}' '{invalid'; do
    expect_rejected "illegal diff_json=$diff" \
        "INSERT INTO revisions (id,user_id,change_set_id,restaurant_id,entity_type,entity_id,action,entity_version_before,entity_version_after,diff_json,search_text,metadata_updated_at,created_at) SELECT '60000000-0000-4000-8000-000000000002',user_id,change_set_id,restaurant_id,entity_type,entity_id,'update',1,2,'$diff',search_text,metadata_updated_at,created_at FROM revisions;" 'CHECK constraint failed|malformed JSON'
done

while IFS='|' read -r column value; do
    expect_rejected "immutable revision core: $column" "UPDATE revisions SET $column=$value;" 'revision core is immutable'
done <<'IMMUTABLE'
id|'60000000-0000-4000-8000-000000000002'
user_id|'00000000-0000-4000-8000-000000000002'
change_set_id|'40000000-0000-4000-8000-000000000002'
restaurant_id|'10000000-0000-4000-8000-000000000002'
entity_type|'dish'
entity_id|'20000000-0000-4000-8000-000000000001'
dish_id|'20000000-0000-4000-8000-000000000001'
visit_id|'30000000-0000-4000-8000-000000000001'
action|'update'
entity_version_before|1
entity_version_after|2
diff_json|'[{"field":"rating_tenths","before":40,"after":43}]'
source_revision_id|'60000000-0000-4000-8000-000000000002'
search_text|'changed'
created_at|2
IMMUTABLE
expect_value 'revision note/hide metadata remains editable' \
    "UPDATE revisions SET note='Synthetic note',note_search='synthetic note',metadata_version=2,metadata_updated_at=2,deleted_at=2; SELECT note,metadata_version,deleted_at FROM revisions;" 'Synthetic note|2|2'

expect_rejected 'zero-row CAS aborts and closes the uncommitted transaction' \
    "BEGIN; UPDATE restaurants SET rating_tenths=44,version=version+1,updated_at=2 WHERE id='10000000-0000-4000-8000-000000000001' AND version=999; INSERT INTO mutation_guards VALUES ('zero-row-cas',changes()); COMMIT;" 'CHECK constraint failed'
expect_rejected 'later invalid revision rolls back the successful CAS and guard' \
    "BEGIN; UPDATE restaurants SET rating_tenths=44,version=version+1,updated_at=2 WHERE id='10000000-0000-4000-8000-000000000001' AND version=1; INSERT INTO mutation_guards VALUES ('later-failure',changes()); INSERT INTO revisions (id,user_id,change_set_id,restaurant_id,entity_type,entity_id,action,entity_version_before,entity_version_after,diff_json,search_text,metadata_updated_at,created_at) SELECT '60000000-0000-4000-8000-000000000002',user_id,change_set_id,restaurant_id,entity_type,entity_id,'update',1,2,'[]',search_text,2,2 FROM revisions; COMMIT;" 'CHECK constraint failed'
expect_value 'both failed transactions leave the entity and history unchanged' \
    "SELECT rating_tenths,version,updated_at,(SELECT count(*) FROM revisions),(SELECT count(*) FROM mutation_guards) FROM restaurants WHERE id='10000000-0000-4000-8000-000000000001';" '43|1|1|1|0'
expect_value 'foreign_key_check: no rows' 'PRAGMA foreign_key_check;' ''
expect_value 'quick_check' 'PRAGMA quick_check;' 'ok'
printf '\nPASS: migration, valid controls, %s expected rejections, rollback, FK and integrity checks\n' "$DINING_REJECTIONS"
