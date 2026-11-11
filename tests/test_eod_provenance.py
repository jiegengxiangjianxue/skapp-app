import unittest

from skapp_pkg.pipeline.eod_sync import _build_picks


class SnapshotProvenanceTests(unittest.TestCase):
    def test_unknown_invalid_future_and_old_dates_are_historical(self):
        for asof in (None, "2026-02-30", "2026-11-12", "2026-10-29"):
            with self.subTest(asof=asof):
                result = _build_picks({"asof": asof, "items": []}, [], {}, "2026-11-11")
                self.assertTrue(result["stale"])
                self.assertEqual(result["status"], "insufficient_data")
                self.assertEqual(result["usage"], "historical_only")

    def test_file_generation_date_does_not_replace_unknown_evidence(self):
        engine = {"asof": None, "date": "2026-11-11",
                  "items": [{"symbol": "sh600001", "date": None}]}
        result = _build_picks(engine, [], {}, "2026-11-11")
        self.assertIsNone(result["engine_asof"])
        self.assertTrue(result["stale"])
        self.assertEqual(result["status"], "insufficient_data")

    def test_upstream_insufficient_state_cannot_be_cleared_by_today(self):
        for extra in ({"stale": True}, {"status": "insufficient_data"}):
            with self.subTest(extra=extra):
                result = _build_picks({"asof": "2026-11-11", "items": [], **extra},
                                      [], {}, "2026-11-11")
                self.assertTrue(result["stale"])
                self.assertEqual(result["status"], "insufficient_data")

    def test_valid_archive_does_not_claim_live_recommendation(self):
        result = _build_picks({"asof": "2026-11-11", "items": []}, [], {}, "2026-11-11")
        self.assertFalse(result["stale"])
        self.assertEqual(result["status"], "candidate_archive")
        self.assertEqual(result["usage"], "archive_not_live_recommendation")


if __name__ == "__main__":
    unittest.main()
