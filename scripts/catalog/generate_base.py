"""Generates docs/sql/recipient_catalog.sql: the tables (scripts/catalog/schema.sql) plus the base vocabulary
(core designations, unit spellings, query words). Locations and member profiles come from the decoded workbook
(scripts/decoded_import/generate.py), not from here.

    python3 scripts/catalog/generate_base.py docs/sql/recipient_catalog.sql
"""
import re
import sys
from pathlib import Path

out_path = Path(sys.argv[1])


def normalize(v):  # mirrors TextNormalizer.normalize (ASCII alnum, like Java's \p{Alnum})
    v = v.lower()
    v = re.sub(r"\bl\s*&\s*o\b", " lo ", v)
    v = re.sub(r"\blaw\s+(?:and|&)\s+order\b", " lo ", v)
    v = re.sub(r"'s\b", "s", v)
    v = re.sub(r"[^a-z0-9]+", " ", v).strip()
    return re.sub(r"\s+", " ", v)


def q(v):
    return "NULL" if v is None else "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


DESIGNATIONS = [
    ("SI", "Sub-Inspector", ["si", "sis", "wsi", "wsis", "sub inspector", "sub inspectors", "subinspector", "subinspectors"]),
    ("CI", "Circle Inspector", ["ci", "cis", "circle inspector", "circle inspectors"]),
    ("INSPR", "Inspector", ["inspr", "insprs", "insp", "inspector", "inspectors"]),
    ("SHO", "Station House Officer", ["sho", "shos", "station house officer", "station house officers"]),
    ("DSP", "Deputy Superintendent of Police", ["dsp", "dsps", "deputy superintendent of police", "deputy superintendents of police"]),
    ("SDPO", "Sub-Divisional Police Officer", ["sdpo", "sdpos", "sub divisional police officer", "sub divisional police officers"]),
    ("ACP", "Assistant Commissioner of Police", ["acp", "acps", "assistant commissioner of police"]),
    ("ADCP", "Additional DCP", ["adcp", "adcps", "addl dcp", "additional dcp", "additional deputy commissioner of police"]),
    ("DCP", "Deputy Commissioner of Police", ["dcp", "dcps", "deputy commissioner of police"]),
    ("ADDLSP", "Additional SP", ["addlsp", "addl sp", "additional sp", "additional superintendent of police"]),
    ("SP", "Superintendent of Police", ["sp", "sps", "superintendent of police"]),
    ("CP", "Commissioner of Police", ["cp", "commissioner of police"]),
    ("RI", "Reserve Inspector", ["ri", "ris", "reserve inspector", "reserve inspectors"]),
    ("DI", "Detective Inspector", ["di", "dis", "detective inspector", "detective inspectors"]),
]
UNITS = [("traffic", "Traffic", ["traffic", "tfc", "trf", "trfc", "trafic", "trafffic"]),
         ("lo", "Law and Order", ["lo", "lando", "landorder"])]
WORDS = {
    "COMMAND": "send find select get give list fetch show search add choose notify message messages text mail inform tell please pls kindly",
    "FILLER": "to the for of in at from and with a an on under within into who are is working posted me my our us them those these or also too only both it this that now him her their there here",
    "ALL_SCOPE": "all every each entire whole",
    "GENERIC": "everyone everybody anyone anybody user users people person persons recipient recipients individual individuals officer officers official officials staff personnel team teams concerned respective related available active sir sirs madam garu gaaru suraksha telangana police department dept district districts dist station stations ps pss commissionerate commissionarate zone zones division divisions subdivision subdivisions sub unit units area areas region rank ranks wing wings",
    "GROUP_HINT": "group groups subgroup subgroups",
    "MEMBER_HINT": "member members individual individuals",
    "EXCLUSION": "except excluding exclude without but not",
}
out = []
w = out.append
w(Path(__file__).with_name("schema.sql").read_text().rstrip("\n"))
w("""
-- ---------------------------------------------------------------------------------------------------
-- Seed: base vocabulary for one list. Change @list_id to seed another tenant list.
-- ---------------------------------------------------------------------------------------------------
SET @list_id = 173;

START TRANSACTION;
""")
w("-- Designations")
for code, label, aliases in DESIGNATIONS:
    w(f"INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, {q(code)}, {q(label)}) AS new ON DUPLICATE KEY UPDATE label = new.label;")
for code, label, aliases in DESIGNATIONS:
    for alias in aliases:
        w(f"INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES (@list_id, {q(code)}, {q(alias)}, {q(normalize(alias))}) AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code;")

w("\n-- Unit and wing spellings")
for canonical, label, aliases in UNITS:
    w(f"INSERT INTO recipient_unit (list_id, canonical, label) VALUES (@list_id, {q(canonical)}, {q(label)}) AS new ON DUPLICATE KEY UPDATE label = new.label;")
    for alias in aliases:
        w(f"INSERT INTO recipient_unit_alias (list_id, unit_canonical, alias, normalized_alias) VALUES (@list_id, {q(canonical)}, {q(alias)}, {q(normalize(alias).replace(' ', ''))}) AS new ON DUPLICATE KEY UPDATE unit_canonical = new.unit_canonical;")

w("\n-- Query words")
for category, words in WORDS.items():
    values = ", ".join(f"(@list_id, {q(word)}, {q(category)})" for word in words.split())
    w(f"INSERT IGNORE INTO recipient_query_word (list_id, word, category) VALUES {values};")

w("\nCOMMIT;")
out_path.parent.mkdir(parents=True, exist_ok=True)
out_path.write_text("\n".join(out) + "\n")
