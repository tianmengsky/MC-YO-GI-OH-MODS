"""Read-only independent checks for the completed pack inventory.

Run after the collector finishes; writes only the report beside this script.
Python standard library, no network calls and no mutations of source data.
"""
import csv
import gzip
import hashlib
import json
import re
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
PLAN = Path(__file__).resolve().parent
ROOT = PLAN.parent / "source-data" / "pack-inventory"
db = ROOT / "inventory.sqlite"
connection = sqlite3.connect(db.as_uri() + "?mode=ro", uri=True)
connection.row_factory = sqlite3.Row


def scalar(query):
    return connection.execute(query).fetchone()[0]


def rows(query):
    return [dict(row) for row in connection.execute(query)]


counts = {
    "products": scalar("SELECT COUNT(*) FROM products"),
    "cards": scalar("SELECT COUNT(*) FROM cards"),
    "product_cards": scalar("SELECT COUNT(*) FROM product_cards"),
}
checks = {
    "sqlite_quick_check": scalar("PRAGMA quick_check") == "ok",
    "unique_product_keys": scalar(
        "SELECT COUNT(*)-COUNT(DISTINCT product_id) FROM products") == 0,
    "unique_card_keys": scalar(
        "SELECT COUNT(*)-COUNT(DISTINCT konami_cid) FROM cards") == 0,
    "unique_product_row_ordinals": scalar(
        "SELECT COUNT(*) FROM (SELECT product_id,row_ordinal FROM product_cards "
        "GROUP BY product_id,row_ordinal HAVING COUNT(*)>1)") == 0,
    "no_orphan_product_memberships": scalar(
        "SELECT COUNT(*) FROM product_cards m LEFT JOIN products p "
        "ON p.product_id=m.product_id WHERE p.product_id IS NULL") == 0,
    "no_orphan_card_memberships": scalar(
        "SELECT COUNT(*) FROM product_cards m LEFT JOIN cards c "
        "ON c.konami_cid=m.konami_cid WHERE c.konami_cid IS NULL") == 0,
    "all_products_complete": scalar(
        "SELECT COUNT(*) FROM products WHERE status!='complete' OR status IS NULL") == 0,
    "all_product_counts_verified": scalar(
        "SELECT COUNT(*) FROM products WHERE expected_card_rows IS NULL "
        "OR expected_card_rows != parsed_card_rows OR parsed_card_rows<=0") == 0,
    "all_membership_counts_match_products": scalar(
        "SELECT COUNT(*) FROM products p WHERE parsed_card_rows!=(SELECT COUNT(*) "
        "FROM product_cards m WHERE m.product_id=p.product_id)") == 0,
    "all_distinct_counts_match_products": scalar(
        "SELECT COUNT(*) FROM products p WHERE distinct_cids!=(SELECT COUNT(DISTINCT konami_cid) "
        "FROM product_cards m WHERE m.product_id=p.product_id)") == 0,
    "no_exact_release_after_cutoff": scalar(
        "SELECT COUNT(*) FROM products WHERE date_quality='exact_index_date' "
        "AND (release_date>'2017-03-24' OR release_date IS NULL)") == 0,
    "no_sentinel_as_exact_release": scalar(
        "SELECT COUNT(*) FROM products WHERE date_quality!='exact_index_date' "
        "AND release_date IS NOT NULL") == 0,
    "year_only_scope_has_evidence": scalar(
        "SELECT COUNT(*) FROM products WHERE date_quality='year_only' "
        "AND (index_year IS NULL OR index_year>2016)") == 0,
    "official_product_sources": scalar(
        "SELECT COUNT(*) FROM products WHERE source_url NOT LIKE "
        "'https://www.db.yugioh-card.com/yugiohdb/card_search.action?%'") == 0,
}
coverage = json.loads((ROOT / "coverage.json").read_text(encoding="utf-8"))
checks["coverage_total_matches_database"] = (
    coverage["product_count"] == counts["products"]
    and coverage["membership_rows"] == counts["product_cards"]
    and coverage["distinct_konami_cids"] == counts["cards"]
)

