"""Builds docs/sql/recipient_decoded_import.sql and review CSVs from the decoded-usernames workbook.

    python3 -m venv .venv && .venv/bin/pip install openpyxl
    .venv/bin/python scripts/decoded_import/generate.py \
        src/main/resources/TG_Police_Usernames_Decoded.xlsx docs/sql/recipient_decoded_import.sql docs/decoded_review

Confidence rules (a match is only CONFIRMED through verified evidence):
  * Confident row      -> every field verified
  * Likely row         -> designation and district verified unless marked "?"; station and zone unverified
  * Needs review       -> every field unverified
  * Personal name      -> no designation; locations unverified (the account is found by name)
  * Test / system      -> no designation or location (never matched by rank or place)
  * "?" inside a value -> that value unverified; a bare "?" or "not decoded" value is dropped
"""
import csv
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

import openpyxl

XLSX, OUT_SQL, REVIEW_DIR = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
INDEX_JSON = Path(__file__).resolve().parents[2] / "src/main/resources/suraksha_recipient_search_index.json"


def normalize(value):  # mirrors TextNormalizer.normalize
    v = (value or "").lower()
    v = re.sub(r"\bl\s*&\s*o\b", " lo ", v)
    v = re.sub(r"\blaw\s+(?:and|&)\s+order\b", " lo ", v)
    v = re.sub(r"'s\b", "s", v)
    v = re.sub(r"[^a-z0-9]+", " ", v).strip()
    return re.sub(r"\s+", " ", v)


