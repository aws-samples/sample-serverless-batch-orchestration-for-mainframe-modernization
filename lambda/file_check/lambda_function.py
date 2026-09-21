"""
File-check Lambda — replaces Control-M file-watcher / IN-condition logic.

For a given jobName it verifies, in S3:
  1. Existence  — at least one object under each configured prefix matches a regex.
  2. Freshness  — for prefixes listed in `folders_to_check_age`, the newest match
                  is within MAX_AGE_DAYS calendar days (UTC). Static reference files
                  are NOT age-checked (they change infrequently).

testRunPrefix isolation: when the event carries a non-empty `testRunPrefix`, it is
prepended to every prefix before any S3 call, so a regression run reads only from
its own namespace and never touches production paths.

Return contract consumed by the Step Functions Choice state:
  { "allFilesPresent": bool, "errorType": str|None, "fileResults": [...], ... }
"""

import os
import re
import logging
from datetime import datetime, timezone

import boto3

logger = logging.getLogger()
logger.setLevel(logging.INFO)

S3 = boto3.client("s3")
BUCKET = os.environ["S3_BUCKET"]
MAX_AGE_DAYS = int(os.environ.get("MAX_AGE_DAYS", "2"))

# ---------------------------------------------------------------------------
# JOB_CONFIG — the single source of truth for every pre-condition. ADD NEW JOBS
# HERE ONLY. Two keys per job:
#   folders_to_check_for_regex : {prefix: [regex, ...]}  — what must exist
#   folders_to_check_age       : {prefix, ...}           — subset also age-checked
# In production these prefixes are typically env-var driven; kept literal here so
# the sample is self-contained. Regex is matched against the object *basename*.
# ---------------------------------------------------------------------------
JOB_CONFIG = {
    "Check_JOB1_Input_Files": {
        "folders_to_check_for_regex": {
            "inbound/transactions/": [r"^txns\.\d{8}\.csv$"],   # operational — age-checked
        },
        "folders_to_check_age": {"inbound/transactions/"},
    },
    "Check_JOB2A_Input_Files": {
        "folders_to_check_for_regex": {
            "processing/JOB1/output/": [r"^normalized\.csv$"],  # intermediate — presence only
        },
        "folders_to_check_age": set(),
    },
    "Check_JOB2B_Input_Files": {
        "folders_to_check_for_regex": {
            "processing/JOB1/output/": [r"^normalized\.csv$"],
        },
        "folders_to_check_age": set(),
    },
    "Check_JOB3_Input_Files": {
        "folders_to_check_for_regex": {
            "processing/JOB2A/output/": [r"^fees\.csv$"],
            "processing/JOB2B/output/": [r"^rebates\.csv$"],
        },
        "folders_to_check_age": set(),
    },
}


def _apply_prefix(folder: str, test_run_prefix: str) -> str:
    """Prepend the per-run isolation prefix, if any. Production => folder unchanged."""
    if test_run_prefix:
        return f"{test_run_prefix.rstrip('/')}/{folder}"
    return folder


def _list_objects(prefix: str):
    """Paginate list_objects_v2; skip 'directory' placeholder keys."""
    objects = []
    paginator = S3.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=BUCKET, Prefix=prefix):
        for obj in page.get("Contents", []):
            if not obj["Key"].endswith("/"):
                objects.append(obj)
    return objects


def _days_old_utc(last_modified: datetime) -> int:
    """Calendar-day difference in UTC (matches the reference implementation)."""
    today = datetime.now(timezone.utc).date()
    return (today - last_modified.astimezone(timezone.utc).date()).days


def lambda_handler(event, context):
    job_name = event.get("jobName")
    test_run_prefix = (event.get("testRunPrefix") or "").strip()

    cfg = JOB_CONFIG.get(job_name)
    if cfg is None:
        return {
            "allFilesPresent": False,
            "errorType": "INPUT_FILES_INVALID",
            "message": f"Unknown jobName: {job_name}",
            "jobName": job_name,
            "fileResults": [],
        }

    age_folders = cfg.get("folders_to_check_age", set())
    file_results = []
    missing = 0

    for folder, patterns in cfg["folders_to_check_for_regex"].items():
        prefix = _apply_prefix(folder, test_run_prefix)
        objects = _list_objects(prefix)
        age_required = folder in age_folders

        for pattern in patterns:
            matches = [o for o in objects if re.match(pattern, o["Key"].rsplit("/", 1)[-1])]

            if not matches:
                missing += 1
                file_results.append({
                    "prefix": prefix, "pattern": pattern, "present": False,
                    "ageCheckApplied": age_required, "ageStatus": "FILE_NOT_FOUND",
                    "errorType": "INPUT_FILES_MISSING",
                })
                continue

            if not age_required:
                file_results.append({
                    "prefix": prefix, "pattern": pattern, "present": True,
                    "ageCheckApplied": False, "ageStatus": "AGE_CHECK_NOT_REQUIRED",
                    "errorType": None,
                })
                continue

            newest = max(matches, key=lambda o: o["LastModified"])
            days_old = _days_old_utc(newest["LastModified"])
            if days_old <= MAX_AGE_DAYS:
                file_results.append({
                    "prefix": prefix, "pattern": pattern, "present": True,
                    "ageCheckApplied": True, "numberOfDaysOld": days_old,
                    "ageStatus": "WITHIN_ALLOWED_AGE", "maxAgeDays": MAX_AGE_DAYS,
                    "errorType": None,
                })
            else:
                missing += 1
                file_results.append({
                    "prefix": prefix, "pattern": pattern, "present": False,
                    "ageCheckApplied": True, "numberOfDaysOld": days_old,
                    "ageStatus": f"OLDER_THAN_{MAX_AGE_DAYS}_DAYS", "maxAgeDays": MAX_AGE_DAYS,
                    "errorType": "INPUT_FILES_TOO_OLD",
                })

    all_present = missing == 0
    # First non-null errorType wins for the aggregate signal.
    agg_error = next((r["errorType"] for r in file_results if r["errorType"]), None)

    result = {
        "allFilesPresent": all_present,
        "errorType": None if all_present else agg_error,
        "jobName": job_name,
        "bucket": BUCKET,
        "prefix": test_run_prefix or "(production)",
        "filesChecked": len(file_results),
        "missingCount": missing,
        "fileResults": file_results,
        "message": "All required input files present."
        if all_present else f"{missing} required input file check(s) failed.",
    }
    logger.info("job=%s allFilesPresent=%s missing=%d", job_name, all_present, missing)
    return result
