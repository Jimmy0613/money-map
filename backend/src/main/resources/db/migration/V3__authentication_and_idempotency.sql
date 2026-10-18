CREATE TABLE auth_sessions (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
 created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL, revoked_at timestamptz,
 CHECK(expires_at>created_at)
);
CREATE TABLE refresh_tokens (
 id uuid PRIMARY KEY, session_id uuid NOT NULL REFERENCES auth_sessions ON DELETE CASCADE,
 token_hash char(64) NOT NULL UNIQUE, created_at timestamptz NOT NULL DEFAULT now(), consumed_at timestamptz
);
CREATE UNIQUE INDEX refresh_one_active ON refresh_tokens(session_id) WHERE consumed_at IS NULL;
CREATE TABLE operation_keys (
 user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE, request_key uuid NOT NULL,
 body_hash char(64) NOT NULL, transaction_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id,request_key)
);
CREATE SCHEMA privacy;
CREATE TABLE privacy.deletion_ledger (user_id uuid PRIMARY KEY, deleted_at timestamptz NOT NULL DEFAULT now());
