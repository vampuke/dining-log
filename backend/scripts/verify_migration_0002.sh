#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import hashlib
import json
import pathlib
import sqlite3

root = pathlib.Path('.')
initial = (root / 'migrations/0001_initial.sql').read_bytes()
assert hashlib.sha256(initial).hexdigest() == '191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b'
db = sqlite3.connect(':memory:')
db.executescript(initial.decode())
assert db.execute('PRAGMA foreign_keys').fetchone() == (1,)
db.executescript((root / 'tests/sql/fixtures.sql').read_text())

# Populate every application table and all four cascading children for both
# owners, including revisions referencing dishes/visits and soft-deleted rows.
def uid(prefix, number):
    return f'{prefix:08d}-0000-4000-8000-{number:012d}'

for n, owner in [(1, 1), (2, 1), (3, 2)]:
    user, restaurant = uid(0, owner), uid(10000000, n)
    dish, visit = uid(20000000, n + 10), uid(30000000, n + 10)
    db.execute('INSERT INTO dishes (id,user_id,restaurant_id,name,review,created_at,updated_at,deleted_at) VALUES (?,?,?,?,?,1,2,?)',
               (dish, user, restaurant, f'Dish {n}', 'Noodles\n汤面', 2 if n == 2 else None))
    db.execute("INSERT INTO visits (id,user_id,restaurant_id,dining_date,note,search_text,created_at,updated_at) VALUES (?,?,?,'2026-10-31',?,'synthetic visit',1,2)",
               (visit, user, restaurant, f'Visit {n}'))
    db.execute('INSERT OR IGNORE INTO restaurant_search VALUES (?,?,?,2)', (restaurant, user, f'Search {n} 汤面'))
    for kind, entity, dish_id, visit_id, offset in [('restaurant', restaurant, None, None, 10), ('dish', dish, dish, None, 20), ('visit', visit, None, visit, 30)]:
        db.execute("INSERT INTO revisions (id,user_id,change_set_id,restaurant_id,entity_type,entity_id,dish_id,visit_id,action,entity_version_before,entity_version_after,diff_json,search_text,metadata_updated_at,created_at) VALUES (?,?,?,?,?,?,?,?,'update',1,2,?,'synthetic revision',2,2)",
                   (uid(60000000, n + offset), user, uid(40000000, owner), restaurant, kind, entity, dish_id, visit_id,
                    json.dumps([{'field': 'note', 'before': None, 'after': f'History {n}'}])))
db.execute('UPDATE restaurants SET deleted_at=2, updated_at=2 WHERE id=?', (uid(10000000, 2),))
db.execute("INSERT INTO recovery_codes VALUES (?,? ,?,1,NULL,NULL)", (uid(90000000, 1), uid(0, 1), '0' * 64))
db.execute("INSERT INTO auth_rate_limits VALUES ('login_account',?,0,1,100)", ('0' * 64,))
db.execute("INSERT INTO idempotency_keys VALUES (?,?,'PATCH','/fixture',?,200,'{}',1,100)", (uid(0, 1), uid(50000000, 10), '0' * 64))
db.execute("INSERT INTO mutation_guards VALUES ('synthetic-guard',1)")
db.commit()
tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
assert len(tables) == 14

def snapshot(connection):
    result = {}
    for table in tables:
        rows = sorted(json.dumps(row, ensure_ascii=False, separators=(',', ':')) for row in connection.execute(f'SELECT * FROM "{table}"'))
        result[table] = (len(rows), hashlib.sha256('\n'.join(rows).encode()).hexdigest())
    return result

before = snapshot(db)
assert all(count > 0 for count, checksum in before.values())
indexes = dict(db.execute("SELECT name,sql FROM sqlite_master WHERE type='index' AND tbl_name='restaurants' AND sql IS NOT NULL"))
assert len(indexes) == 5
trigger = db.execute("SELECT sql FROM sqlite_master WHERE name='trg_revisions_core_immutable'").fetchone()
assert trigger is not None
assert db.execute('PRAGMA foreign_key_check').fetchall() == []
assert db.execute('PRAGMA integrity_check').fetchall() == [('ok',)]
migration = (root / 'migrations/0002_address_optional.sql').read_text()

