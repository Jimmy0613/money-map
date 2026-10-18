CREATE TABLE users (
 id uuid PRIMARY KEY, email varchar(254) NOT NULL UNIQUE CHECK(email=lower(btrim(email))),
 password_hash varchar(255) NOT NULL, display_name varchar(50), timezone varchar(64) NOT NULL DEFAULT 'Asia/Seoul',
 terms_version varchar(30) NOT NULL, terms_accepted_at timestamptz NOT NULL DEFAULT now(),
 data_revision bigint NOT NULL DEFAULT 0 CHECK(data_revision>=0), version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE categories (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
 type varchar(7) NOT NULL CHECK(type IN ('INCOME','EXPENSE')), name varchar(30) NOT NULL CHECK(length(btrim(name)) BETWEEN 1 AND 30),
 normalized_name varchar(30) NOT NULL CHECK(normalized_name=lower(btrim(name))), is_default boolean NOT NULL DEFAULT false,
 archived_at timestamptz, icon varchar(30), color varchar(7) CHECK(color IS NULL OR color ~ '^#[0-9A-Fa-f]{6}$'),
 sort_order integer NOT NULL DEFAULT 0, version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,type,normalized_name), UNIQUE(user_id,id,type)
);