csv_counts = {}
for table in ["products", "cards", "product_cards"]:
    with (ROOT / (table + ".csv")).open(encoding="utf-8-sig", newline="") as stream:
        csv_counts[table] = sum(1 for _ in csv.DictReader(stream))
checks["csv_exports_match_database"] = csv_counts == counts

locales = rows(
    "SELECT locale,COUNT(*) products,SUM(status='complete') complete,"
    "SUM(date_quality!='exact_index_date') uncertain_exact_dates "
    "FROM products GROUP BY locale ORDER BY locale")
checks["coverage_locales_match_database"] = all(
    coverage["locales"][row["locale"]]["products"] == row["products"]
    and coverage["locales"][row["locale"]]["complete"] == row["complete"]
    for row in locales
)

samples = []
for locale in [row["locale"] for row in locales]:
    sample = connection.execute(
        "SELECT * FROM products WHERE locale=? ORDER BY product_id LIMIT 1",
        (locale,),
    ).fetchone()
    samples.append(dict(sample))
for product_id in ["ja:1119003", "ja:2201048", "en:11101000"]:
    sample = connection.execute(
        "SELECT * FROM products WHERE product_id=?", (product_id,),
    ).fetchone()
    if sample and sample["product_id"] not in {s["product_id"] for s in samples}:
        samples.append(dict(sample))

cache_checks = []
for sample in samples:
    key = "product_" + sample["product_id"].replace(":", "_")
    raw = gzip.decompress((ROOT / "cache" / (key + ".html.gz")).read_bytes())
    metadata = json.loads((ROOT / "cache" / (key + ".meta.json")).read_text("utf-8"))
    html = raw.decode("utf-8")
    official_cids = set(map(int, re.findall(
        r'class="link_value"[^>]+value="[^"]*[?&]cid=(\d+)', html)))
    stored_cids = {
        r[0] for r in connection.execute(
            "SELECT konami_cid FROM product_cards WHERE product_id=?",
            (sample["product_id"],))
    }
    cache_checks.append({
        "product_id": sample["product_id"],
        "name": sample["name"],
        "expected_rows": sample["expected_card_rows"],
        "sha256_matches_cache_metadata": hashlib.sha256(raw).hexdigest() == metadata["sha256"],
        "source_url_matches_metadata": sample["source_url"] == metadata["url"],
        "html_cids_match_database": official_cids == stored_cids,
        "html_unique_cids": len(official_cids),
    })
checks["sampled_cache_hashes_and_memberships_match"] = all(
    s["sha256_matches_cache_metadata"]
    and s["source_url_matches_metadata"]
    and s["html_cids_match_database"] for s in cache_checks
)

mapping_tables = {r[0] for r in connection.execute(
    "SELECT name FROM sqlite_master WHERE type='table'")}