# Prove that a failure immediately after the cascading DROP restores both the
# original schema and all data when the transaction is rolled back.
rollback_db = sqlite3.connect(':memory:')
db.backup(rollback_db)
rollback_db.execute('PRAGMA foreign_keys=ON')
failure = migration.replace('ALTER TABLE restaurants_new RENAME TO restaurants;',
                            "INSERT INTO mutation_guards VALUES ('migration-failure',0);\nALTER TABLE restaurants_new RENAME TO restaurants;")
assert failure != migration
try:
    rollback_db.executescript('BEGIN;\n' + failure + '\nCOMMIT;')
except sqlite3.IntegrityError as error:
    assert 'CHECK constraint failed' in str(error)
    assert rollback_db.in_transaction
    rollback_db.rollback()
else:
    raise AssertionError('Injected migration failure did not abort')
assert snapshot(rollback_db) == before
assert {r[0] for r in rollback_db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")} == set(tables)
assert dict(rollback_db.execute("SELECT name,sql FROM sqlite_master WHERE type='index' AND tbl_name='restaurants' AND sql IS NOT NULL")) == indexes
assert next(r for r in rollback_db.execute('PRAGMA table_info(restaurants)') if r[1] == 'address')[3] == 1
print('PASS injected failure after DROP: atomic rollback preserves original schema and all 14 table checksums')

# D1 applies each migration in an atomic batch. The SQLite verifier supplies
# the equivalent transaction; BEGIN/COMMIT inside a D1 migration are rejected.
db.executescript('BEGIN;\n' + migration + '\nCOMMIT;')
assert db.execute('PRAGMA foreign_keys').fetchone() == (1,)
after = snapshot(db)
for table in tables:
    assert after[table] == before[table], f'{table}: {before[table]} -> {after[table]}'
    print(f'PASS {table}: rows={after[table][0]} sha256={after[table][1]} unchanged')
assert db.execute('PRAGMA foreign_key_check').fetchall() == []
assert db.execute('PRAGMA integrity_check').fetchall() == [('ok',)]
actual_indexes = {r[1] for r in db.execute('PRAGMA index_list(restaurants)') if r[3] == 'c'}
assert actual_indexes == set(indexes)
assert dict(db.execute("SELECT name,sql FROM sqlite_master WHERE type='index' AND tbl_name='restaurants' AND sql IS NOT NULL")) == indexes
assert db.execute("SELECT sql FROM sqlite_master WHERE name='trg_revisions_core_immutable'").fetchone() == trigger
assert db.execute("SELECT name FROM sqlite_master WHERE name LIKE '_b_%'").fetchall() == []
db.execute("INSERT INTO restaurants (id,user_id,name,category,address,created_at,updated_at) VALUES (?,?,'Optional address','cafe',NULL,1,1)", (uid(10000000, 99), uid(0, 1)))
for n, address in enumerate(['', '   '], 100):
    try:
        db.execute("INSERT INTO restaurants (id,user_id,name,category,address,created_at,updated_at) VALUES (?,?,'Invalid address','cafe',?,1,1)", (uid(10000000, n), uid(0, 1), address))
    except sqlite3.IntegrityError as error:
        assert 'CHECK constraint failed' in str(error)
    else:
        raise AssertionError(f'Invalid address accepted: {address!r}')
try:
    db.execute("UPDATE revisions SET diff_json='[{\"field\":\"note\",\"before\":null,\"after\":\"tampered\"}]'")
except sqlite3.IntegrityError as error:
    assert str(error) == 'revision core is immutable'
else:
    raise AssertionError('Immutable revision mutation accepted')
assert db.execute("SELECT count(*) FROM restaurants WHERE name='Invalid address'").fetchone() == (0,)
assert db.execute('PRAGMA foreign_key_check').fetchall() == []
assert db.execute('PRAGMA integrity_check').fetchall() == [('ok',)]
print('PASS foreign_key_check=0 integrity_check=ok indexes=5 exact SQL matches; NULL accepted; blank rejected; revision trigger ABORT verified')
print('PASS migration 0002: all 14 tables preserved under foreign_keys=ON')
PY
