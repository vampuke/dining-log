#!/bin/bash
# 独立验证 migration：建表 + 约束生效
set -u
DB=/tmp/verify_$$.db
rm -f "$DB"
sqlite3 "$DB" < /root/dining_log/backend/migrations/0001_initial.sql || { echo "BUILD_FAIL"; exit 1; }
echo "BUILD_OK"
echo "tables=$(sqlite3 "$DB" "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';")"
echo "indexes=$(sqlite3 "$DB" "SELECT count(*) FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%';")"
echo "triggers=$(sqlite3 "$DB" "SELECT count(*) FROM sqlite_master WHERE type='trigger';")"

U=11111111-1111-1111-1111-111111111111
sqlite3 "$DB" "INSERT INTO users(id,username,username_display,display_name,password_algorithm,password_hash,password_salt,password_iterations,pepper_version,password_changed_at,created_at,updated_at) VALUES('$U','tester','tester','T','pbkdf2-sha256+hmac-sha256-v1','AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA','AAAAAAAAAAAAAAAAAAAAAA',10000,'v1',1,1,1);" 2>&1 | head -2
echo "user_insert_rc=$?"

test_bad () {
  local label="$1" sql="$2"
  out=$(sqlite3 "$DB" "PRAGMA foreign_keys=ON; $sql" 2>&1)
  if [ -z "$out" ]; then echo "ACCEPTED(bad!) $label"; else echo "REJECTED ok  $label  -> $(echo "$out" | head -1)"; fi
}

test_bad "rating 0"  "INSERT INTO restaurants(id,user_id,name,category,address,rating_tenths,version,created_at,updated_at) VALUES('22222222-2222-2222-2222-222222222222','$U','R','restaurant','A',0,1,1,1);"
test_bad "rating 51" "INSERT INTO restaurants(id,user_id,name,category,address,rating_tenths,version,created_at,updated_at) VALUES('22222222-2222-2222-2222-222222222223','$U','R','restaurant','A',51,1,1,1);"
test_bad "rating float 43.5" "INSERT INTO restaurants(id,user_id,name,category,address,rating_tenths,version,created_at,updated_at) VALUES('22222222-2222-2222-2222-222222222224','$U','R','restaurant','A',43.5,1,1,1);"
test_bad "bad category" "INSERT INTO restaurants(id,user_id,name,category,address,version,created_at,updated_at) VALUES('22222222-2222-2222-2222-222222222225','$U','R','nonsense','A',1,1,1);"

echo "--- 合法插入 ---"
sqlite3 "$DB" "INSERT INTO restaurants(id,user_id,name,category,address,rating_tenths,version,created_at,updated_at) VALUES('33333333-3333-3333-3333-333333333333','$U','兰记','restaurant','愚园路',43,1,1,1);" && echo "OK rating 43 accepted"
echo "readback=$(sqlite3 "$DB" "SELECT rating_tenths FROM restaurants WHERE id='33333333-3333-3333-3333-333333333333';")"

echo "--- fk check ---"
sqlite3 "$DB" "PRAGMA foreign_key_check;"
echo "--- integrity ---"
sqlite3 "$DB" "PRAGMA quick_check;"
rm -f "$DB"
