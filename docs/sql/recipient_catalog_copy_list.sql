-- Copies one list's recipient catalog to another list (a new tenant), then the copy can be edited
-- independently. Existing rows in the target list are kept; only missing rows are added.
-- Run recipient_catalog.sql once first so the tables exist. MySQL 8.0.19+.

SET @from_list_id = 173;
SET @to_list_id   = 120;

START TRANSACTION;

INSERT IGNORE INTO recipient_designation (list_id, code, label, active)
SELECT @to_list_id, code, label, active FROM recipient_designation WHERE list_id = @from_list_id;

INSERT IGNORE INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias, active)
SELECT @to_list_id, designation_code, alias, normalized_alias, active
  FROM recipient_designation_alias WHERE list_id = @from_list_id;

INSERT IGNORE INTO recipient_location (list_id, name, location_type, active)
SELECT @to_list_id, name, location_type, active FROM recipient_location WHERE list_id = @from_list_id;

-- Location ids differ per list, so aliases are re-pointed by location name.
INSERT IGNORE INTO recipient_location_alias
       (list_id, location_id, alias, normalized_alias, verification, source, notes, active)
SELECT @to_list_id, target.id, a.alias, a.normalized_alias, a.verification, a.source, a.notes, a.active
  FROM recipient_location_alias a
  JOIN recipient_location src    ON src.list_id = a.list_id AND src.id = a.location_id
  JOIN recipient_location target ON target.list_id = @to_list_id AND target.name = src.name
 WHERE a.list_id = @from_list_id;

INSERT IGNORE INTO recipient_unit (list_id, canonical, label, active)
SELECT @to_list_id, canonical, label, active FROM recipient_unit WHERE list_id = @from_list_id;

INSERT IGNORE INTO recipient_unit_alias (list_id, unit_canonical, alias, normalized_alias, active)
SELECT @to_list_id, unit_canonical, alias, normalized_alias, active
  FROM recipient_unit_alias WHERE list_id = @from_list_id;

INSERT IGNORE INTO recipient_query_word (list_id, word, category, active)
SELECT @to_list_id, word, category, active FROM recipient_query_word WHERE list_id = @from_list_id;

COMMIT;
