#!/bin/bash
set -u
DB=/tmp/vm_$$.db
rm -f "$DB"
sqlite3 "$DB" < /root/dining_log/backend/migrations/0001_initial.sql || { echo BUILD_FAIL; exit 1; }
U=11111111-1111-1111-1111-111111111111
R=33333333-3333-3333-3333-333333333333

sqlite3 "$DB" "INSERT INTO users(id,username,display_name,password_algorithm,password_hash,password_salt,password_iterations,pepper_version,password_changed_at,created_at,updated_at) VALUES('$U','tester','T','pbkdf2-sha256+hmac-sha256-v1','AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA','AAAAAAAAAAAAAAAAAAAAAA',10000,'v1',1,1,1);" && echo "user OK"
echo "iters=$(sqlite3 "$DB" "SELECT password_iterations FROM users WHERE id='$U';")  (default '10000' 未显式写入会怎样？上面显式写了)"

# 测试默认值
sqlite3 "$DB" "INSERT INTO users(id,username,display_name,password_algorithm,password_hash,password_salt,pepper_version,password_changed_at,created_at,updated_at) VALUES('44444444-4444-4444-4444-444444444444','tester2','T2','pbkdf2-sha256+hmac-sha256-v1','AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA','AAAAAAAAAAAAAAAAAAAAAA','v1',1,1,1);" && echo "DEFAULT iters=$(sqlite3 "$DB" "SELECT password_iterations FROM users WHERE id='44444444-4444-4444-4444-444444444444';")"

# 低迭代应被拒
out=$(sqlite3 "$DB" "INSERT INTO users(id,username,display_name,password_algorithm,password_hash,password_salt,password_iterations,pepper_version,password_changed_at,created_at,updated_at) VALUES('55555555-5555-5555-5555-555555555555','tester3','T3','pbkdf2-sha256+hmac-sha256-v1','AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA','AAAAAAAAAAAAAAAAAAAAAA',999,'v1',1,1,1);" 2>&1)
[ -z "$out" ] && echo "ACCEPTED(bad) iters=999" || echo "REJECTED ok iters=999"

# 大写用户名应被拒
out=$(sqlite3 "$DB" "INSERT INTO users(id,username,display_name,password_algorithm,password_hash,password_salt,pepper_version,password_changed_at,created_at,updated_at) VALUES('66666666-6666-6666-6666-666666666666','TesterX','T6','pbkdf2-sha256+hmac-sha256-v1','AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA','AAAAAAAAAAAAAAAAAAAAAA','v1',1,1,1);" 2>&1)
[ -z "$out" ] && echo "ACCEPTED(bad) uppercase username" || echo "REJECTED ok uppercase username"

# 建 restaurant + revision 测 trigger
sqlite3 "$DB" "PRAGMA foreign_keys=ON; INSERT INTO restaurants(id,user_id,name,category,address,rating_tenths,version,created_at,updated_at) VALUES('$R','$U','兰记','restaurant','愚园路',43,1,1,1);" && echo "restaurant OK"
sqlite3 "$DB" "INSERT INTO change_sets(id,user_id,request_id,created_at) VALUES('77777777-7777-7777-7777-777777777777','$U','req-1',1);" 2>&1 | head -2
sqlite3 "$DB" "INSERT INTO revisions(id,user_id,change_set_id,entity_type,entity_id,action,entity_version_before,entity_version_after,changes_json,created_at) VALUES('88888888-8888-8888-8888-888888888888','$U','77777777-7777-7777-7777-777777777777','restaurant','$R','update',1,2,'{\"rating_tenths\":{\"before\":40,\"after\":43}}',2);" 2>&1 | head -3

echo "--- trigger: 试图改核心字段 ---"
out=$(sqlite3 "$DB" "UPDATE revisions SET entity_version_after=99 WHERE id='88888888-8888-8888-8888-888888888888';" 2>&1)
[ -z "$out" ] && echo "ACCEPTED(bad) core field update" || echo "REJECTED ok core field update -> $(echo "$out"|head -1)"

echo "--- fk check（有数据后）---"
sqlite3 "$DB" "PRAGMA foreign_key_check;"
echo "--- quick_check ---"
sqlite3 "$DB" "PRAGMA quick_check;"
rm -f "$DB"
