import re
import unittest
from pathlib import Path


class DashboardIsolationTests(unittest.TestCase):
    def test_summary_fetch_does_not_assign_shared_state(self):
        source = (Path(__file__).resolve().parents[1] / "ui/src/main/java/com/skapp/app/ui/state/NewsSummaryHolder.kt").read_text()
        body = source.split("fun loadSummary():", 1)[1].split("suspend fun refreshSummary", 1)[0]
        self.assertIsNone(re.search(r"_state\.value\s*=(?!=)", body))
        self.assertIn("WorkbenchSummary.asMap(_state.value.copy(board = board))", body)
        self.assertIn("s.copy(summary = v)", body)

    def test_insufficient_local_board_is_not_labeled_realtime(self):
        source = (Path(__file__).resolve().parents[1] / "ui/src/main/java/com/skapp/app/ui/screens/DashboardScreen.kt").read_text()
        insufficient = source.index('"本地计算·证据不足"')
        realtime = source.index('-> "本地实时"')
        self.assertLess(insufficient, realtime)
        self.assertIn('b["status"] in setOf("insufficient", "insufficient_data")', source)


if __name__ == "__main__":
    unittest.main()
