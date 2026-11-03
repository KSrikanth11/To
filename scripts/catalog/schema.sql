-- Recipient resolver tables, one catalog per tenant list. Step 1 of 2: run this file, then
-- recipient_decoded_import.sql (the decoded-usernames workbook: ranks, places and one profile per member).
-- Every table carries list_id (the same value as list_members.list_id / list_sub_groups.list_id), so each
-- list has its own designations, locations, unit spellings and query words, and one list's aliases can
-- never affect another list's results. MySQL 8.0.19+, utf8mb4. Safe to re-run.
--
-- Usage: set @list_id below and run the whole file. It creates the tables and seeds the base vocabulary
-- (core designations, unit spellings, query words); every location comes from recipient_decoded_import.sql.
--
-- Rules the application applies to these tables:
--   * Rows are read only for the request's list_id, and only where active = 1.
--   * normalized_alias = lowercase, "'s" -> "s", L&O -> "lo", every non [a-z0-9] run -> one space.
--     The app re-normalizes `alias` on load; the column exists so MySQL can enforce uniqueness per list.
--   * A location alias with verification = 'UNVERIFIED' can only produce SUGGESTED matches.
--   * recipient_location.parent_location_id is informational (station -> district, zone -> commissionerate);
--     the application does not expand it, because each decoded member lists its own district, zone and station.
--   * A member profile is used only while list_members.list_username still equals its `username`.

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS recipient_designation (
  list_id     BIGINT       NOT NULL COMMENT 'Tenant: list_members.list_id',
  code        VARCHAR(20)  NOT NULL,
  label       VARCHAR(100) NOT NULL,
  parent_code VARCHAR(20)  NULL COMMENT 'Wider designation this one belongs to (WSI -> SI); a search for SI also finds WSI',
  active      TINYINT(1)   NOT NULL DEFAULT 1,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (list_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS recipient_designation_alias (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  list_id           BIGINT       NOT NULL,
  designation_code  VARCHAR(20)  NOT NULL,
  alias             VARCHAR(100) NOT NULL,
  normalized_alias  VARCHAR(100) NOT NULL,
  active            TINYINT(1)   NOT NULL DEFAULT 1,
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_designation_alias (list_id, normalized_alias),
  KEY ix_designation_alias_code (list_id, designation_code),
  CONSTRAINT fk_designation_alias_code FOREIGN KEY (list_id, designation_code)
    REFERENCES recipient_designation (list_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS recipient_location (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  list_id             BIGINT       NOT NULL,
  name                VARCHAR(150) NOT NULL,
  location_type       ENUM('DISTRICT','COMMISSIONERATE','ZONE','SUB_DIVISION','DIVISION','OTHER') NOT NULL DEFAULT 'OTHER',
  parent_location_id  BIGINT       NULL COMMENT 'Reserved for the location hierarchy (currently disabled)',
  active              TINYINT(1)   NOT NULL DEFAULT 1,
  created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_location_name (list_id, name),
  UNIQUE KEY uq_location_tenant (list_id, id),
  -- A parent must belong to the same list.
  CONSTRAINT fk_location_parent FOREIGN KEY (list_id, parent_location_id) REFERENCES recipient_location (list_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS recipient_location_alias (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  list_id           BIGINT       NOT NULL,
  location_id       BIGINT       NOT NULL,
  alias             VARCHAR(150) NOT NULL,
  normalized_alias  VARCHAR(150) NOT NULL,
  verification      ENUM('VERIFIED','UNVERIFIED') NOT NULL DEFAULT 'UNVERIFIED',
  source            VARCHAR(30)  NOT NULL DEFAULT 'admin' COMMENT 'decoded_xlsx = imported from the decoded workbook; admin = added by hand',
  notes             VARCHAR(255) NULL COMMENT 'Evidence, e.g. the subgroup that pairs this code with the name',
  active            TINYINT(1)   NOT NULL DEFAULT 1,
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_location_alias (list_id, location_id, normalized_alias),
  KEY ix_location_alias_phrase (list_id, normalized_alias),
  -- An alias must point at a location of the same list.
  CONSTRAINT fk_location_alias_location FOREIGN KEY (list_id, location_id) REFERENCES recipient_location (list_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS recipient_unit (
  list_id     BIGINT       NOT NULL,
  canonical   VARCHAR(50)  NOT NULL,
  label       VARCHAR(100) NOT NULL,
  active      TINYINT(1)   NOT NULL DEFAULT 1,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (list_id, canonical)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS recipient_unit_alias (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  list_id           BIGINT      NOT NULL,
  unit_canonical    VARCHAR(50) NOT NULL,
  alias             VARCHAR(50) NOT NULL,
  normalized_alias  VARCHAR(50) NOT NULL COMMENT 'Single word: spaces removed after normalization',
  active            TINYINT(1)  NOT NULL DEFAULT 1,
  created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_unit_alias (list_id, normalized_alias),
  CONSTRAINT fk_unit_alias_unit FOREIGN KEY (list_id, unit_canonical) REFERENCES recipient_unit (list_id, canonical)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS recipient_query_word (
  list_id     BIGINT      NOT NULL,
  word        VARCHAR(50) NOT NULL,
  category    ENUM('COMMAND','FILLER','ALL_SCOPE','GENERIC','GROUP_HINT','MEMBER_HINT','EXCLUSION') NOT NULL
              COMMENT 'EXCLUSION starts an exclusion; GROUP_HINT/MEMBER_HINT pick the recipient type; ALL_SCOPE sets scope ALL; all others are ignored',
  active      TINYINT(1)  NOT NULL DEFAULT 1,
  created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (list_id, word, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Decoded members (loaded by recipient_decoded_import.sql). A profile is used only while list_members.list_username
-- still equals `username`; after a rename the app reads the username instead.
CREATE TABLE IF NOT EXISTS recipient_member_profile (
  list_id           BIGINT       NOT NULL,
  member_id         BIGINT       NOT NULL COMMENT 'list_members.id',
  username          VARCHAR(255) NOT NULL COMMENT 'list_username this profile was decoded from',
  confidence        ENUM('CONFIDENT','LIKELY','NEEDS_REVIEW','PERSONAL','SYSTEM') NOT NULL
                    COMMENT 'SYSTEM accounts are never matched by designation or location',
  designation_text  VARCHAR(255) NULL,
  rank_text         VARCHAR(255) NULL,
  badge_no          VARCHAR(20)  NULL,
  wing_text         VARCHAR(255) NULL,
  station_text      VARCHAR(255) NULL,
  zone_text         VARCHAR(255) NULL,
  district_text     VARCHAR(255) NULL,
  full_expansion    VARCHAR(600) NULL,
  source            VARCHAR(30)  NOT NULL DEFAULT 'admin' COMMENT 'decoded_xlsx = imported from the decoded workbook',
  active            TINYINT(1)   NOT NULL DEFAULT 1,
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (list_id, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What a decoded member is: designation codes and location ids from the list's own catalog.
-- verified = 0 for values the decoder marked "?" or rows it was unsure about; they only give SUGGESTED matches.
CREATE TABLE IF NOT EXISTS recipient_member_attribute (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  list_id           BIGINT       NOT NULL,
  member_id         BIGINT       NOT NULL,
  kind              ENUM('DESIGNATION','LOCATION') NOT NULL,
  designation_code  VARCHAR(20)  NULL,
  location_id       BIGINT       NULL,
  verified          TINYINT(1)   NOT NULL DEFAULT 0,
  attribute_key     VARCHAR(40)  GENERATED ALWAYS AS (CONCAT(kind, ':', COALESCE(designation_code, location_id))) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uq_member_attribute (list_id, member_id, attribute_key),
  KEY ix_member_attribute_location (list_id, location_id),
  CONSTRAINT fk_member_attribute_profile FOREIGN KEY (list_id, member_id)
    REFERENCES recipient_member_profile (list_id, member_id) ON DELETE CASCADE,
  CONSTRAINT fk_member_attribute_designation FOREIGN KEY (list_id, designation_code)
    REFERENCES recipient_designation (list_id, code),
  CONSTRAINT fk_member_attribute_location FOREIGN KEY (list_id, location_id) REFERENCES recipient_location (list_id, id),
  CONSTRAINT ck_member_attribute_kind CHECK (
    (kind = 'DESIGNATION' AND designation_code IS NOT NULL AND location_id IS NULL) OR
    (kind = 'LOCATION' AND location_id IS NOT NULL AND designation_code IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One query for the application loader: SELECT ... FROM recipient_catalog_phrase WHERE list_id = ?
CREATE OR REPLACE VIEW recipient_catalog_phrase AS
  SELECT d.list_id, 'DESIGNATION' AS kind, d.code AS catalog_key, d.label, a.alias, a.normalized_alias, 1 AS verified,
         d.parent_code
    FROM recipient_designation d
    JOIN recipient_designation_alias a ON a.list_id = d.list_id AND a.designation_code = d.code
   WHERE d.active = 1 AND a.active = 1
  UNION ALL
  SELECT l.list_id, 'LOCATION', l.name, l.name, a.alias, a.normalized_alias, a.verification = 'VERIFIED', NULL
    FROM recipient_location l
    JOIN recipient_location_alias a ON a.list_id = l.list_id AND a.location_id = l.id
   WHERE l.active = 1 AND a.active = 1
  UNION ALL
  SELECT u.list_id, 'UNIT', u.canonical, u.label, a.alias, a.normalized_alias, 1, NULL
    FROM recipient_unit u
    JOIN recipient_unit_alias a ON a.list_id = u.list_id AND a.unit_canonical = u.canonical
   WHERE u.active = 1 AND a.active = 1;

