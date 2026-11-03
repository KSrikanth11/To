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

-- ---------------------------------------------------------------------------------------------------
-- Seed: base vocabulary for one list. Change @list_id to seed another tenant list.
-- ---------------------------------------------------------------------------------------------------
SET @list_id = 173;

START TRANSACTION;

-- Designations
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'SI', 'Sub-Inspector') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'CI', 'Circle Inspector') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'INSPR', 'Inspector') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'SHO', 'Station House Officer') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'DSP', 'Deputy Superintendent of Police') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'SDPO', 'Sub-Divisional Police Officer') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'ACP', 'Assistant Commissioner of Police') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'ADCP', 'Additional DCP') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'DCP', 'Deputy Commissioner of Police') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'ADDLSP', 'Additional SP') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'SP', 'Superintendent of Police') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'CP', 'Commissioner of Police') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'RI', 'Reserve Inspector') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, 'DI', 'Detective Inspector') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SI', 'si', 'si') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SI', 'sis', 'sis') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SI', 'wsi', 'wsi') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SI', 'wsis', 'wsis') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SI', 'sub inspector', 'sub inspector') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SI', 'sub inspectors', 'sub inspectors') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SI', 'subinspector', 'subinspector') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SI', 'subinspectors', 'subinspectors') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'CI', 'ci', 'ci') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'CI', 'cis', 'cis') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'CI', 'circle inspector', 'circle inspector') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'CI', 'circle inspectors', 'circle inspectors') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'INSPR', 'inspr', 'inspr') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'INSPR', 'insprs', 'insprs') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'INSPR', 'insp', 'insp') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'INSPR', 'inspector', 'inspector') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'INSPR', 'inspectors', 'inspectors') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SHO', 'sho', 'sho') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SHO', 'shos', 'shos') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SHO', 'station house officer', 'station house officer') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SHO', 'station house officers', 'station house officers') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'DSP', 'dsp', 'dsp') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'DSP', 'dsps', 'dsps') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'DSP', 'deputy superintendent of police', 'deputy superintendent of police') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'DSP', 'deputy superintendents of police', 'deputy superintendents of police') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SDPO', 'sdpo', 'sdpo') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SDPO', 'sdpos', 'sdpos') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SDPO', 'sub divisional police officer', 'sub divisional police officer') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SDPO', 'sub divisional police officers', 'sub divisional police officers') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'ACP', 'acp', 'acp') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'ACP', 'acps', 'acps') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'ACP', 'assistant commissioner of police', 'assistant commissioner of police') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'ADCP', 'adcp', 'adcp') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'ADCP', 'adcps', 'adcps') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'ADCP', 'addl dcp', 'addl dcp') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'ADCP', 'additional dcp', 'additional dcp') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'ADCP', 'additional deputy commissioner of police', 'additional deputy commissioner of police') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'DCP', 'dcp', 'dcp') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'DCP', 'dcps', 'dcps') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'DCP', 'deputy commissioner of police', 'deputy commissioner of police') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'ADDLSP', 'addlsp', 'addlsp') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'ADDLSP', 'addl sp', 'addl sp') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'ADDLSP', 'additional sp', 'additional sp') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'ADDLSP', 'additional superintendent of police', 'additional superintendent of police') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SP', 'sp', 'sp') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SP', 'sps', 'sps') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'SP', 'superintendent of police', 'superintendent of police') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'CP', 'cp', 'cp') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'CP', 'commissioner of police', 'commissioner of police') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'RI', 'ri', 'ri') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'RI', 'ris', 'ris') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'RI', 'reserve inspector', 'reserve inspector') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'RI', 'reserve inspectors', 'reserve inspectors') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'DI', 'di', 'di') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'DI', 'dis', 'dis') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'DI', 'detective inspector', 'detective inspector') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;
INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, 'DI', 'detective inspectors', 'detective inspectors') AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;