def q(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def uncertain(value):
    return isinstance(value, str) and "?" in value


def clean(value):
    """Strips uncertainty marks; None for values that carry no information."""
    if value is None:
        return None
    text = re.sub(r"\s*\?\s*", " ", str(value)).strip(" ,–-")
    text = re.sub(r"\s+", " ", text)
    if not text or "not decoded" in text.lower():
        return None
    return text


# Query words (recipient_query_word seed): an alias equal to one of these is never registered.
QUERY_WORDS = set("""send find select get give list fetch show search add choose notify message messages text mail
inform tell please pls kindly to the for of in at from and with a an on under within into who are is working posted me
my our us them those these or also too only both it this that now him her their there here all every each entire whole
everyone everybody anyone anybody user users people person persons recipient recipients individual individuals officer
officers official officials staff personnel team teams concerned respective related available active sir sirs madam garu
gaaru suraksha telangana police department dept district districts dist station stations ps pss commissionerate
commissionarate zone zones division divisions subdivision subdivisions sub unit units area areas region rank ranks wing
wings group groups subgroup subgroups member members except excluding exclude without but not do""".split())

# Ranks and posts people hold: code -> (label, key abbreviations, query aliases, parent code).
# Offices, cells, places and wings that the workbook lists under "Designation / Post" are deliberately absent.
DESIGNATIONS = {
    "SI": ("Sub-Inspector", "si sip si<number> si2town", "si|sis|sub inspector|sub inspectors|subinspector|subinspectors", ""),
    "WSI": ("Woman Sub-Inspector", "wsi wsi<number>", "wsi|wsis|woman sub inspector|woman sub inspectors|women sub inspectors", "SI"),
    "PSI": ("Probationary Sub-Inspector", "psi<number>", "psi|psis|probationary sub inspector|probationary sub inspectors", "SI"),
    "RSI": ("Reserve Sub-Inspector", "rsi", "rsi|rsis|reserve sub inspector|reserve sub inspectors", ""),
    "ASI": ("Assistant Sub-Inspector", "asi<number>", "asi|asis|assistant sub inspector|assistant sub inspectors", ""),
    "WASI": ("Woman Assistant Sub-Inspector", "wasi<number>", "wasi|wasis|woman asi|woman asis", "ASI"),
    "CI": ("Circle Inspector", "ci cip", "ci|cis|circle inspector|circle inspectors", ""),
    "INSPR": ("Inspector", "insp inspctr inspector inspr inspr<number> inspradmin insprpcr sbi", "inspr|insprs|insp|inspector|inspectors", ""),
    "SHO": ("Station House Officer", "sho shorural shotrf", "sho|shos|station house officer|station house officers", ""),
    "DSP": ("Deputy Superintendent of Police", "dsp", "dsp|dsps|deputy superintendent of police|deputy superintendents of police", ""),
    "SDPO": ("Sub-Divisional Police Officer", "sdpo sdpombnr", "sdpo|sdpos|sub divisional police officer|sub divisional police officers", ""),
    "ACP": ("Assistant Commissioner of Police", "acp acpar", "acp|acps|assistant commissioner of police", ""),
    "ADCP": ("Additional DCP", "adcp addldcp", "adcp|adcps|addl dcp|additional dcp|additional deputy commissioner of police", ""),
    "DCP": ("Deputy Commissioner of Police", "dcp", "dcp|dcps|deputy commissioner of police", ""),
    "ADDLSP": ("Additional SP", "addlsp addsp", "addlsp|addl sp|additional sp|additional superintendent of police", ""),
    "SP": ("Superintendent of Police", "sp", "sp|sps|superintendent of police", ""),
    "ASP": ("Assistant Superintendent of Police", "asp", "asp|asps|assistant superintendent of police", ""),
    "CP": ("Commissioner of Police", "cp", "cp|commissioner of police", ""),
    "ADDLCP": ("Additional Commissioner of Police", "addlcp", "addlcp|addl cp|additional cp|additional commissioner of police", ""),
    "JCP": ("Joint Commissioner of Police", "jcp jtcp", "jcp|jtcp|jt cp|joint cp|joint commissioner of police", ""),
    "RI": ("Reserve Inspector", "ri riar rictc rihg", "ri|ris|reserve inspector|reserve inspectors", ""),
    "DI": ("Detective Inspector", "di", "di|dis|detective inspector|detective inspectors", ""),
    "HC": ("Head Constable", "hc<number>", "hc|hcs|head constable|head constables", ""),
    "WHC": ("Woman Head Constable", "whc<number>", "whc|whcs|woman head constable|woman head constables", "HC"),
    "PC": ("Police Constable", "pc<number>", "pc|pcs|police constable|police constables|constable|constables", ""),
    "WPC": ("Woman Police Constable", "wpc<number> pwpc<number>", "wpc|wpcs|woman police constable|woman police constables|woman constables", "PC"),
    "DGP": ("Director General of Police", "dgp", "dgp|director general of police", ""),
    "ADGP": ("Additional Director General of Police", "adgp adg", "adgp|adgps|additional director general of police", ""),
    "DG": ("Director General", "dg", "director general", ""),
    "IGP": ("Inspector General of Police", "igp", "igp|igps|inspector general of police", ""),
    "DIG": ("Deputy Inspector General", "dig", "dig|digs|deputy inspector general", ""),
    "COMDT": ("Commandant", "comdt cmt", "comdt|commandant|commandants", ""),
    "ADDLCOMDT": ("Additional Commandant", "addlcomdt", "addl commandant|additional commandant", ""),
    "ASSTCOMDT": ("Assistant Commandant", "", "assistant commandant|assistant commandants", ""),
    "AO": ("Administrative Officer", "ao ado", "ao|aos|administrative officer|administrative officers", ""),
    "CAO": ("Chief Administrative Officer", "cao cado", "cao|chief administrative officer", ""),
    "AAO": ("Assistant Accounts Officer", "aao", "aao|assistant accounts officer", ""),
    "JAO": ("Junior Accounts Officer", "jao", "jao|junior accounts officer", ""),
    "SUPDT": ("Superintendent (Ministerial)", "spdnt spdt supdt supta suptpay", "supdt|spdt|ministerial superintendent|superintendent ministerial", ""),
    "CC": ("Camp Clerk", "cc cc2addlsp cc2sp cc2tosp ccdcp cctosp spcc addlspcc", "camp clerk|camp clerks", ""),
    "MTO": ("Motor Transport Officer", "mto", "mto|motor transport officer", ""),
    "OSD": ("Officer on Special Duty", "osd", "osd|officer on special duty", ""),
    "PRO": ("Public Relations Officer", "pro", "public relations officer", ""),
    "DUTYOFFICER": ("Duty Officer", "do duty dutyofcr dutyofficer", "duty officer|duty officers", ""),
    "WRITER": ("Station Writer", "writer", "writer|writers|station writer|station writers", ""),
    "DOCTOR": ("Doctor / Medical Officer", "doctor", "doctor|doctors|medical officer|medical officers", ""),
}
DESIGNATION_ALIASES = {normalize(a) for _, _, aliases, _ in DESIGNATIONS.values() for a in aliases.split("|")}
LOCATION_SUFFIXES = (" Police Commissionerate", " City Police", " District", " Police", " Zone")

wb = openpyxl.load_workbook(XLSX, read_only=True, data_only=True)
decoded = [r for r in list(wb["Decoded Usernames"].iter_rows(values_only=True))[1:] if r and r[0]]
key = [r for r in list(wb["Abbreviation Key"].iter_rows(values_only=True))[1:] if r and r[0]]
review = defaultdict(list)

# 1. Designation label -> code ------------------------------------------------------------------
def label_key(text):
    text = re.sub(r"\s+No\.\s*\d+\s*$", "", text or "")
    text = re.sub(r"\s+with badge/general number", "", text)
    text = re.sub(r"\s*-\s*[IV]+\s*$", "", text)             # "Sub-Inspector (SI)-II", "Inspector-II"
    return normalize(text.replace("?", ""))


label_to_code, abbr_to_code = {}, {}
for code, (label, abbrs, _, _) in DESIGNATIONS.items():
    label_to_code[label_key(label)] = code
    for abbr in abbrs.split():
        abbr_to_code[abbr] = code
for abbr, full, category, scope, status in key:
    code = abbr_to_code.get(str(abbr).lower())
    if code and category in ("Designation / Post", "Personnel rank"):
        label_to_code.setdefault(label_key(full), code)


def designation_codes(text):
    """Codes for a decoded designation or rank value, plus family parents (WSI -> SI)."""
    code = label_to_code.get(label_key(text)) if text else None
    codes = []
    while code:
        codes.append(code)
        code = DESIGNATIONS[code][3]
    return codes

# 2. Locations -----------------------------------------------------------------------------------
district_key_rows = [r for r in key if r[2].startswith("District")]


def is_area(part):
    return part.endswith(("District", "Commissionerate", "Police", "Zone")) and not part.startswith("Multi Zone")


areas = set()
for value in [r[7] for r in decoded] + [r[6] for r in decoded] + [r[1] for r in district_key_rows]:
    for part in (value or "").split(","):
        part = clean(part)
        if part and is_area(part):
            areas.add(part)
DISTRICT_LEVEL = {a for a in areas if not a.endswith("Zone")}
ZONES = areas - DISTRICT_LEVEL


def resolve_area(part):
    """Maps a district/zone-column value ("Warangal", "Golconda") to an area name, else None."""
    if part in areas:
        return part
    if part + " Zone" in ZONES:
        return part + " Zone"
    containing = [a for a in DISTRICT_LEVEL if re.search(r"\b" + re.escape(part) + r"\b", a)]
    return containing[0] if len(containing) == 1 else None


def area_parts(value):
    found, others = [], []
    for raw in (value or "").split(","):
        part = clean(raw)
        if not part:
            continue
        area = resolve_area(part)
        (found.append((area, not uncertain(raw))) if area else others.append(part))
    return found, others


def home_district(found):
    for area, _ in reversed(found):
        if area in DISTRICT_LEVEL:
            return area
    return found[-1][0] if found else None


CONFIDENCE = {"Confident": "CONFIDENT", "Likely": "LIKELY", "Needs review": "NEEDS_REVIEW",
              "Personal name": "PERSONAL", "Test / system": "SYSTEM"}
stations = {}                       # location name -> (station name, home area)
station_homes = defaultdict(set)    # normalized station name -> home areas


def zone_or_area(name):
    """A station-column value that is itself an area ("Central Zone", "Golconda") resolves to that area."""
    return name if name in areas else name + " Zone" if name + " Zone" in ZONES else None


def add_station(name, home):
    loc = f"{name}, {home}"
    stations[loc] = (name, home)
    station_homes[normalize(name)].add(home)
    return loc


# Pass 1: every station named in the decoded rows or the key, with its home district/commissionerate.
for r in decoded:
    home = home_district(area_parts(r[7])[0] + area_parts(r[6])[0])
    for part in (r[5] or "").split(","):
        name = clean(part)
        if name and home and not zone_or_area(name):
            add_station(name, home)
for abbr, full, category, scope, status in key:
    if category == "Police Station / Place" and clean(full) and scope in DISTRICT_LEVEL and not zone_or_area(clean(full)):
        add_station(clean(full), scope)


def unique_station(name):
    homes = station_homes.get(normalize(name), set())
    return (f"{name}, {next(iter(homes))}", next(iter(homes))) if len(homes) == 1 else None


# Pass 2: profiles.
profiles = []
for member_id, username, desig, rank, wing, station, zone, district, full, confidence in decoded:
    conf = CONFIDENCE[confidence]
    found, others = area_parts(district)
    zone_found, zone_others = area_parts(zone)
    found += [z for z in zone_found if z[0] not in {a for a, _ in found}]
    still_unknown = []
    for value in others + zone_others:
        place = unique_station(value)          # "Maheshwaram", "LB Nagar": a known station, so a place
        if place:
            found += [(place[1], True), (place[0], True)]
        else:
            still_unknown.append(value)
    others = still_unknown
    home = home_district(found)
    for part in (station or "").split(","):
        name = clean(part)
        if not name:
            continue
        area = zone_or_area(name)
        if area:
            found.append((area, not uncertain(part)))
        elif home:
            found.append((add_station(name, home), not uncertain(part)))
        else:
            others.append(name)
    if others:
        review["values_kept_as_keywords"].append((member_id, username, "; ".join(others)))

    attrs = []
    if conf != "SYSTEM":
        codes = [] if conf == "PERSONAL" else list(dict.fromkeys(designation_codes(desig) + designation_codes(rank)))
        if conf != "PERSONAL" and not codes:
            review["designations_not_mapped"].append((member_id, username, desig or "", rank or ""))
        for code in codes:
            ok = conf == "CONFIDENT" or (conf == "LIKELY" and not uncertain(desig) and not uncertain(rank))
            attrs.append(("DESIGNATION", code, ok))
        for loc, part_ok in dict.fromkeys(found):
            fine_grained = loc in stations or loc in ZONES
            ok = part_ok and (conf == "CONFIDENT" or (conf == "LIKELY" and not fine_grained))
            attrs.append(("LOCATION", loc, ok))
    if conf in ("LIKELY", "NEEDS_REVIEW") or any(uncertain(v) for v in (desig, rank, wing, station, zone, district)):
        review["uncertain_rows"].append((member_id, username, confidence, desig or "", rank or "", wing or "",
                                         station or "", zone or "", district or ""))
    badge = re.search(r"No\.\s*(\d+)", rank or "")
    profiles.append(dict(member_id=member_id, username=username, confidence=conf, designation=desig, rank=rank,
                         badge=badge.group(1) if badge else None, wing=wing, station=station, zone=zone,
                         district=district, full=full, attrs=attrs))

station_code_homes = defaultdict(set)
for abbr, full, category, scope, status in key:
    if category == "Police Station / Place" and clean(full) and scope in DISTRICT_LEVEL:
        station_code_homes[normalize(str(abbr))].add(scope)

# Location aliases. District-level names and codes come first and win. A station name or code shared by
# stations of different areas is not an alias (it stays a plain keyword: "Nalgonda rural SIs" works).
loc_aliases = defaultdict(dict)    # location -> {normalized alias: [alias, verified, note]}
alias_owner = defaultdict(set)


def add_alias(location, alias, verified, note=None):
    n = normalize(alias)
    if not n or len(n) < 2 or n in QUERY_WORDS or n in DESIGNATION_ALIASES:
        return
    entry = loc_aliases[location].setdefault(n, [alias, False, note])
    entry[1] = entry[1] or verified
    alias_owner[n].add(location)


for area in sorted(areas):
    add_alias(area, area, True)
    for suffix in LOCATION_SUFFIXES:
        if area.endswith(suffix):
            short = area[: -len(suffix)]
            add_alias(area, short, True)
            if suffix == " Police Commissionerate":
                add_alias(area, short + " Commissionerate", True)
            break
for abbr, full, category, scope, status in district_key_rows:
    if status == "Not decoded":
        continue
    found, _ = area_parts(full)
    if found:
        # "gz" -> "Golconda Zone, Hyderabad City Police": the code names the most specific part.
        add_alias(found[0][0], str(abbr), status == "Confident" and not uncertain(full), "abbreviation key")
district_owner = {n: set(owners) for n, owners in alias_owner.items()}


def inside(home, owner):
    return home == owner or owner in DISTRICT_LEVEL and home.endswith(owner)


def station_alias_allowed(n, homes, home):
    """District-level meanings win. A station name/code that collides with an area alias is kept only when the
    area is a different place (mlg: Mulugu District vs Miryalaguda in Nalgonda), so the app can ask which one.
    A name/code shared by stations of several areas and no area ("Rural", "I Town") stays a plain keyword."""
    if n in district_owner:
        return not any(inside(home, owner) or owner in ZONES for owner in district_owner[n])
    return len(homes) == 1


for loc, (name, home) in sorted(stations.items()):
    n = normalize(name)
    if station_alias_allowed(n, station_homes[n], home):
        add_alias(loc, name, True)
for abbr, full, category, scope, status in key:
    if category != "Police Station / Place" or status == "Not decoded" or not clean(full) or scope not in DISTRICT_LEVEL:
        continue
    n = normalize(str(abbr))
    loc = f"{clean(full)}, {scope}"
    if loc in stations and station_alias_allowed(n, station_code_homes[n], scope):
        add_alias(loc, str(abbr), status == "Confident" and not uncertain(full), "abbreviation key")
# Location codes learned from the usernames themselves (si-qbp-cyb, hc-nzbtn1, ...). The abbreviation key misses
# many codes people actually use. A username token becomes an alias of a location when:
#   * it is not a query word, rank, wing/section code or an alias some other location already owns;
#   * its letters are an in-order abbreviation of that location's name, starting with the same letter
#     (qbp -> Quthbullapur, nzbtn -> Nizamabad Town, blnr -> Balanagar) - this keeps unit words such as
#     "cluesteam" or "controlroom" from becoming places just because every such account sits in one city;
#   * at least 75% of the decoded members whose username carries the token are in that location
#     (2+ members for two-letter codes). The most specific qualifying location wins (station > zone > district).
# It is VERIFIED only when every supporting member is decoded with a verified location; otherwise UNVERIFIED,
# which can only give SUGGESTED matches.
NON_LOCATION_CODES = {normalize(str(a)).replace(" ", "") for a, _, category, _, _ in key
                      if category in ("Wing / Section", "Designation / Post", "Personnel rank")}
DESIGNATION_CODE = re.compile(r"^(?:" + "|".join(sorted({re.escape(a.replace("<number>", "")) for a in abbr_to_code},
                                                         key=len, reverse=True)) + r")\d*$")


def place_letters(location):
    name = location.split(",")[0]
    for suffix in LOCATION_SUFFIXES:          # " PS" stays: bhps -> "Banjara Hills PS"
        if name.endswith(suffix):
            name = name[: -len(suffix)]
            break
    return re.sub(r"[^a-z]", "", name.lower())


def abbreviates(code, location):
    letters, name = re.sub(r"[^a-z]", "", code), place_letters(location)
    if len(letters) < 2 or not name or letters[0] != name[0]:
        return False
    rest = iter(name)
    return all(ch in rest for ch in letters)


token_members = defaultdict(set)                       # token -> member ids
token_locations = defaultdict(lambda: defaultdict(list))  # token -> location -> [verified, ...]
for p in profiles:
    if p["confidence"] in ("SYSTEM", "PERSONAL"):
        continue
    for token in set(re.split(r"[^a-z0-9]+", re.sub(r"@.*", "", p["username"].lower()))):
        if not token or token.isdigit():
            continue
        token_members[token].add(p["member_id"])
        for kind, loc, ok in p["attrs"]:
            if kind == "LOCATION":
                token_locations[token][loc].append(ok)

specificity = lambda loc: 0 if loc in stations else 1 if loc in ZONES else 2
for token in sorted(token_members):
    n = normalize(token)
    if (n in alias_owner or n in QUERY_WORDS or n in DESIGNATION_ALIASES or n in NON_LOCATION_CODES
            or DESIGNATION_CODE.match(n)):
        continue
    members = len(token_members[token])
    candidates = [(specificity(loc), loc, oks) for loc, oks in token_locations[token].items()
                  if len(oks) / members >= 0.75 and abbreviates(token, loc) and (len(n) > 2 or len(oks) >= 2)]
    if not candidates:
        continue
    _, loc, oks = min(candidates)
    add_alias(loc, token, all(oks), f"username code ({len(oks)} of {members} accounts)")
    review["username_location_codes"].append((token, loc, len(oks), members, "VERIFIED" if all(oks) else "UNVERIFIED"))

for n, owners in sorted(alias_owner.items()):
    if len(owners) > 1:
        review["ambiguous_location_aliases"].append((n, " | ".join(sorted(owners))))

# Old JSON dictionary codes contradicted by the decoded data.
for name, aliases in json.loads(INDEX_JSON.read_text())["verified_or_strongly_supported_aliases"].items():
    first = normalize(name).split()[0]
    for alias in aliases:
        owners = alias_owner.get(normalize(alias), set())
        if owners and not any(first in normalize(o) for o in owners):
            review["json_dictionary_conflicts"].append((alias, name, " | ".join(sorted(owners))))

# 3. Units: wing abbreviations that share one full form (acc, account -> one unit) ----------------
groups = defaultdict(set)
for abbr, full, category, scope, status in key:
    if category in ("Wing / Section", "Designation / Post") and status == "Confident" and clean(full):
        n = normalize(str(abbr)).replace(" ", "")
        if n and n not in QUERY_WORDS and n not in DESIGNATION_ALIASES and n not in alias_owner:
            groups[normalize(re.sub(r"\(.*?\)", "", clean(full)))].add(n)
units, unit_owner = {}, {}
for full, forms in sorted(groups.items()):
    if len(forms) < 2 or not full:
        continue
    canonical = "traffic" if "traffic" in forms else "lo" if "lo" in forms else min(forms, key=lambda f: (len(f), f))
    forms = {f for f in forms if unit_owner.setdefault(f, canonical) == canonical}
    units.setdefault(canonical, [full, set()])[1].update(forms | {canonical})

# 4. SQL ------------------------------------------------------------------------------------------
location_type = {a: "ZONE" if a in ZONES else "COMMISSIONERATE" if ("Commissionerate" in a or a.endswith("Police"))
                 else "DISTRICT" for a in areas}
location_type.update({loc: "OTHER" for loc in stations})
area_parent = {}
for p in profiles:
    found = [loc for kind, loc, _ in p["attrs"] if kind == "LOCATION" and loc in areas]
    for zone in [a for a in found if a in ZONES]:
        for d in [a for a in found if a in DISTRICT_LEVEL]:
            area_parent.setdefault(zone, d)
parent_of = {loc: home for loc, (_, home) in stations.items()} | area_parent

out = []
w = out.append
w(f"""-- Generated by scripts/decoded_import/generate.py from {XLSX.name}. Do not edit by hand: change the workbook
-- (or the generator) and regenerate. Run recipient_catalog.sql first. MySQL 8.0.19+. Safe to re-run.
--
-- Loads list @list_id with:
--   * designations for every rank and post in the workbook (PC, HC, ASI, WSI ... added to the base set);
--   * every location: the decoded districts, commissionerates, zones and stations, with their codes;
--   * unit spellings for wings that have several abbreviations;
--   * one recipient_member_profile per decoded username, with its designation/location attributes.
-- A value marked "?" in the workbook is stored with verified = 0 / UNVERIFIED, which can only produce
-- SUGGESTED matches; "not decoded" values are left out. docs/decoded_review/ lists those rows for review.
-- Counts: {len(profiles)} profiles, {sum(len(p['attrs']) for p in profiles)} attributes, {len(location_type)} locations,
--         {sum(len(v) for v in loc_aliases.values())} location aliases, {len(DESIGNATIONS)} designations, {len(units)} units.

SET NAMES utf8mb4;
SET @list_id = 173;

START TRANSACTION;

-- Designations ----------------------------------------------------------------------------------
""")
for code, (label, _, aliases, _) in DESIGNATIONS.items():
    w(f"INSERT INTO recipient_designation (list_id, code, label) VALUES (@list_id, {q(code)}, {q(label)}) "
      f"AS new ON DUPLICATE KEY UPDATE label = new.label, active = 1;")
for code, (_, _, _, parent) in DESIGNATIONS.items():
    w(f"UPDATE recipient_designation SET parent_code = {q(parent or None)} WHERE list_id = @list_id AND code = {q(code)};")
for code, (label, _, aliases, _) in DESIGNATIONS.items():
    values = ", ".join(f"(@list_id, {q(code)}, {q(a)}, {q(normalize(a))})" for a in dict.fromkeys(aliases.split("|")))
    w(f"INSERT INTO recipient_designation_alias (list_id, designation_code, alias, normalized_alias) VALUES {values} "
      f"AS new ON DUPLICATE KEY UPDATE designation_code = new.designation_code, alias = new.alias, active = 1;")

w("""
-- Locations: districts, commissionerates, zones and stations, with the codes from the abbreviation key ----------
""")
names = sorted(location_type)
for i in range(0, len(names), 200):
    values = ", ".join(f"(@list_id, {q(n)}, {q(location_type[n])})" for n in names[i:i + 200])
    w(f"INSERT INTO recipient_location (list_id, name, location_type) VALUES {values} "
      f"AS new ON DUPLICATE KEY UPDATE location_type = new.location_type, active = 1;")
w("""
CREATE TEMPORARY TABLE tmp_location_alias (id INT AUTO_INCREMENT PRIMARY KEY, location_name VARCHAR(150), alias VARCHAR(150), normalized_alias VARCHAR(150),
  verification VARCHAR(10), notes VARCHAR(255)) DEFAULT CHARSET = utf8mb4;""")
alias_values = [(loc, alias, n, "VERIFIED" if ok else "UNVERIFIED", note)
                for loc in names for n, (alias, ok, note) in sorted(loc_aliases[loc].items())]
for i in range(0, len(alias_values), 300):
    w("INSERT INTO tmp_location_alias (location_name, alias, normalized_alias, verification, notes) VALUES " + ", ".join(
        "(" + ", ".join(q(v) for v in row) + ")" for row in alias_values[i:i + 300]) + ";")
w("""INSERT INTO recipient_location_alias (list_id, location_id, alias, normalized_alias, verification, source, notes)
SELECT * FROM (SELECT l.list_id, l.id AS location_id, t.alias, t.normalized_alias, t.verification,
                      'decoded_xlsx' AS source, t.notes
                 FROM tmp_location_alias t JOIN recipient_location l ON l.list_id = @list_id AND l.name = t.location_name) AS new
ON DUPLICATE KEY UPDATE verification = new.verification, source = new.source, notes = new.notes, active = 1;
DROP TEMPORARY TABLE tmp_location_alias;
""")
w("-- Parent links are informational (zone -> commissionerate, station -> district); the app does not expand them.")
w("CREATE TEMPORARY TABLE tmp_location_parent (id INT AUTO_INCREMENT PRIMARY KEY, child VARCHAR(150), parent VARCHAR(150)) DEFAULT CHARSET = utf8mb4;")
pairs = sorted(parent_of.items())
for i in range(0, len(pairs), 300):
    w("INSERT INTO tmp_location_parent (child, parent) VALUES " + ", ".join(f"({q(c)}, {q(p)})" for c, p in pairs[i:i + 300]) + ";")
w("""UPDATE recipient_location child
  JOIN tmp_location_parent t ON t.child = child.name
  JOIN recipient_location parent ON parent.list_id = child.list_id AND parent.name = t.parent
   SET child.parent_location_id = parent.id
 WHERE child.list_id = @list_id;
DROP TEMPORARY TABLE tmp_location_parent;

-- Units -----------------------------------------------------------------------------------------""")
for canonical, (full, forms) in sorted(units.items()):
    w(f"INSERT INTO recipient_unit (list_id, canonical, label) VALUES (@list_id, {q(canonical)}, {q(full.title())}) "
      f"AS new ON DUPLICATE KEY UPDATE label = new.label, active = 1;")
    values = ", ".join(f"(@list_id, {q(canonical)}, {q(f)}, {q(f)})" for f in sorted(forms))
    w(f"INSERT INTO recipient_unit_alias (list_id, unit_canonical, alias, normalized_alias) VALUES {values} "
      f"AS new ON DUPLICATE KEY UPDATE unit_canonical = new.unit_canonical, active = 1;")

w("""
-- Member profiles -------------------------------------------------------------------------------
-- Decoded profiles are replaced as a whole; profiles entered by hand (source <> 'decoded_xlsx') are kept.
DELETE FROM recipient_member_profile WHERE list_id = @list_id AND source = 'decoded_xlsx';""")
cols = "(list_id, member_id, username, confidence, designation_text, rank_text, badge_no, wing_text, station_text, zone_text, district_text, full_expansion, source)"
for i in range(0, len(profiles), 250):
    rows = []
    for p in profiles[i:i + 250]:
        rows.append("(@list_id, " + ", ".join(q(v) for v in (
            p["member_id"], p["username"], p["confidence"], p["designation"], p["rank"], p["badge"], p["wing"],
            p["station"], p["zone"], p["district"], p["full"])) + ", 'decoded_xlsx')")
    w(f"INSERT INTO recipient_member_profile {cols} VALUES " + ", ".join(rows) + ";")
w("""
CREATE TEMPORARY TABLE tmp_member_attribute (id INT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT, kind VARCHAR(20), designation_code VARCHAR(20),
  location_name VARCHAR(150), verified TINYINT) DEFAULT CHARSET = utf8mb4;""")
attr_rows = [(p["member_id"], kind, value if kind == "DESIGNATION" else None, value if kind == "LOCATION" else None, ok)
             for p in profiles for kind, value, ok in p["attrs"]]
for i in range(0, len(attr_rows), 400):
    w("INSERT INTO tmp_member_attribute (member_id, kind, designation_code, location_name, verified) VALUES " + ", ".join(
        "(" + ", ".join(q(v) for v in row) + ")" for row in attr_rows[i:i + 400]) + ";")
w("""INSERT INTO recipient_member_attribute (list_id, member_id, kind, designation_code, verified)
SELECT @list_id, t.member_id, 'DESIGNATION', t.designation_code, t.verified
  FROM tmp_member_attribute t WHERE t.kind = 'DESIGNATION';
INSERT INTO recipient_member_attribute (list_id, member_id, kind, location_id, verified)
SELECT @list_id, t.member_id, 'LOCATION', l.id, t.verified
  FROM tmp_member_attribute t JOIN recipient_location l ON l.list_id = @list_id AND l.name = t.location_name
 WHERE t.kind = 'LOCATION';
DROP TEMPORARY TABLE tmp_member_attribute;

COMMIT;""")
OUT_SQL.parent.mkdir(parents=True, exist_ok=True)
OUT_SQL.write_text("\n".join(out) + "\n")

# 5. Review files ----------------------------------------------------------------------------------
HEADERS = {
    "uncertain_rows": ["member_id", "username", "confidence", "designation", "rank", "wing", "station", "zone", "district"],
    "designations_not_mapped": ["member_id", "username", "designation", "rank"],
    "values_kept_as_keywords": ["member_id", "username", "values"],
    "ambiguous_location_aliases": ["alias", "locations"],
    "json_dictionary_conflicts": ["alias", "old_json_meaning", "decoded_meaning"],
    "username_location_codes": ["code", "location", "accounts_in_location", "accounts_with_code", "verification"],
}
REVIEW_DIR.mkdir(parents=True, exist_ok=True)
for name, header in HEADERS.items():
    with open(REVIEW_DIR / f"{name}.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(header)
        writer.writerows(review[name])
print(f"profiles={len(profiles)} attributes={len(attr_rows)} "
      f"verified_attributes={sum(1 for r in attr_rows if r[4])} locations={len(location_type)} "
      f"(areas={len(areas)} stations={len(stations)}) location_aliases={len(alias_values)} units={len(units)}")
print("review: " + ", ".join(f"{k}={len(review[k])}" for k in HEADERS))
