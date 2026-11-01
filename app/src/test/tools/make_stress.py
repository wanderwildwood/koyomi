#!/usr/bin/env python3
"""Makes the repeating-events stress file and the table of what each month must show.

  make_stress.py <out_dir> [zone]

Writes <out_dir>/stress.ics (about 300 events, a made-up cast, no real data) and
<out_dir>/stress-expected.csv (month,count: every occurrence on a day of that month, read in
<zone>, default America/New_York, January 2026 to December 2027). The counts come from a plain
day-by-day walk of each rule below, which shares no code with the app or with Android's
recurrence expander, so the two can disagree and say so.

Cases: daily COUNT; weekly BYDAY with several days; every other week; monthly BYMONTHDAY
(including the 31st, which skips short months); monthly 2nd Tuesday, last Friday (BYDAY=-1FR and
BYDAY=FR;BYSETPOS=-1); yearly; UNTIL in UTC, as a date, and written in local time; EXDATE as one
list, as several lines, in UTC, in local time and as dates; edited occurrences (RECURRENCE-ID)
whose series is and is not in the file; all-day series; series crossing both DST changes; a TZID
that is a Windows zone name; open-ended series.
"""
import csv
import re
import os
import datetime as dt
import sys
from zoneinfo import ZoneInfo

OUT = sys.argv[1]
SHOW = ZoneInfo(sys.argv[2] if len(sys.argv) > 2 else "America/New_York")
UTC = dt.timezone.utc
H0, H1 = dt.date(2026, 1, 1), dt.date(2027, 12, 31)  # the months counted
WD = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"]
WINDOWS = {"America/New_York": "Eastern Standard Time", "Europe/Berlin": "W. Europe Standard Time",
           "America/Chicago": "Central Standard Time", "Asia/Tokyo": "Tokyo Standard Time"}

CAST = ["Ada Whitlock", "Tomas Reyes", "Mira Okafor", "Jun Park", "Sol Brandt", "Ines Duarte"]
THINGS = ["Pottery", "Choir", "Standup", "Rota", "Bins", "Tide check", "Seedlings", "Repair cafe", "Book club",
          "Swim", "Piano", "Allotment", "Library", "Quiz", "Market", "Stretch"]

events = []   # (text lines)
series = []   # dicts for counting
uid_n = [0]
rng_state = [12345]


def rnd(n):
    rng_state[0] = (rng_state[0] * 1103515245 + 12345) & 0x7FFFFFFF
    return rng_state[0] % n


def uid():
    uid_n[0] += 1
    return f"stress-{uid_n[0]:04d}@example.org"


def title():
    return f"{THINGS[rnd(len(THINGS))]} with {CAST[rnd(len(CAST))].split()[0]}"


def fmt_local(d):
    return d.strftime("%Y%m%dT%H%M%S")


def fmt_utc(d):
    return d.astimezone(UTC).strftime("%Y%m%dT%H%M%SZ")


def rrule_text(r):
    parts = [f"FREQ={r['freq']}"]
    if r.get("interval", 1) != 1:
        parts.append(f"INTERVAL={r['interval']}")
    if r.get("byday"):
        parts.append("BYDAY=" + ",".join((str(n) if n else "") + WD[w] for n, w in r["byday"]))
    if r.get("bymonthday"):
        parts.append(f"BYMONTHDAY={r['bymonthday']}")
    if r.get("bymonth"):
        parts.append(f"BYMONTH={r['bymonth']}")
    if r.get("bysetpos"):
        parts.append(f"BYSETPOS={r['bysetpos']}")
    if r.get("count"):
        parts.append(f"COUNT={r['count']}")
    if r.get("until_text"):
        parts.append(f"UNTIL={r['until_text']}")
    return ";".join(parts)


# ---------------------------------------------------------------- expansion (day by day)

def nth_weekday(year, month, n, wd):
    days = []
    d = dt.date(year, month, 1)
    while d.month == month:
        if d.weekday() == wd:
            days.append(d)
        d += dt.timedelta(days=1)
    return days[n - 1] if n > 0 else days[n]