-- Unit and wing spellings
INSERT INTO recipient_unit (list_id, canonical, label) VALUES (@list_id, 'traffic', 'Traffic') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_unit_alias (list_id, unit_canonical, alias, normalized_alias) VALUES (@list_id, 'traffic', 'traffic', 'traffic') AS new ON DUPLICATE KEY UPDATE unit_canonical = new.unit_canonical;
INSERT INTO recipient_unit_alias (list_id, unit_canonical, alias, normalized_alias) VALUES (@list_id, 'traffic', 'tfc', 'tfc') AS new ON DUPLICATE KEY UPDATE unit_canonical = new.unit_canonical;
INSERT INTO recipient_unit_alias (list_id, unit_canonical, alias, normalized_alias) VALUES (@list_id, 'traffic', 'trf', 'trf') AS new ON DUPLICATE KEY UPDATE unit_canonical = new.unit_canonical;
INSERT INTO recipient_unit_alias (list_id, unit_canonical, alias, normalized_alias) VALUES (@list_id, 'traffic', 'trfc', 'trfc') AS new ON DUPLICATE KEY UPDATE unit_canonical = new.unit_canonical;
INSERT INTO recipient_unit_alias (list_id, unit_canonical, alias, normalized_alias) VALUES (@list_id, 'traffic', 'trafic', 'trafic') AS new ON DUPLICATE KEY UPDATE unit_canonical = new.unit_canonical;
INSERT INTO recipient_unit_alias (list_id, unit_canonical, alias, normalized_alias) VALUES (@list_id, 'traffic', 'trafffic', 'trafffic') AS new ON DUPLICATE KEY UPDATE unit_canonical = new.unit_canonical;
INSERT INTO recipient_unit (list_id, canonical, label) VALUES (@list_id, 'lo', 'Law and Order') AS new ON DUPLICATE KEY UPDATE label = new.label;
INSERT INTO recipient_unit_alias (list_id, unit_canonical, alias, normalized_alias) VALUES (@list_id, 'lo', 'lo', 'lo') AS new ON DUPLICATE KEY UPDATE unit_canonical = new.unit_canonical;
INSERT INTO recipient_unit_alias (list_id, unit_canonical, alias, normalized_alias) VALUES (@list_id, 'lo', 'lando', 'lando') AS new ON DUPLICATE KEY UPDATE unit_canonical = new.unit_canonical;
INSERT INTO recipient_unit_alias (list_id, unit_canonical, alias, normalized_alias) VALUES (@list_id, 'lo', 'landorder', 'landorder') AS new ON DUPLICATE KEY UPDATE unit_canonical = new.unit_canonical;

