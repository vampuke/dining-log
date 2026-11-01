#!/bin/bash
set -u
DB=/tmp/vm3_$$.db
rm -f "$DB"
sqlite3 "$DB" < /root/dining_log/backend/migrations/0001_initial.sql || { echo BUILD_FAIL; exit 1; }
U=11111111-1111-1111-1111-111111111111
R=33333333-3333-3333-3333-333333333333
CS=77777777-7777-7777-7777-777777777777
REQ=99999999-9999-9999-9999-999999999999
REV=88888888-8888-8888-8888-888888888888

sqlite3 "$DB" "INSERT INTO users(id,username,display_name,password_algorithm,password_hash,password_salt,pepper_version,password_changed_at,created_at,updated_at) VALUES('$U','tester','T','pbkdf2-sha256+hmac-sha256-v1','AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA','AAAAAAAAAAAAAAAAAAAAAA','v1',1,1,1);"
sqlite3 "$DB" "PRAGMA foreign_keys=ON; INSERT INTO restaurants(id,user_id,name,category,address,rating_tenths,version,created_at,updated_at) VALUES('$R','$U','兰记','restaurant','愚园路',43,1,1,1);"
sqlite3 "$DB" "INSERT INTO change_sets(id,user_id,request_id,created_at) VALUES('$CS','$U','$REQ',1);" && echo "change_set OK"
sqlite3 "$DB" "INSERT INTO revisions(id,user_id,change_set_id,restaurant_id,entity_type,entity_id,action,entity_version_before,entity_version_after,diff_json,search_text,metadata_updated_at,created_at) VALUES('$REV','$U','$CS','$R','restaurant','$R','update',1,2,'[{\"field\":\"rating_tenths\",\"before\":40,\"after\":43}]','兰记',2,2);" && echo "revision OK"

echo "--- trigger: 改核心字段 entity_version_after ---"
out=$(sqlite3 "$DB" "UPDATE revisions SET entity_version_after=99 WHERE id='$REV';" 2>&1)
[ -z "$out" ] && echo "ACCEPTED(bad)" || echo "REJECTED ok -> $(echo "$out"|head -1)"

echo "--- trigger: 改 entity_id ---"
out=$(sqlite3 "$DB" "UPDATE revisions SET entity_id='$CS' WHERE id='$REV';" 2>&1)
[ -z "$out" ] && echo "ACCEPTED(bad)" || echo "REJECTED ok -> $(echo "$out"|head -1)"

echo "--- trigger: 改非核心字段 note（应允许）---"
out=$(sqlite3 "$DB" "UPDATE revisions SET note='补充说明' WHERE id='$REV';" 2>&1)
[ -z "$out" ] && echo "ALLOWED ok (note 可改)" || echo "REJECTED(wrong) -> $(echo "$out"|head -1)"

echo "--- diff_json 非法（非数组）应拒 ---"
out=$(sqlite3 "$DB" "INSERT INTO revisions(id,user_id,change_set_id,restaurant_id,entity_type,entity_id,action,entity_version_before,entity_version_after,diff_json,search_text,metadata_updated_at,created_at) VALUES('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','$U','$CS','$R','restaurant','$R','update',2,3,'{\"x\":1}','x',3,3);" 2>&1)
[ -z "$out" ] && echo "ACCEPTED(bad) non-array diff" || echo "REJECTED ok non-array diff"

echo "--- version_after != before+1 应拒 ---"
out=$(sqlite3 "$DB" "INSERT INTO revisions(id,user_id,change_set_id,restaurant_id,entity_type,entity_id,action,entity_version_before,entity_version_after,diff_json,search_text,metadata_updated_at,created_at) VALUES('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','$U','$CS','$R','restaurant','$R','update',2,5,'[{\"f\":1}]','x',4,4);" 2>&1)
[ -z "$out" ] && echo "ACCEPTED(bad) version jump" || echo "REJECTED ok version jump"

echo "--- fk / integrity ---"
sqlite3 "$DB" "PRAGMA foreign_key_check;"; sqlite3 "$DB" "PRAGMA quick_check;"
rm -f "$DB"