mapping_review = {"status": "mapping_tables_not_present"}
if {"card_passcode_mapping", "engine_cards", "card_usage_flags"} <= mapping_tables:
    checks["mapping_covers_official_cards_once"] = (
        scalar("SELECT COUNT(*) FROM card_passcode_mapping") == counts["cards"]
        and scalar("SELECT COUNT(*)-COUNT(DISTINCT konami_cid) FROM card_passcode_mapping") == 0
        and scalar("SELECT COUNT(*) FROM cards c LEFT JOIN card_passcode_mapping m "
                   "ON m.konami_cid=c.konami_cid WHERE m.konami_cid IS NULL") == 0
    )
    checks["verified_mappings_have_engine_data"] = scalar(
        "SELECT COUNT(*) FROM card_passcode_mapping m LEFT JOIN engine_cards e "
        "ON e.engine_passcode=m.engine_passcode WHERE m.status='verified_two_sources' "
        "AND (m.engine_passcode IS NULL OR e.engine_passcode IS NULL)") == 0
    checks["no_link_monsters_in_engine_pool"] = scalar(
        "SELECT COUNT(*) FROM engine_cards WHERE (CAST(type AS INTEGER)&67108864)!=0") == 0
    checks["usage_flags_cover_all_cids"] = scalar(
        "SELECT COUNT(*) FROM cards c LEFT JOIN card_usage_flags u "
        "ON u.konami_cid=c.konami_cid WHERE u.konami_cid IS NULL") == 0
    verified_codes = {
        int(r[0]) for r in connection.execute(
            "SELECT engine_passcode FROM card_passcode_mapping WHERE status='verified_two_sources'")
    }
    jp_codes = {
        int(r[0]) for r in connection.execute(
            "SELECT DISTINCT m.engine_passcode FROM card_passcode_mapping m JOIN product_cards pc "
            "ON pc.konami_cid=m.konami_cid WHERE m.status='verified_two_sources' AND pc.locale='ja'")
    }
    unresolved_cids = {
        int(r[0]) for r in connection.execute(
            "SELECT konami_cid FROM card_passcode_mapping WHERE status!='verified_two_sources'")
    }
    restricted_cids = {
        int(r[0]) for r in connection.execute(
            "SELECT konami_cid FROM card_usage_flags WHERE usage_notice_status='explicit_official_no_duel_notice'")
    }
    checks["unresolved_cids_equal_explicit_restricted_cids"] = unresolved_cids == restricted_cids
    mdroot = Path(r"F:\MyCardLibrary\mdpro3")
    local = sqlite3.connect((mdroot / "Data/locales/zh-CN/cards.cdb").as_uri()+"?mode=ro", uri=True)
    local_data = {int(r[0]): tuple(r) for r in local.execute(
        "SELECT id,ot,alias,setcode,type,atk,def,level,race,attribute,category FROM datas")}
    pack = sqlite3.connect((mdroot / "Data/pack/pack.db").as_uri()+"?mode=ro", uri=True)
    original_md_codes = {
        int(r[0]) for r in pack.execute("SELECT id FROM pack WHERE date <= '2017-03-24'")
        if int(r[0]) in local_data and not (local_data[int(r[0])][4] & 67108864)
    }
    engine_mismatches = []
    for row in connection.execute("SELECT * FROM engine_cards"):
        code = int(row["engine_passcode"])
        fields = ["engine_passcode", "ot", "alias", "setcode", "type", "atk", "def",
                  "level_packed", "race", "attribute", "category"]
        actual = tuple(int(row[field]) for field in fields)
        if actual != local_data.get(code):
            engine_mismatches.append(code)
    checks["engine_rule_fields_match_local_database"] = not engine_mismatches
    checks["official_jp_verified_pool_matches_existing_md_pool"] = jp_codes == original_md_codes
    mapping_review = {
        "status": "reviewed_available_tables",
        "verified_engine_codes": len(verified_codes),
        "jp_verified_engine_codes": len(jp_codes),
        "existing_md_pool": len(original_md_codes),
        "unresolved_cids": len(unresolved_cids),
        "explicit_restricted_cids": len(restricted_cids),
        "global_additions_vs_md": sorted(verified_codes-original_md_codes),
        "md_only_vs_official_jp": sorted(original_md_codes-jp_codes),
        "engine_rule_field_mismatches": engine_mismatches,
        "qualification": "Mappings use corroborated third-party double IDs, not an official KONAMI passcode API.",
    }
    local.close()
    pack.close()
else:
    checks["final_mapping_and_usage_tables_present"] = False

report = {
    "audited_at": datetime.now(timezone.utc).isoformat(),
    "scope": "Read-only consistency audit of the delivered source corpus; not proof that live official catalogs list all historical products.",
    "counts": counts,
    "checks": checks,
    "all_checks_pass": all(checks.values()),
    "locales": locales,
    "sampled_cached_pages": cache_checks,
    "unknown_exact_release_dates": scalar(
        "SELECT COUNT(*) FROM products WHERE date_quality!='exact_index_date'"),
    "missing_collection_numbers": scalar(
        "SELECT COUNT(*) FROM product_cards WHERE collection_number IS NULL"),
    "unknown_physical_quantities": scalar(
        "SELECT COUNT(*) FROM product_cards WHERE physical_quantity IS NULL"),
    "mapping_audit": mapping_review,
}
connection.close()
dest = PLAN / "source-data-independent-audit.json"
dest.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"report": str(dest), "counts": counts, "all_checks_pass": report["all_checks_pass"],
                  "failed_checks": [key for key, value in checks.items() if not value]},
                 ensure_ascii=False, indent=2))
sys.exit(0 if report["all_checks_pass"] else 1)
