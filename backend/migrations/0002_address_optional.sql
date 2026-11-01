-- D1 executes this migration as one atomic batch. Do not run individual statements.
-- Back up every cascading child before dropping the parent, with foreign keys ON.
PRAGMA foreign_keys = ON;

CREATE TABLE _b_dishes AS SELECT * FROM dishes;
CREATE TABLE _b_visits AS SELECT * FROM visits;
CREATE TABLE _b_search AS SELECT * FROM restaurant_search;
CREATE TABLE _b_rev AS SELECT * FROM revisions;

CREATE TABLE restaurants_new (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 120),
    category TEXT NOT NULL CHECK (
        category IN ('restaurant', 'cafe', 'bakery', 'bar', 'street_food', 'other')
    ),
    cuisine TEXT CHECK (cuisine IS NULL OR length(cuisine) BETWEEN 1 AND 60),
    cuisine_normalized TEXT,
    address TEXT CHECK (address IS NULL OR length(trim(address)) BETWEEN 1 AND 500),
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

INSERT INTO restaurants_new SELECT id,user_id,name,category,cuisine,cuisine_normalized,address,rating_tenths,review,note,version,created_at,updated_at,deleted_at FROM restaurants;
DROP TABLE restaurants;
ALTER TABLE restaurants_new RENAME TO restaurants;

INSERT INTO dishes SELECT * FROM _b_dishes;
DROP TABLE _b_dishes;
INSERT INTO visits SELECT * FROM _b_visits;
DROP TABLE _b_visits;
INSERT INTO restaurant_search SELECT * FROM _b_search;
DROP TABLE _b_search;
INSERT INTO revisions SELECT * FROM _b_rev;
DROP TABLE _b_rev;

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
