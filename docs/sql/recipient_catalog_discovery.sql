-- Read-only queries that pull catalog candidates out of one list's usernames and groups, so a reviewer
-- can fill (or check) the recipient_* catalog tables for that list. Nothing here writes data.
-- Run recipient_catalog.sql first (the queries compare against what is already in the catalog).
-- MySQL 8.0.19+.
--
-- Usernames follow designation-unit-location, e.g. si-ahtu-nlg@suraksha:
--   first segment  -> designation candidates   (si, ci, sho, inspr1 -> inspr)
--   last segment   -> location code candidates (nlg, cyb, mdk)
--   middle         -> unit / wing candidates   (ahtu, tfc, trf, itc, ccs)
-- Group names and descriptions are the evidence that a code means a place or a rank, e.g.
-- "ALLSDPOs-NLG | SDPO Officers in Nalgonda District" pairs NLG with Nalgonda.

SET @list_id = 173;

-- 1. Designation candidates: first username segment, trailing digits removed (si1046 -> si).
--    already_mapped_to is empty for codes the catalog does not know yet.
WITH m AS (
  SELECT id, list_username,
         TRIM(BOTH '-' FROM REGEXP_REPLACE(LOWER(SUBSTRING_INDEX(list_username, '@', 1)), '[^a-z0-9]+', '-')) AS local_part
    FROM list_members WHERE list_id = @list_id
), t AS (
  SELECT m.list_username, j.pos, COALESCE(NULLIF(REGEXP_REPLACE(j.token, '[0-9]+$', ''), ''), j.token) AS base
    FROM m, JSON_TABLE(CONCAT('["', REPLACE(m.local_part, '-', '","'), '"]'), '$[*]'
            COLUMNS (pos FOR ORDINALITY, token VARCHAR(100) PATH '$')) j
   WHERE m.local_part <> ''
)
SELECT t.base AS designation_candidate, COUNT(*) AS members, MAX(d.designation_code) AS already_mapped_to,
       SUBSTRING_INDEX(GROUP_CONCAT(t.list_username ORDER BY t.list_username SEPARATOR ', '), ', ', 3) AS examples
  FROM t LEFT JOIN recipient_designation_alias d ON d.list_id = @list_id AND d.normalized_alias = t.base AND d.active = 1
 WHERE t.pos = 1
 GROUP BY t.base
 ORDER BY members DESC
 LIMIT 80;

-- 2. Location code candidates: last username segment, with the location it is mapped to today and
--    whether that mapping is VERIFIED. Unmapped codes with many members are the first to review.
WITH m AS (
  SELECT id, list_username,
         TRIM(BOTH '-' FROM REGEXP_REPLACE(LOWER(SUBSTRING_INDEX(list_username, '@', 1)), '[^a-z0-9]+', '-')) AS local_part
    FROM list_members WHERE list_id = @list_id
), t AS (
  SELECT m.id, m.list_username, j.pos, COUNT(*) OVER (PARTITION BY m.id) AS segments,
         COALESCE(NULLIF(REGEXP_REPLACE(j.token, '[0-9]+$', ''), ''), j.token) AS base
    FROM m, JSON_TABLE(CONCAT('["', REPLACE(m.local_part, '-', '","'), '"]'), '$[*]'
            COLUMNS (pos FOR ORDINALITY, token VARCHAR(100) PATH '$')) j
   WHERE m.local_part <> ''
)
SELECT t.base AS location_code_candidate, COUNT(*) AS members,
       MAX(l.name) AS mapped_location, MAX(a.verification) AS verification,
       SUBSTRING_INDEX(GROUP_CONCAT(t.list_username ORDER BY t.list_username SEPARATOR ', '), ', ', 3) AS examples
  FROM t
  LEFT JOIN recipient_location_alias a ON a.list_id = @list_id AND a.normalized_alias = t.base AND a.active = 1
  LEFT JOIN recipient_location l ON l.list_id = a.list_id AND l.id = a.location_id
 WHERE t.pos = t.segments AND t.segments > 1
 GROUP BY t.base
 ORDER BY (MAX(l.name) IS NULL) DESC, members DESC
 LIMIT 80;

-- 3. Evidence for location codes: groups whose name uses a code, next to their description.
--    description_names_location = 1 means the description spells out the mapped place (supports VERIFIED);
--    0 or an unmapped code needs a human decision before it is marked VERIFIED.
WITH g AS (
  SELECT id, sub_group_name, sub_group_desc,
         CONCAT(' ', TRIM(REGEXP_REPLACE(LOWER(sub_group_name), '[^a-z0-9]+', ' ')), ' ') AS name_text,
         CONCAT(' ', TRIM(REGEXP_REPLACE(LOWER(COALESCE(sub_group_desc, '')), '[^a-z0-9]+', ' ')), ' ') AS desc_text
    FROM list_sub_groups WHERE list_id = @list_id
)
SELECT a.alias AS code, l.name AS mapped_location, a.verification, g.sub_group_name, g.sub_group_desc,
       EXISTS (SELECT 1 FROM recipient_location_alias v
                WHERE v.list_id = a.list_id AND v.location_id = a.location_id AND v.id <> a.id
                  AND v.verification = 'VERIFIED' AND CHAR_LENGTH(v.normalized_alias) > 4
                  AND g.desc_text LIKE CONCAT('% ', v.normalized_alias, ' %')) AS description_names_location
  FROM recipient_location_alias a
  JOIN recipient_location l ON l.list_id = a.list_id AND l.id = a.location_id
  JOIN g ON g.name_text LIKE CONCAT('% ', a.normalized_alias, ' %')
 WHERE a.list_id = @list_id AND a.active = 1 AND a.normalized_alias <> TRIM(REGEXP_REPLACE(LOWER(l.name), '[^a-z0-9]+', ' '))
 ORDER BY a.verification DESC, a.alias, g.sub_group_name;