def matches(r, start_date, d):
    f = r["freq"]
    k = r.get("interval", 1)
    if d < start_date:
        return False
    if f == "DAILY":
        return (d - start_date).days % k == 0
    if f == "WEEKLY":
        wk0 = start_date - dt.timedelta(days=start_date.weekday())
        wk = d - dt.timedelta(days=d.weekday())
        days = {w for _, w in r["byday"]} if r.get("byday") else {start_date.weekday()}
        return ((wk - wk0).days // 7) % k == 0 and d.weekday() in days
    if f == "MONTHLY":
        if ((d.year - start_date.year) * 12 + d.month - start_date.month) % k:
            return False
        if r.get("bymonthday"):
            return d.day == r["bymonthday"]
        sel = []
        for n, w in r["byday"]:
            if n:
                try:
                    sel.append(nth_weekday(d.year, d.month, n, w))
                except IndexError:
                    pass
            else:
                x = dt.date(d.year, d.month, 1)
                while x.month == d.month:
                    if x.weekday() == w:
                        sel.append(x)
                    x += dt.timedelta(days=1)
        sel.sort()
        if r.get("bysetpos"):
            p = r["bysetpos"]
            sel = [sel[p - 1]] if p > 0 else [sel[p]]
        return d in sel
    if f == "YEARLY":
        return d.month == start_date.month and d.day == start_date.day
    raise ValueError(f)


def occurrences(s):
    """Wall-clock datetimes (or dates) of every occurrence, EXDATE applied, to the horizon."""
    r, start = s["rule"], s["start"]
    start_date = start if isinstance(start, dt.date) and not isinstance(start, dt.datetime) else start.date()
    out, d, n = [], start_date, 0
    end = H1 + dt.timedelta(days=40)
    while d <= end:
        if matches(r, start_date, d):
            wall = d if s["allday"] else dt.datetime.combine(d, start.time())
            if s.get("until"):
                u = s["until"]
                if s["allday"]:
                    if d > u:
                        break
                else:
                    inst = wall.replace(tzinfo=s["zone"])
                    if inst.astimezone(UTC) > u:
                        break
            n += 1
            if r.get("count") and n > r["count"]:
                break
            out.append(wall)
        d += dt.timedelta(days=1)
    return [o for o in out if o not in s.get("exdates", [])]


def shown_date(s, wall):
    if s["allday"]:
        return wall
    return wall.replace(tzinfo=s["zone"]).astimezone(SHOW).date()


# ---------------------------------------------------------------- writing

def add_series(zone="America/New_York", start=None, minutes=60, allday=False, rule=None, until=None, until_kind=None,
               ex=(), ex_style="one", tzid=None, name=None, override=None):
    """until: wall datetime/date of the last allowed occurrence moment (see until_kind)."""
    z = ZoneInfo(zone) if zone != "UTC" else UTC
    u = uid()
    rule = dict(rule)
    s = {"uid": u, "zone": z, "start": start, "allday": allday, "rule": rule, "exdates": list(ex)}
    lines = ["BEGIN:VEVENT", f"UID:{u}", f"SUMMARY:{name or title()}"]
    if allday:
        lines.append(f"DTSTART;VALUE=DATE:{start.strftime('%Y%m%d')}")
        lines.append(f"DTEND;VALUE=DATE:{(start + dt.timedelta(days=1)).strftime('%Y%m%d')}")
    elif zone == "UTC":
        lines.append(f"DTSTART:{start.strftime('%Y%m%dT%H%M%S')}Z")
        lines.append(f"DTEND:{(start + dt.timedelta(minutes=minutes)).strftime('%Y%m%dT%H%M%S')}Z")
    else:
        tz = tzid or zone
        lines.append(f"DTSTART;TZID={tz}:{fmt_local(start)}")
        lines.append(f"DTEND;TZID={tz}:{fmt_local(start + dt.timedelta(minutes=minutes))}")
    if until is not None:
        if until_kind == "utc":
            inst = until.replace(tzinfo=z).astimezone(UTC)
            rule["until_text"] = inst.strftime("%Y%m%dT%H%M%SZ")
            s["until"] = inst
        elif until_kind == "local":
            rule["until_text"] = fmt_local(until)
            s["until"] = until.replace(tzinfo=z).astimezone(UTC)
        else:
            rule["until_text"] = until.strftime("%Y%m%d")
            s["until"] = until
    lines.append("RRULE:" + rrule_text(rule))
    ex = list(ex)
    if ex:
        def one(x):
            if allday:
                return x.strftime("%Y%m%d")
            if zone == "UTC":
                return x.strftime("%Y%m%dT%H%M%SZ")
            return fmt_local(x)
        prop = "EXDATE;VALUE=DATE" if allday else ("EXDATE" if zone == "UTC" else f"EXDATE;TZID={tzid or zone}")
        if ex_style == "one":
            lines.append(f"{prop}:" + ",".join(one(x) for x in ex))
        else:
            for x in ex:
                lines.append(f"{prop}:{one(x)}")
    lines.append("END:VEVENT")
    events.append(lines)
    series.append(s)
    return s


singles = []  # (wall start, zone, allday)


def add_single(zone, start, minutes=60, allday=False, name=None, recurrence_id=None, uid_=None):
    z = ZoneInfo(zone) if zone != "UTC" else UTC
    u = uid_ or uid()
    lines = ["BEGIN:VEVENT", f"UID:{u}", f"SUMMARY:{name or title()}"]
    if recurrence_id is not None:
        lines.append(f"RECURRENCE-ID;TZID={zone}:{fmt_local(recurrence_id)}")
    if allday:
        lines.append(f"DTSTART;VALUE=DATE:{start.strftime('%Y%m%d')}")
        lines.append(f"DTEND;VALUE=DATE:{(start + dt.timedelta(days=1)).strftime('%Y%m%d')}")
    elif zone == "UTC":
        lines.append(f"DTSTART:{start.strftime('%Y%m%dT%H%M%S')}Z")
        lines.append(f"DTEND:{(start + dt.timedelta(minutes=minutes)).strftime('%Y%m%dT%H%M%S')}Z")
    else:
        lines.append(f"DTSTART;TZID={zone}:{fmt_local(start)}")
        lines.append(f"DTEND;TZID={zone}:{fmt_local(start + dt.timedelta(minutes=minutes))}")
    lines.append("END:VEVENT")
    events.append(lines)
    return {"zone": z, "start": start, "allday": allday, "single": True}


def D(y, m, d, h=9, mi=0):
    return dt.datetime(y, m, d, h, mi)


# ---- 1 daily COUNT (14)
for i in range(14):
    add_series(start=D(2026, 1 + i % 12, 3 + i, 7 + i % 6), rule=dict(freq="DAILY", count=3 + i))
# ---- 2 weekly BYDAY multi-day (16)
combos = [[0, 2, 4], [1, 3], [5, 6], [0, 1, 2, 3, 4], [0], [2, 4, 6], [1, 4], [0, 3]]
for i in range(16):
    c = combos[i % len(combos)]
    first = dt.date(2026, 1 + i % 10, 1)
    while first.weekday() != c[0]:
        first += dt.timedelta(days=1)
    r = dict(freq="WEEKLY", byday=[(0, w) for w in c])
    if i % 2:
        r["count"] = 6 + i
    add_series(start=dt.datetime.combine(first, dt.time(18, 30)), rule=r)
# ---- 3 every other week, and every third (14)
for i in range(14):
    first = dt.date(2026, 1 + i % 9, 6 + i % 5)
    r = dict(freq="WEEKLY", interval=2 + (i % 3 == 2), byday=[(0, first.weekday())])
    if i % 3:
        r["count"] = 8 + i
    add_series(start=dt.datetime.combine(first, dt.time(8, 15)), rule=r)
# ---- 4 monthly BYMONTHDAY, incl. 31 and 30 (skips short months) (18)
for i, day in enumerate([1, 5, 10, 15, 15, 20, 28, 29, 30, 31, 31, 31, 30, 1, 12, 25, 31, 15]):
    m = 1 + i % 4
    r = dict(freq="MONTHLY", bymonthday=day)
    if i % 3 == 0:
        r["count"] = 7
    if i % 6 == 1:
        r["interval"] = 2
    if day > 28:
        m = [1, 3, 5][i % 3]  # a month that has the day
    add_series(start=D(2026, m, day, 12, 0), rule=r)
# ---- 5 monthly nth weekday, last Friday (BYDAY=-1FR and BYSETPOS) (24)
nth = [(2, 1), (2, 1), (1, 0), (3, 4), (4, 3), (-1, 4), (-1, 4), (-1, 0), (-2, 2), (5, 0), (1, 6), (3, 5)]
for i, (n, w) in enumerate(nth):
    first = nth_weekday(2026, 1 + i % 3, n if n != 5 else 4, w)
    if n == 5:  # the 5th Monday: only some months have one; the series starts in a month that does
        first = nth_weekday(2026, 3, 5, 0) if False else dt.date(2026, 3, 30)
    r = dict(freq="MONTHLY", byday=[(n, w)])
    if i % 4 == 3:
        r["count"] = 9
    add_series(start=dt.datetime.combine(first, dt.time(19, 0)), rule=r)
for i in range(12):
    first = nth_weekday(2026, 1 + i % 3, -1, 4)
    r = dict(freq="MONTHLY", byday=[(0, 4)], bysetpos=-1)
    if i % 3 == 2:
        r["count"] = 8
    add_series(start=dt.datetime.combine(first, dt.time(16, 45)), rule=r)
# ---- 6 yearly, all-day birthdays and timed (18)
for i in range(18):
    d = dt.date(2026, 1 + i % 12, 2 + (i * 5) % 26)
    r = dict(freq="YEARLY")
    if i % 5 == 4:
        r["count"] = 2
    if i % 2 == 0:
        add_series(start=d, allday=True, rule=r, name=f"{CAST[i % len(CAST)].split()[0]}'s birthday")
    else:
        add_series(start=dt.datetime.combine(d, dt.time(10, 0)), rule=r)
# ---- 7 UNTIL in UTC, as a date, and written in local time (30)
for i in range(10):
    first = dt.date(2026, 1 + i % 6, 5 + i)
    last = first + dt.timedelta(days=60 + i * 3)
    add_series(start=dt.datetime.combine(first, dt.time(21, 30)), rule=dict(freq="DAILY"),
               until=dt.datetime.combine(last, dt.time(21, 30)), until_kind="utc")
for i in range(10):
    first = dt.date(2026, 2 + i % 6, 4 + i)
    last = first + dt.timedelta(days=70 + i)
    add_series(start=dt.datetime.combine(first, dt.time(22, 0)), rule=dict(freq="DAILY", interval=1 + i % 3),
               until=dt.datetime.combine(last, dt.time(22, 0)), until_kind="local")
for i in range(10):
    first = dt.date(2026, 1 + i % 6, 1 + i)
    last = first + dt.timedelta(days=90 + i)
    add_series(start=first, allday=True, rule=dict(freq="WEEKLY", byday=[(0, first.weekday())]), until=last, until_kind="date")
# ---- 8 EXDATE (24)
for i in range(24):
    first = dt.date(2026, 3, 2 + i % 5)
    start = dt.datetime.combine(first, dt.time(8, 0))
    ex = [start + dt.timedelta(days=k) for k in (i % 3 + 1, 3 + i % 4, 9, 10 + i % 3)]
    style = "one" if i % 2 == 0 else "many"
    if i % 4 == 3:
        add_series(zone="UTC", start=start, rule=dict(freq="DAILY", count=40), ex=ex, ex_style=style)
    elif i % 6 == 5:
        add_series(start=first, allday=True, rule=dict(freq="DAILY", count=30), ex=[x.date() for x in ex], ex_style=style)
    else:
        add_series(start=start, rule=dict(freq="DAILY", count=40), ex=ex, ex_style=style)
# ---- 9 edited occurrences: series in the file (exdate + own event), series not in the file (own event) (30 + 30)
for i in range(30):
    first = dt.date(2026, 4, 1 + i % 20)
    start = dt.datetime.combine(first, dt.time(11, 0))
    s = add_series(start=start, rule=dict(freq="WEEKLY", byday=[(0, first.weekday())], count=10))
    moved = start + dt.timedelta(weeks=2)
    add_single("America/New_York", moved + dt.timedelta(hours=3), recurrence_id=moved, uid_=s["uid"], name="Moved: " + title())
lone = 0
for i in range(30):
    base = dt.datetime.combine(dt.date(2026, 5, 4) + dt.timedelta(days=i * 3), dt.time(14, 0))
    if i % 3 == 2:   # two edits sharing one UID, series elsewhere
        u = uid()
        for k in (0, 1):
            moved = base + dt.timedelta(days=7 * k)
            add_single("America/New_York", moved + dt.timedelta(hours=1), recurrence_id=moved, uid_=u)
            lone += 1
    else:
        add_single("America/New_York", base + dt.timedelta(hours=1), recurrence_id=base)
        lone += 1
# ---- 10 all-day series (20)
for i in range(20):
    first = dt.date(2026, 1 + i % 8, 3 + i)
    r = [dict(freq="DAILY", count=3 + i % 5), dict(freq="WEEKLY", byday=[(0, first.weekday())], count=8),
         dict(freq="MONTHLY", bymonthday=first.day, count=6), dict(freq="WEEKLY", interval=2, byday=[(0, first.weekday())])][i % 4]
    add_series(start=first, allday=True, rule=r)
# ---- 11 across both DST changes, New York wall time (20)
for i in range(20):
    first = dt.date(2026, 2, 22 + i % 7)
    hour = [9, 1, 2, 0, 23, 12][i % 6]
    r = [dict(freq="DAILY", count=300), dict(freq="WEEKLY", byday=[(0, first.weekday())], count=40),
         dict(freq="DAILY", interval=2, count=170)][i % 3]
    add_series(start=D(first.year, first.month, first.day, hour, 30), rule=r)
# ---- 12 a Windows zone name as TZID (20)
zones = ["America/New_York", "Europe/Berlin", "America/Chicago", "Asia/Tokyo"]
for i in range(20):
    z = zones[i % 4]
    first = dt.date(2026, 1 + i % 6, 6 + i % 10)
    add_series(zone=z, tzid=WINDOWS[z], start=dt.datetime.combine(first, dt.time(8 + i % 12, 0)),
               rule=dict(freq="WEEKLY", byday=[(0, first.weekday()), (0, (first.weekday() + 2) % 7)], count=30))
# ---- 13 open-ended (20)
for i in range(20):
    first = dt.date(2026, 1 + i % 10, 2 + i)
    r = [dict(freq="DAILY"), dict(freq="WEEKLY", byday=[(0, first.weekday())]),
         dict(freq="MONTHLY", bymonthday=first.day), dict(freq="YEARLY")][i % 4]
    add_series(start=dt.datetime.combine(first, dt.time(6, 45)), rule=r)

# ---- 14 plain single events (40)
extra = 0
for i in range(40):
    z = ["America/New_York", "Europe/Berlin", "UTC", "Asia/Tokyo", "America/Chicago"][i % 5]
    day = dt.date(2026, 1, 1) + dt.timedelta(days=i * 17)
    if i % 8 == 7:
        add_single("UTC", dt.datetime.combine(day, dt.time(0, 0)), allday=True)
    else:
        add_single(z, dt.datetime.combine(day, dt.time([1, 8, 13, 22, 23][i % 5], 15)))
    extra += 1

# ---------------------------------------------------------------- counts

counts = {}
dump = {}


def bump(d):
    if H0 <= d <= H1:
        counts[(d.year, d.month)] = counts.get((d.year, d.month), 0) + 1


# original starts of edits whose series is in the file, per series uid
edited_out = {}
for ev in events:
    t = "\n".join(ev)
    m = re.search(r"RECURRENCE-ID;TZID=([^:]+):(\d{8}T\d{6})", t)
    if m:
        edited_out.setdefault(re.search(r"UID:(\S+)", t).group(1), []).append(dt.datetime.strptime(m.group(2), "%Y%m%dT%H%M%S"))
for s in series:
    for wall in occurrences(s):
        if wall in edited_out.get(s["uid"], []):
            continue
        bump(shown_date(s, wall))
        if os.environ.get('DUMP'):
            k = (s['uid'], shown_date(s, wall).strftime('%Y-%m'))
            dump[k] = dump.get(k, 0) + 1
# every edited occurrence is one plain event at its NEW time; for a series in the file its
# original day is also taken out of the series (below)
# (re-read from the file text so the table cannot drift from what is written)
import re
for ev in events:
    text = "\n".join(ev)
    if "RECURRENCE-ID" not in text:
        continue
    m = re.search(r"UID:(\S+)", text).group(1)
    m2 = re.search(r"DTSTART;TZID=([^:]+):(\d{8}T\d{6})", text)
    z = ZoneInfo(m2.group(1))
    d = dt.datetime.strptime(m2.group(2), "%Y%m%dT%H%M%S").replace(tzinfo=z).astimezone(SHOW).date()
    bump(d)
# plain singles
for ev in events:
    text = "\n".join(ev)
    if "RRULE" in text or "RECURRENCE-ID" in text:
        continue
    m2 = re.search(r"DTSTART;VALUE=DATE:(\d{8})", text)
    if m2:
        bump(dt.datetime.strptime(m2.group(1), "%Y%m%d").date())
        continue
    m2 = re.search(r"DTSTART;TZID=([^:]+):(\d{8}T\d{6})", text)
    if m2:
        z, w = ZoneInfo(m2.group(1)), m2.group(2)
    else:
        z, w = UTC, re.search(r"DTSTART:(\d{8}T\d{6})Z", text).group(1)
    bump(dt.datetime.strptime(w, "%Y%m%dT%H%M%S").replace(tzinfo=z).astimezone(SHOW).date())

with open(f"{OUT}/stress.ics", "w", newline="") as f:
    f.write("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Datebook stress test//EN\r\nX-WR-CALNAME:Stress test\r\n")
    for ev in events:
        f.write("\r\n".join(ev) + "\r\n")
    f.write("END:VCALENDAR\r\n")
with open(f"{OUT}/stress-expected.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["month", "count"])
    for (y, m) in sorted(counts):
        w.writerow([f"{y}-{m:02d}", counts[(y, m)]])
print(len(events), "events;", sum(counts.values()), "occurrences in", len(counts), "months")

if os.environ.get('DUMP'):
    open(os.environ['DUMP'], 'w').write('\n'.join(f'{k[0]} {k[1]} {v}' for k, v in dump.items()))