-- Query words
INSERT IGNORE INTO recipient_query_word (list_id, word, category) VALUES (@list_id, 'send', 'COMMAND'), (@list_id, 'find', 'COMMAND'), (@list_id, 'select', 'COMMAND'), (@list_id, 'get', 'COMMAND'), (@list_id, 'give', 'COMMAND'), (@list_id, 'list', 'COMMAND'), (@list_id, 'fetch', 'COMMAND'), (@list_id, 'show', 'COMMAND'), (@list_id, 'search', 'COMMAND'), (@list_id, 'add', 'COMMAND'), (@list_id, 'choose', 'COMMAND'), (@list_id, 'notify', 'COMMAND'), (@list_id, 'message', 'COMMAND'), (@list_id, 'messages', 'COMMAND'), (@list_id, 'text', 'COMMAND'), (@list_id, 'mail', 'COMMAND'), (@list_id, 'inform', 'COMMAND'), (@list_id, 'tell', 'COMMAND'), (@list_id, 'please', 'COMMAND'), (@list_id, 'pls', 'COMMAND'), (@list_id, 'kindly', 'COMMAND');
INSERT IGNORE INTO recipient_query_word (list_id, word, category) VALUES (@list_id, 'to', 'FILLER'), (@list_id, 'the', 'FILLER'), (@list_id, 'for', 'FILLER'), (@list_id, 'of', 'FILLER'), (@list_id, 'in', 'FILLER'), (@list_id, 'at', 'FILLER'), (@list_id, 'from', 'FILLER'), (@list_id, 'and', 'FILLER'), (@list_id, 'with', 'FILLER'), (@list_id, 'a', 'FILLER'), (@list_id, 'an', 'FILLER'), (@list_id, 'on', 'FILLER'), (@list_id, 'under', 'FILLER'), (@list_id, 'within', 'FILLER'), (@list_id, 'into', 'FILLER'), (@list_id, 'who', 'FILLER'), (@list_id, 'are', 'FILLER'), (@list_id, 'is', 'FILLER'), (@list_id, 'working', 'FILLER'), (@list_id, 'posted', 'FILLER'), (@list_id, 'me', 'FILLER'), (@list_id, 'my', 'FILLER'), (@list_id, 'our', 'FILLER'), (@list_id, 'us', 'FILLER'), (@list_id, 'them', 'FILLER'), (@list_id, 'those', 'FILLER'), (@list_id, 'these', 'FILLER'), (@list_id, 'or', 'FILLER'), (@list_id, 'also', 'FILLER'), (@list_id, 'too', 'FILLER'), (@list_id, 'only', 'FILLER'), (@list_id, 'both', 'FILLER'), (@list_id, 'it', 'FILLER'), (@list_id, 'this', 'FILLER'), (@list_id, 'that', 'FILLER'), (@list_id, 'now', 'FILLER'), (@list_id, 'him', 'FILLER'), (@list_id, 'her', 'FILLER'), (@list_id, 'their', 'FILLER'), (@list_id, 'there', 'FILLER'), (@list_id, 'here', 'FILLER');
INSERT IGNORE INTO recipient_query_word (list_id, word, category) VALUES (@list_id, 'all', 'ALL_SCOPE'), (@list_id, 'every', 'ALL_SCOPE'), (@list_id, 'each', 'ALL_SCOPE'), (@list_id, 'entire', 'ALL_SCOPE'), (@list_id, 'whole', 'ALL_SCOPE');
INSERT IGNORE INTO recipient_query_word (list_id, word, category) VALUES (@list_id, 'everyone', 'GENERIC'), (@list_id, 'everybody', 'GENERIC'), (@list_id, 'anyone', 'GENERIC'), (@list_id, 'anybody', 'GENERIC'), (@list_id, 'user', 'GENERIC'), (@list_id, 'users', 'GENERIC'), (@list_id, 'people', 'GENERIC'), (@list_id, 'person', 'GENERIC'), (@list_id, 'persons', 'GENERIC'), (@list_id, 'recipient', 'GENERIC'), (@list_id, 'recipients', 'GENERIC'), (@list_id, 'individual', 'GENERIC'), (@list_id, 'individuals', 'GENERIC'), (@list_id, 'officer', 'GENERIC'), (@list_id, 'officers', 'GENERIC'), (@list_id, 'official', 'GENERIC'), (@list_id, 'officials', 'GENERIC'), (@list_id, 'staff', 'GENERIC'), (@list_id, 'personnel', 'GENERIC'), (@list_id, 'team', 'GENERIC'), (@list_id, 'teams', 'GENERIC'), (@list_id, 'concerned', 'GENERIC'), (@list_id, 'respective', 'GENERIC'), (@list_id, 'related', 'GENERIC'), (@list_id, 'available', 'GENERIC'), (@list_id, 'active', 'GENERIC'), (@list_id, 'sir', 'GENERIC'), (@list_id, 'sirs', 'GENERIC'), (@list_id, 'madam', 'GENERIC'), (@list_id, 'garu', 'GENERIC'), (@list_id, 'gaaru', 'GENERIC'), (@list_id, 'suraksha', 'GENERIC'), (@list_id, 'telangana', 'GENERIC'), (@list_id, 'police', 'GENERIC'), (@list_id, 'department', 'GENERIC'), (@list_id, 'dept', 'GENERIC'), (@list_id, 'district', 'GENERIC'), (@list_id, 'districts', 'GENERIC'), (@list_id, 'dist', 'GENERIC'), (@list_id, 'station', 'GENERIC'), (@list_id, 'stations', 'GENERIC'), (@list_id, 'ps', 'GENERIC'), (@list_id, 'pss', 'GENERIC'), (@list_id, 'commissionerate', 'GENERIC'), (@list_id, 'commissionarate', 'GENERIC'), (@list_id, 'zone', 'GENERIC'), (@list_id, 'zones', 'GENERIC'), (@list_id, 'division', 'GENERIC'), (@list_id, 'divisions', 'GENERIC'), (@list_id, 'subdivision', 'GENERIC'), (@list_id, 'subdivisions', 'GENERIC'), (@list_id, 'sub', 'GENERIC'), (@list_id, 'unit', 'GENERIC'), (@list_id, 'units', 'GENERIC'), (@list_id, 'area', 'GENERIC'), (@list_id, 'areas', 'GENERIC'), (@list_id, 'region', 'GENERIC'), (@list_id, 'rank', 'GENERIC'), (@list_id, 'ranks', 'GENERIC'), (@list_id, 'wing', 'GENERIC'), (@list_id, 'wings', 'GENERIC');
INSERT IGNORE INTO recipient_query_word (list_id, word, category) VALUES (@list_id, 'group', 'GROUP_HINT'), (@list_id, 'groups', 'GROUP_HINT'), (@list_id, 'subgroup', 'GROUP_HINT'), (@list_id, 'subgroups', 'GROUP_HINT');
INSERT IGNORE INTO recipient_query_word (list_id, word, category) VALUES (@list_id, 'member', 'MEMBER_HINT'), (@list_id, 'members', 'MEMBER_HINT'), (@list_id, 'individual', 'MEMBER_HINT'), (@list_id, 'individuals', 'MEMBER_HINT');
INSERT IGNORE INTO recipient_query_word (list_id, word, category) VALUES (@list_id, 'except', 'EXCLUSION'), (@list_id, 'excluding', 'EXCLUSION'), (@list_id, 'exclude', 'EXCLUSION'), (@list_id, 'without', 'EXCLUSION'), (@list_id, 'but', 'EXCLUSION'), (@list_id, 'not', 'EXCLUSION');

COMMIT;
