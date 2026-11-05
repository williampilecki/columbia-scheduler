"""End-to-end checks against the locally built Java executable."""
import itertools
import random
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEADER = "term\tcourse\tid\ttitle\tday\tstart\tend\tfirst\tlast\n"


def run(*args):
    return subprocess.run(["java", "-jar", str(ROOT / "build" / "scheduler.jar"), *args],
                          text=True, capture_output=True, timeout=8)


def meeting(course, section, day="MONDAY", start="10:00", end="11:00", first="", last="", term="T"):
    return "\t".join([term, course, section, course, day, start, end, first, last]) + "\n"


def plan(rows, *courses):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "catalog.tsv"
        path.write_text(HEADER + "".join(rows))
        return run(str(path), "T", *courses)


class SchedulerTests(unittest.TestCase):
    def test_demo(self):
        result = run()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("6 valid combinations", result.stdout)
        self.assertEqual(re.findall(r"(\d+) weekly gap", result.stdout), ["30", "30", "60"])

    def test_half_open_intervals_and_weekday_in_date_intersection(self):
        a = meeting("A", "1", first="2026-09-07", last="2026-09-13")
        # Date overlap on Sunday only. Both meetings occur on Monday.
        b = meeting("B", "2", first="2026-09-13", last="2026-09-21")
        c = meeting("C", "3", start="11:00", end="12:00")
        self.assertIn("1 valid combination for", plan([a,b,c], "A","B","C").stdout)
        self.assertIn("0 valid combinations", plan([a,meeting("B","2")], "A","B").stdout)

    def test_conflict_subset_and_secondary_meeting(self):
        rows = [meeting("A","1","FRIDAY"),meeting("A","1"),meeting("B","2"),meeting("C","3","SUNDAY")]
        self.assertIn("supplied sections: A, B", plan(rows,"A","B","C").stdout)

    def test_unknown_async_terms_and_invalid_requests(self):
        rows = [meeting("A","1","ASYNC","",""),meeting("B","2"),
                meeting("B","2","TBA","",""),meeting("B","3",term="OTHER")]
        self.assertIn("1 valid combination for", plan(rows,"A").stdout)
        self.assertIn("unverified", plan(rows,"B").stderr)
        self.assertIn("exactly once", plan(rows,"A","A").stderr)
        self.assertIn("No catalog sections", plan(rows,"C").stderr)
        self.assertEqual(plan([meeting("A","1",end="09:00")],"A").returncode,2)

    def test_vergil_export(self):
        with tempfile.TemporaryDirectory() as directory:
            output = subprocess.check_output([sys.executable, str(ROOT/"tools/import_vergil.py"),
                                              str(ROOT/"data/vergil-example.json")], text=True)
            path = Path(directory)/"export.tsv"
            path.write_text(output)
            result = run(str(path),"20263","TESTUN2000")
            self.assertIn("2 valid combinations",result.stdout)
            self.assertIn("2026-09-08 to 2026-12-14",result.stdout)

    def test_generated_catalogs_against_cartesian_product(self):
        rng = random.Random(3134)
        for _ in range(25):
            groups, rows = [], []
            for course in "ABCD":
                group = []
                for idx in range(3):
                    start = rng.randrange(9,16)
                    day = rng.choice(["MONDAY","TUESDAY"])
                    section = f"{course}{idx}"
                    group.append((day,start,section))
                    rows.append(meeting(course,section,day,f"{start:02}:00",f"{start+1:02}:00"))
                groups.append(group)
            expected = []
            for combo in itertools.product(*groups):
                slots = {(day,start) for day,start,_ in combo}
                if len(slots) != len(combo):
                    continue
                gaps = 0
                for day in ["MONDAY","TUESDAY"]:
                    times = sorted(start for d,start in slots if d == day)
                    gaps += sum((b-a-1)*60 for a,b in zip(times,times[1:]))
                expected.append((gaps,sorted(section for _,_,section in combo)))
            expected.sort()
            result = plan(rows,*"ABCD")
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn(f"{len(expected)} valid combination",result.stdout)
            actual = []
            for option in re.split(r"Option \d+ \| ",result.stdout)[1:]:
                actual.append((int(option.split()[0]),sorted(re.findall(r"\[([A-D]\d)\]",option))))
            self.assertEqual(actual,expected[:3])


if __name__ == "__main__":
    unittest.main(verbosity=2)
