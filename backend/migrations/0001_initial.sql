PRAGMA foreign_keys = ON;

CREATE TABLE users (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    username TEXT NOT NULL UNIQUE CHECK (
        length(username) BETWEEN 3 AND 32
        AND username = lower(username)
        AND username NOT GLOB '*[^a-z0-9_]*'
        AND substr(username, 1, 1) GLOB '[a-z]'
    ),
    display_name TEXT NOT NULL CHECK (length(trim(display_name)) BETWEEN 1 AND 50),
    password_algorithm TEXT NOT NULL
        CHECK (password_algorithm = 'pbkdf2-sha256+hmac-sha256-v1'),
    password_hash TEXT NOT NULL CHECK (length(password_hash) = 43),
    password_salt TEXT NOT NULL CHECK (length(password_salt) = 22),
    password_iterations INTEGER NOT NULL DEFAULT 10000 CHECK (password_iterations >= 10000),
    pepper_version TEXT NOT NULL,
    password_changed_at INTEGER NOT NULL,
    auth_version INTEGER NOT NULL DEFAULT 1 CHECK (auth_version >= 1),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    deleted_at INTEGER CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);
CREATE INDEX idx_users_deleted ON users(deleted_at)
    WHERE deleted_at IS NOT NULL;

CREATE TABLE sessions (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    auth_version INTEGER NOT NULL CHECK (auth_version >= 1),
    device_name TEXT NOT NULL CHECK (length(device_name) BETWEEN 1 AND 80),
    created_at INTEGER NOT NULL,
    last_refreshed_at INTEGER NOT NULL CHECK (last_refreshed_at >= created_at),
    expires_at INTEGER NOT NULL CHECK (expires_at > created_at),
    revoked_at INTEGER,
    revoke_reason TEXT CHECK (
        revoke_reason IS NULL OR revoke_reason IN (
            'logout', 'logout_all', 'password_change', 'recovery',
            'token_reuse', 'user_revoke', 'account_delete', 'expired'
        )
    ),
    UNIQUE (user_id, id)
);
CREATE INDEX idx_sessions_user ON sessions(user_id, created_at DESC, id DESC);
CREATE INDEX idx_sessions_expiry ON sessions(expires_at);
CREATE INDEX idx_sessions_active ON sessions(user_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE refresh_tokens (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE CHECK (length(token_hash) = 64),
    parent_token_id TEXT CHECK (
        parent_token_id IS NULL OR length(parent_token_id) = 36
    ),
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at > created_at),
    consumed_at INTEGER,
    consumed_request_id TEXT CHECK (
        consumed_request_id IS NULL OR length(consumed_request_id) = 36
    ),
    UNIQUE (user_id, id),
    FOREIGN KEY (user_id, session_id)
        REFERENCES sessions(user_id, id) ON DELETE CASCADE,
    CHECK (
        (consumed_at IS NULL AND consumed_request_id IS NULL)
        OR (consumed_at IS NOT NULL AND consumed_request_id IS NOT NULL)
    )
);
CREATE UNIQUE INDEX idx_refresh_one_child ON refresh_tokens(parent_token_id)
    WHERE parent_token_id IS NOT NULL;
CREATE INDEX idx_refresh_session ON refresh_tokens(session_id, created_at, id);

CREATE TABLE refresh_rotation_results (
    old_token_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    request_id TEXT NOT NULL CHECK (length(request_id) = 36),
    encryption_key_version TEXT NOT NULL,
    nonce TEXT NOT NULL CHECK (length(nonce) = 16),
    ciphertext TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at > created_at),
    FOREIGN KEY (user_id, old_token_id)
        REFERENCES refresh_tokens(user_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_refresh_results_expiry ON refresh_rotation_results(expires_at);

CREATE TABLE recovery_codes (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash TEXT NOT NULL UNIQUE CHECK (length(code_hash) = 64),
    created_at INTEGER NOT NULL,
    used_at INTEGER,
    disabled_at INTEGER
);
CREATE INDEX idx_recovery_user ON recovery_codes(user_id);
CREATE INDEX idx_recovery_active ON recovery_codes(user_id, code_hash)
    WHERE used_at IS NULL AND disabled_at IS NULL;

CREATE TABLE auth_rate_limits (
    scope TEXT NOT NULL CHECK (scope IN ('login_account', 'login_ip', 'register_ip', 'recovery_account', 'recovery_ip')),
    key_hash TEXT NOT NULL CHECK (length(key_hash) = 64),
    window_start INTEGER NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts >= 1),
    expires_at INTEGER NOT NULL CHECK (expires_at > window_start),
    PRIMARY KEY (scope, key_hash, window_start)
);
CREATE INDEX idx_auth_rate_limits_expiry ON auth_rate_limits(expires_at);

