-- Synthetic public fixtures only; none of these values are credentials.
PRAGMA foreign_keys = ON;

INSERT INTO users (
    id, username, display_name, password_algorithm, password_hash,
    password_salt, pepper_version, password_changed_at, created_at, updated_at
) VALUES
    ('00000000-0000-4000-8000-000000000001', 'test_a', 'Test A',
     'pbkdf2-sha256+hmac-sha256-v1', printf('%043d', 0), printf('%022d', 0), 'v1', 1, 1, 1),
    ('00000000-0000-4000-8000-000000000002', 'test_b', 'Test B',
     'pbkdf2-sha256+hmac-sha256-v1', printf('%043d', 0), printf('%022d', 0), 'v1', 1, 1, 1);

INSERT INTO restaurants (id, user_id, name, category, address, rating_tenths, created_at, updated_at) VALUES
    ('10000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', 'Restaurant A', 'restaurant', 'Synthetic address A', 43, 1, 1),
    ('10000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000001', 'Another restaurant A', 'cafe', 'Synthetic address A2', NULL, 1, 1),
    ('10000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-000000000002', 'Restaurant B', 'restaurant', 'Synthetic address B', NULL, 1, 1);

INSERT INTO dishes (id, user_id, restaurant_id, name, rating_tenths, created_at, updated_at) VALUES
    ('20000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'Dish A', 43, 1, 1);

INSERT INTO restaurant_search (restaurant_id, user_id, content, updated_at) VALUES
    ('10000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', 'restaurant:restaurant a dish:dish a', 1);

INSERT INTO visits (id, user_id, restaurant_id, dining_date, search_text, created_at, updated_at) VALUES
    ('30000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '2024-02-29', '', 1, 1),
    ('30000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '2024-02-29', '', 1, 1);

INSERT INTO change_sets (id, user_id, request_id, created_at) VALUES
    ('40000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000001', 1),
    ('40000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000002', '50000000-0000-4000-8000-000000000002', 1);

INSERT INTO revisions (
    id, user_id, change_set_id, restaurant_id, entity_type, entity_id, action,
    entity_version_before, entity_version_after, diff_json, search_text, metadata_updated_at, created_at
) VALUES (
    '60000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001',
    'restaurant', '10000000-0000-4000-8000-000000000001', 'create', 0, 1,
    '[{"field":"name","before":null,"after":"Restaurant A"}]', '', 1, 1
);

INSERT INTO sessions (id, user_id, auth_version, device_name, created_at, last_refreshed_at, expires_at) VALUES
    ('70000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', 1, 'Synthetic device A', 1, 1, 100),
    ('70000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000002', 1, 'Synthetic device B', 1, 1, 100);

INSERT INTO refresh_tokens (id, user_id, session_id, token_hash, created_at, expires_at) VALUES
    ('80000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000001', printf('%064d', 1), 1, 100);

INSERT INTO refresh_rotation_results (old_token_id, user_id, request_id, encryption_key_version, nonce, ciphertext, created_at, expires_at) VALUES
    ('80000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000003', 'v1', printf('%016d', 0), 'synthetic-not-a-token', 1, 100);
