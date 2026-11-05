"""Convert one saved Vergil course search response to the planner's TSV on stdout."""
import datetime as dt
import json
import sys
from pathlib import Path

HEADER = "term course id title day start end first last".split()
DAYS = {}
for name, short in zip(("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
                       ("m", "t", "w", "r", "f", "sa", "su")):
    for alias in (name, name[:3], name[:2], short):
        DAYS[alias.lower()] = name.upper()


def required(record, key):
    value = record[key]
    if not isinstance(value, (str, int)) or isinstance(value, bool) or not str(value).strip():
        raise ValueError(f"Missing value for {key}")
    return str(value).strip()


def meeting_rows(section):
    rows = []
    for meeting in section.get("meeting_details") or []:
        pattern = meeting.get("meeting_pattern") or {}
        details = pattern.get("meetingpatterndetail_set") or []
        if not details:
            async_names = {"ASYNC", "ASYNCHRONOUS"}
            status = "ASYNC" if str(pattern.get("meeting_pattern_name")).upper() in async_names else "TBA"
            rows.append([status, "", "", "", ""])
        for detail in details:
            day = DAYS.get(str(detail.get("week_day")).lower())
            first, last = meeting.get("begin_date") or "", meeting.get("end_date") or ""
            try:
                start = dt.time.fromisoformat(detail["from_time"])
                end = dt.time.fromisoformat(detail["to_time"])
                valid = day and start < end and all(t.second == t.microsecond == 0 and t.tzinfo is None for t in (start, end))
                if first or last:
                    valid = valid and dt.date.fromisoformat(first) <= dt.date.fromisoformat(last)
            except (KeyError, ValueError, TypeError):
                valid = False
            if valid:
                rows.append([day, start.strftime("%H:%M"), end.strftime("%H:%M"), first, last])
            else:
                rows.append(["TBA", "", "", "", ""])
    return rows or [["TBA", "", "", "", ""]]


def convert(payload):
    data = payload["data"]
    courses = data["courses"]
    if not isinstance(courses, list):
        raise ValueError("Expected data.courses array")
    lines = ["\t".join(HEADER)]
    for course in courses:
        base = required(course["subject"], "subject_code")
        number = required(course, "course_number")
        block = course["class_data"]
        classes = block["classes"]
        if not isinstance(classes, list) or int(block.get("class_count", len(classes))) > len(classes):
            raise ValueError(f"Incomplete section list for {base} {number}")
        for section in classes:
            suffix = (section.get("class_suffix") or {}).get("course_suffix_code") or ""
            component = (section.get("component") or {}).get("component_code") or "LC"
            code = (base + suffix + number).upper()
            if component.upper() != "LC":
                code += "/" + component.upper()
            common = [required(section, "term_calendar_code"), code, required(section, "id"),
                      " ".join(required(course, "course_official_title").split())]
            for meeting in meeting_rows(section):
                fields = common + meeting
                if any(any(c in field for c in "\n\r\t") for field in fields):
                    raise ValueError("Unexpected separator in source data")
                lines.append("\t".join(fields))
    return lines


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: python3 tools/import_vergil.py RESPONSE.json > catalog.tsv")
    try:
        lines = convert(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")))
    except (OSError, AttributeError, ValueError, TypeError, KeyError) as exc:
        sys.exit(f"Vergil import failed: {exc}")
    print("\n".join(lines))
