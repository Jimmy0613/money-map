CREATE TABLE region_dictionary (
 region_key varchar(180) PRIMARY KEY, region_code char(5) NOT NULL UNIQUE CHECK(region_code ~ '^[0-9]{5}$'),
 sido_name varchar(50) NOT NULL, sigungu_name varchar(100) NOT NULL, source_version varchar(50) NOT NULL,
 CHECK(region_key='CODE:' || region_code)
);
CREATE TABLE region_aliases (
 id uuid PRIMARY KEY, region_key varchar(180) NOT NULL REFERENCES region_dictionary ON DELETE RESTRICT,
 sido_normalized varchar(50) NOT NULL, sigungu_normalized varchar(100) NOT NULL, valid_from date NOT NULL, valid_to date,
 CHECK(valid_to IS NULL OR valid_to>=valid_from), UNIQUE(region_key,sido_normalized,sigungu_normalized,valid_from)
);
CREATE TABLE places (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
 provider varchar(10) NOT NULL DEFAULT 'KAKAO' CHECK(provider='KAKAO'), external_id varchar(100) NOT NULL CHECK(length(btrim(external_id))>0),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(user_id,provider,external_id), UNIQUE(user_id,id)
);
CREATE TABLE transactions (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
 type varchar(7) NOT NULL CHECK(type IN ('INCOME','EXPENSE')), amount bigint NOT NULL CHECK(amount BETWEEN 1 AND 999999999999),
 currency char(3) NOT NULL DEFAULT 'KRW' CHECK(currency='KRW'), category_id uuid NOT NULL, transaction_date date NOT NULL,
 memo varchar(500), version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,id), FOREIGN KEY(user_id,category_id,type) REFERENCES categories(user_id,id,type) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE transaction_locations (
 transaction_id uuid PRIMARY KEY, user_id uuid NOT NULL, source varchar(12) NOT NULL CHECK(source IN ('CURRENT','SEARCH','MAP')),
 place_id uuid, latitude numeric(9,6) NOT NULL CHECK(latitude BETWEEN -90 AND 90), longitude numeric(9,6) NOT NULL CHECK(longitude BETWEEN -180 AND 180),
 accuracy_m numeric(12,3) CHECK(accuracy_m>=0), captured_at timestamptz, low_accuracy_accepted boolean NOT NULL DEFAULT false,
 place_name varchar(200), road_address varchar(500), parcel_address varchar(500), region_code char(5) CHECK(region_code ~ '^[0-9]{5}$'),
 region_sido varchar(50), region_sigungu varchar(100), region_key varchar(180) NOT NULL DEFAULT 'UNKNOWN',
 canonical_region_key varchar(180) REFERENCES region_dictionary ON DELETE RESTRICT,
 FOREIGN KEY(user_id,transaction_id) REFERENCES transactions(user_id,id) ON DELETE CASCADE,
 FOREIGN KEY(user_id,place_id) REFERENCES places(user_id,id) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
 CHECK(region_key=COALESCE(canonical_region_key,'UNKNOWN')),
 CHECK(region_key='UNKNOWN' OR region_key ~ '^CODE:[0-9]{5}$'),
 CHECK((source='CURRENT' AND accuracy_m IS NOT NULL AND captured_at IS NOT NULL) OR (source<>'CURRENT' AND accuracy_m IS NULL AND captured_at IS NULL AND NOT low_accuracy_accepted)),
 CHECK(accuracy_m IS NULL OR accuracy_m<=1000 OR low_accuracy_accepted),
 CHECK(source='SEARCH' OR place_id IS NULL), CHECK(source<>'SEARCH' OR (place_name IS NOT NULL AND length(btrim(place_name))>0))
);