CREATE TABLE restaurants (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 120),
    category TEXT NOT NULL CHECK (
        category IN ('restaurant', 'cafe', 'bakery', 'bar', 'street_food', 'other')
    ),
    cuisine TEXT CHECK (cuisine IS NULL OR length(cuisine) BETWEEN 1 AND 60),
    cuisine_normalized TEXT,
    address TEXT NOT NULL CHECK (length(trim(address)) BETWEEN 1 AND 500),
    rating_tenths INTEGER CHECK (
        rating_tenths IS NULL OR (
            typeof(rating_tenths) = 'integer' AND rating_tenths BETWEEN 10 AND 50
        )
    ),
    review TEXT CHECK (review IS NULL OR length(review) <= 4000),
    note TEXT CHECK (note IS NULL OR length(note) <= 4000),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    deleted_at INTEGER CHECK (deleted_at IS NULL OR deleted_at >= created_at),
    UNIQUE (user_id, id),
    CHECK (
        (cuisine IS NULL AND cuisine_normalized IS NULL)
        OR (cuisine IS NOT NULL AND cuisine_normalized IS NOT NULL AND length(cuisine_normalized) > 0)
    )
);
CREATE INDEX idx_restaurants_live_updated ON restaurants(user_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_restaurants_live_category ON restaurants(user_id, category, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_restaurants_live_cuisine ON restaurants(user_id, cuisine_normalized, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_restaurants_live_rating ON restaurants(user_id, rating_tenths DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_restaurants_deleted ON restaurants(user_id, deleted_at DESC, id DESC)
    WHERE deleted_at IS NOT NULL;

CREATE TABLE dishes (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL,
    restaurant_id TEXT NOT NULL,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 120),
    rating_tenths INTEGER CHECK (
        rating_tenths IS NULL OR (
            typeof(rating_tenths) = 'integer' AND rating_tenths BETWEEN 10 AND 50
        )
    ),
    review TEXT CHECK (review IS NULL OR length(review) <= 2000),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    deleted_at INTEGER CHECK (deleted_at IS NULL OR deleted_at >= created_at),
    UNIQUE (user_id, id),
    UNIQUE (user_id, restaurant_id, id),
    FOREIGN KEY (user_id, restaurant_id)
        REFERENCES restaurants(user_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_dishes_restaurant_live ON dishes(user_id, restaurant_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_dishes_user_live ON dishes(user_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_dishes_restaurant_rating ON dishes(user_id, restaurant_id, rating_tenths DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_dishes_deleted ON dishes(user_id, deleted_at DESC, id DESC)
    WHERE deleted_at IS NOT NULL;

CREATE TABLE restaurant_search (
    restaurant_id TEXT PRIMARY KEY CHECK (length(restaurant_id) = 36),
    user_id TEXT NOT NULL,
    content TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (user_id, restaurant_id),
    FOREIGN KEY (user_id, restaurant_id)
        REFERENCES restaurants(user_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_restaurant_search_user ON restaurant_search(user_id, updated_at DESC, restaurant_id);

CREATE TABLE visits (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL,
    restaurant_id TEXT NOT NULL,
    dining_date TEXT NOT NULL CHECK (
        length(dining_date) = 10
        AND dining_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        AND dining_date BETWEEN '1900-01-01' AND '2100-12-31'
        AND date(dining_date, '+0 days') IS NOT NULL
        AND date(dining_date, '+0 days') = dining_date
    ),
    meal TEXT CHECK (meal IS NULL OR meal IN ('breakfast', 'lunch', 'dinner', 'other')),
    note TEXT CHECK (note IS NULL OR length(note) <= 2000),
    search_text TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    deleted_at INTEGER CHECK (deleted_at IS NULL OR deleted_at >= created_at),
    UNIQUE (user_id, id),
    UNIQUE (user_id, restaurant_id, id),
    FOREIGN KEY (user_id, restaurant_id)
        REFERENCES restaurants(user_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_visits_restaurant_date ON visits(user_id, restaurant_id, dining_date DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_visits_user_date ON visits(user_id, dining_date DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_visits_deleted ON visits(user_id, deleted_at DESC, id DESC)
    WHERE deleted_at IS NOT NULL;

CREATE TABLE change_sets (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL CHECK (length(request_id) = 36),
    created_at INTEGER NOT NULL,
    UNIQUE (user_id, id),
    UNIQUE (user_id, request_id)
);
CREATE INDEX idx_change_sets_time ON change_sets(user_id, created_at DESC, id DESC);

CREATE TABLE revisions (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    change_set_id TEXT NOT NULL,
    restaurant_id TEXT NOT NULL,
    entity_type TEXT NOT NULL CHECK (entity_type IN ('restaurant', 'dish', 'visit')),
    entity_id TEXT NOT NULL CHECK (length(entity_id) = 36),
    dish_id TEXT,
    visit_id TEXT,
    action TEXT NOT NULL CHECK (action IN ('create', 'update', 'delete', 'restore')),
    entity_version_before INTEGER NOT NULL CHECK (entity_version_before >= 0),
    entity_version_after INTEGER NOT NULL
        CHECK (entity_version_after = entity_version_before + 1),
    diff_json TEXT NOT NULL CHECK (
        json_valid(diff_json)
        AND json_type(diff_json) = 'array'
        AND json_array_length(diff_json) > 0
        AND length(CAST(diff_json AS BLOB)) <= 131072
    ),
    source_revision_id TEXT CHECK (
        source_revision_id IS NULL OR length(source_revision_id) = 36
    ),
    search_text TEXT NOT NULL,
    note TEXT CHECK (note IS NULL OR length(note) <= 500),
    note_search TEXT NOT NULL DEFAULT '',
    metadata_version INTEGER NOT NULL DEFAULT 1 CHECK (metadata_version >= 1),
    metadata_updated_at INTEGER NOT NULL,
    deleted_at INTEGER,
    created_at INTEGER NOT NULL,
    UNIQUE (user_id, entity_type, entity_id, entity_version_after),
    FOREIGN KEY (user_id, change_set_id)
        REFERENCES change_sets(user_id, id) ON DELETE CASCADE,
    FOREIGN KEY (user_id, restaurant_id)
        REFERENCES restaurants(user_id, id) ON DELETE CASCADE,
    FOREIGN KEY (user_id, restaurant_id, dish_id)
        REFERENCES dishes(user_id, restaurant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (user_id, restaurant_id, visit_id)
        REFERENCES visits(user_id, restaurant_id, id) ON DELETE CASCADE,
    CHECK (
        (entity_type = 'restaurant' AND entity_id = restaurant_id AND dish_id IS NULL AND visit_id IS NULL)
        OR (entity_type = 'dish' AND dish_id IS NOT NULL AND entity_id = dish_id AND visit_id IS NULL)
        OR (entity_type = 'visit' AND visit_id IS NOT NULL AND entity_id = visit_id AND dish_id IS NULL)
    ),
    CHECK (
        (action = 'create' AND entity_version_before = 0)
        OR (action <> 'create' AND entity_version_before >= 1)
    ),
    CHECK (metadata_updated_at >= created_at),
    CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);
CREATE INDEX idx_revisions_restaurant_time ON revisions(user_id, restaurant_id, created_at DESC, id DESC);
CREATE INDEX idx_revisions_entity_time ON revisions(user_id, entity_type, entity_id, created_at DESC, id DESC);
CREATE INDEX idx_revisions_visible_time ON revisions(user_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_revisions_hidden_time ON revisions(user_id, deleted_at DESC, id DESC)
    WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_revisions_change_set ON revisions(user_id, change_set_id);

CREATE TRIGGER trg_revisions_core_immutable
BEFORE UPDATE ON revisions
WHEN NEW.id IS NOT OLD.id
    OR NEW.user_id IS NOT OLD.user_id
    OR NEW.change_set_id IS NOT OLD.change_set_id
    OR NEW.restaurant_id IS NOT OLD.restaurant_id
    OR NEW.entity_type IS NOT OLD.entity_type
    OR NEW.entity_id IS NOT OLD.entity_id
    OR NEW.dish_id IS NOT OLD.dish_id
    OR NEW.visit_id IS NOT OLD.visit_id
    OR NEW.action IS NOT OLD.action
    OR NEW.entity_version_before IS NOT OLD.entity_version_before
    OR NEW.entity_version_after IS NOT OLD.entity_version_after
    OR NEW.diff_json IS NOT OLD.diff_json
    OR NEW.source_revision_id IS NOT OLD.source_revision_id
    OR NEW.search_text IS NOT OLD.search_text
    OR NEW.created_at IS NOT OLD.created_at
BEGIN
    SELECT RAISE(ABORT, 'revision core is immutable');
END;

CREATE TABLE idempotency_keys (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    idempotency_key TEXT NOT NULL CHECK (length(idempotency_key) = 36),
    method TEXT NOT NULL CHECK (method IN ('POST', 'PATCH', 'DELETE')),
    path TEXT NOT NULL,
    request_hash TEXT NOT NULL CHECK (length(request_hash) = 64),
    response_status INTEGER NOT NULL CHECK (response_status BETWEEN 200 AND 299),
    response_json TEXT NOT NULL CHECK (json_valid(response_json)),
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at > created_at),
    PRIMARY KEY (user_id, idempotency_key)
);
CREATE INDEX idx_idempotency_expiry ON idempotency_keys(expires_at);

CREATE TABLE mutation_guards (
    operation_id TEXT PRIMARY KEY,
    affected_rows INTEGER NOT NULL CHECK (affected_rows = 1)
);