-- 4. Unit / wing candidates: middle username segments that are neither a designation nor a location.
--    Different spellings of one unit (tfc, trf, traffic) belong together in recipient_unit_alias.
WITH m AS (
  SELECT id, list_username,
         TRIM(BOTH '-' FROM REGEXP_REPLACE(LOWER(SUBSTRING_INDEX(list_username, '@', 1)), '[^a-z0-9]+', '-')) AS local_part
    FROM list_members WHERE list_id = @list_id
), t AS (
  SELECT m.id, m.list_username, j.pos, COUNT(*) OVER (PARTITION BY m.id) AS segments,
         COALESCE(NULLIF(REGEXP_REPLACE(j.token, '[0-9]+$', ''), ''), j.token) AS base
    FROM m, JSON_TABLE(CONCAT('["', REPLACE(m.local_part, '-', '","'), '"]'), '$[*]'
            COLUMNS (pos FOR ORDINALITY, token VARCHAR(100) PATH '$')) j
   WHERE m.local_part <> ''
)
SELECT t.base AS unit_candidate, COUNT(*) AS members, MAX(u.unit_canonical) AS already_unit,
       SUBSTRING_INDEX(GROUP_CONCAT(t.list_username ORDER BY t.list_username SEPARATOR ', '), ', ', 3) AS examples
  FROM t
  LEFT JOIN recipient_unit_alias u ON u.list_id = @list_id AND u.normalized_alias = t.base AND u.active = 1
 WHERE t.pos > 1 AND t.pos < t.segments
   AND NOT EXISTS (SELECT 1 FROM recipient_designation_alias d
                    WHERE d.list_id = @list_id AND d.normalized_alias = t.base AND d.active = 1)
   AND NOT EXISTS (SELECT 1 FROM recipient_location_alias a
                    WHERE a.list_id = @list_id AND a.normalized_alias = t.base AND a.active = 1)
 GROUP BY t.base
 HAVING COUNT(*) >= 3
 ORDER BY members DESC
 LIMIT 80;

-- 5. Member coverage: how many usernames the catalog can read. Members with no designation and no
--    location can only be found by name or unit, so the sample shows what to add next.
WITH m AS (
  SELECT id, list_username,
         TRIM(BOTH '-' FROM REGEXP_REPLACE(LOWER(SUBSTRING_INDEX(list_username, '@', 1)), '[^a-z0-9]+', '-')) AS local_part
    FROM list_members WHERE list_id = @list_id
), t AS (
  SELECT m.id, m.list_username, COALESCE(NULLIF(REGEXP_REPLACE(j.token, '[0-9]+$', ''), ''), j.token) AS base
    FROM m, JSON_TABLE(CONCAT('["', REPLACE(m.local_part, '-', '","'), '"]'), '$[*]'
            COLUMNS (token VARCHAR(100) PATH '$')) j
   WHERE m.local_part <> ''
), per_member AS (
  SELECT t.id, t.list_username,
         MAX(d.designation_code IS NOT NULL) AS has_designation,
         MAX(a.location_id IS NOT NULL) AS has_location
    FROM t
    LEFT JOIN recipient_designation_alias d ON d.list_id = @list_id AND d.normalized_alias = t.base AND d.active = 1
    LEFT JOIN recipient_location_alias a ON a.list_id = @list_id AND a.normalized_alias = t.base AND a.active = 1
   GROUP BY t.id, t.list_username
)
SELECT COUNT(*) AS members,
       SUM(has_designation AND has_location) AS designation_and_location,
       SUM(has_designation AND NOT has_location) AS designation_only,
       SUM(has_location AND NOT has_designation) AS location_only,
       SUM(NOT has_designation AND NOT has_location) AS neither,
       SUBSTRING_INDEX(GROUP_CONCAT(CASE WHEN NOT has_designation AND NOT has_location THEN list_username END
                                    ORDER BY list_username SEPARATOR ', '), ', ', 20) AS neither_examples
  FROM per_member;

-- 6. Group coverage: designations and locations found in each group's name + description (compound names
--    such as ALLCIs are read as "all" + alias). Groups with neither cannot be found by rank or place;
--    give them a clearer description or search them by name.
WITH g AS (
  SELECT id, sub_group_name, sub_group_desc,
         CONCAT(' ', TRIM(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(
             LOWER(CONCAT(sub_group_name, ' ', COALESCE(sub_group_desc, ''))),
             '\\bl\\s*&\\s*o\\b', ' lo '), '''s\\b', 's'), '[^a-z0-9]+', ' ')), ' ') AS text
    FROM list_sub_groups WHERE list_id = @list_id
)
SELECT g.id, g.sub_group_name, g.sub_group_desc,
       (SELECT GROUP_CONCAT(DISTINCT d.designation_code ORDER BY d.designation_code)
          FROM recipient_designation_alias d
         WHERE d.list_id = @list_id AND d.active = 1
           AND (g.text LIKE CONCAT('% ', d.normalized_alias, ' %') OR g.text LIKE CONCAT('% all', d.normalized_alias, ' %'))) AS designations,
       (SELECT GROUP_CONCAT(DISTINCT CONCAT(l.name, IF(a.verification = 'VERIFIED', '', ' (unverified)')) ORDER BY l.name)
          FROM recipient_location_alias a JOIN recipient_location l ON l.list_id = a.list_id AND l.id = a.location_id
         WHERE a.list_id = @list_id AND a.active = 1 AND g.text LIKE CONCAT('% ', a.normalized_alias, ' %')) AS locations
  FROM g
 ORDER BY (designations IS NULL AND locations IS NULL) DESC, g.sub_group_name;
