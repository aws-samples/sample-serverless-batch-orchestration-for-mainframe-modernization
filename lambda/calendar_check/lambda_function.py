"""
Calendar-check Lambda — replaces the Control-M business-day calendar.

Returns { "isBusinessDay": bool } for the execution date. The Step Functions
EvaluateBusinessDay Choice routes non-processing days to a clean Succeed state
(no failure alarm, no ops page).

Keep the rule set OUTSIDE the batch programs so holidays can change without a
redeploy. Two common backings:
  1. DynamoDB calendar table  — non-processing dates stored as YYYY-MM-DD PKs
     (used here; set CALENDAR_TABLE).
  2. Precomputed EventBridge Scheduler expression — omit this Lambda entirely
     when the schedule is stable.

Weekends are treated as non-processing days by default; adjust for your workload.

Timezone: $$.Execution.StartTime is always UTC. When the EventBridge schedule runs
in a local zone (ScheduleTimezone != UTC), the intended business date is the local
calendar date, not the UTC one — e.g. a 02:00 Australia/Sydney trigger arrives as
16:00 UTC the previous day. SCHEDULE_TIMEZONE (set from the same value as the
schedule) is used to resolve StartTime to the local date before the weekend/holiday
test, so the correct day is evaluated. Defaults to UTC.
"""

import os
import logging
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

import boto3

logger = logging.getLogger()
logger.setLevel(logging.INFO)

TABLE_NAME = os.environ.get("CALENDAR_TABLE")  # optional
_dynamodb = boto3.resource("dynamodb") if TABLE_NAME else None

# Must match the EventBridge ScheduleTimezone so the local business date is used.
SCHEDULE_TZ = ZoneInfo(os.environ.get("SCHEDULE_TIMEZONE", "UTC"))


def _is_holiday(date_str: str) -> bool:
    """True if date_str (YYYY-MM-DD) is a stored non-processing date."""
    if not _dynamodb:
        return False
    table = _dynamodb.Table(TABLE_NAME)
    resp = table.get_item(Key={"date": date_str})
    return "Item" in resp


def lambda_handler(event, context):
    # Step Functions passes $$.Execution.StartTime (ISO-8601 UTC, e.g. 2026-09-10T02:00:00Z).
    raw = event.get("date") or datetime.now(timezone.utc).isoformat()
    dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    # Resolve to the schedule's local calendar date before testing weekend/holiday.
    dt = dt.astimezone(SCHEDULE_TZ)
    date_str = dt.strftime("%Y-%m-%d")

    is_weekend = dt.weekday() >= 5           # 5 = Sat, 6 = Sun
    is_holiday = _is_holiday(date_str)
    is_business_day = not (is_weekend or is_holiday)

    logger.info("date=%s weekend=%s holiday=%s businessDay=%s",
                date_str, is_weekend, is_holiday, is_business_day)
    return {
        "isBusinessDay": is_business_day,
        "date": date_str,
        "reason": "weekend" if is_weekend else ("holiday" if is_holiday else "business-day"),
    }
