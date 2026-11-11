"""Isolated regression tests for the public Sina symbol-table collector."""
import json
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

from skapp_pkg.datasrc import market_data


class AllSymbolsTests(unittest.TestCase):
    def setUp(self):
        self.rows = [
            {"symbol": f"{'sh' if i % 2 else 'sz'}{i:06d}",
             "name": f"Stock {i}", "trade": "12.5", "changepercent": "-1.2"}
            for i in range(3101)
        ]
        self.count = 3101
        self.pages = {}
        self.events = []
        self.http = self.enterContext(patch.object(market_data, "_http", side_effect=self.fetch))
        self.sleep = self.enterContext(patch.object(
            market_data.time, "sleep", side_effect=lambda delay: self.events.append(("sleep", delay))))
        self.enterContext(patch.object(
            market_data.urllib.request, "urlopen", side_effect=AssertionError("Network forbidden")))

    def fetch(self, url, **kwargs):
        self.events.append(("http", url))
        if "getHQNodeStockCount" in url:
            return self.count if isinstance(self.count, Exception) else json.dumps(self.count)
        page = int(parse_qs(urlparse(url).query)["page"][0])
        data = self.pages.get(page, self.rows[(page - 1) * 100:page * 100])
        if isinstance(data, Exception):
            raise data
        return json.dumps(data)

    def test_complete_table_and_request_contract(self):
        for count in (3101, "3101", 3101.0):
            with self.subTest(count=count):
                self.count = count
                self.events.clear()
                self.http.reset_mock()
                result = market_data.all_symbols()
                self.assertEqual(len(result), 3101)
                self.assertEqual(len({row["symbol"] for row in result}), 3101)
                self.assertEqual(result[0], {"symbol": "sz000000", "name": "Stock 0",
                                             "price": 12.5, "pct_chg": -1.2})
                self.assertIn("getHQNodeStockCount?node=hs_a", self.http.call_args_list[0].args[0])
                self.assertEqual(self.http.call_count, 33)
                for call in self.http.call_args_list:
                    self.assertTrue(call.kwargs["headers"]["User-Agent"])
                    self.assertEqual(call.kwargs["headers"]["Referer"], "https://finance.sina.com.cn/")
                positions = [i for i, event in enumerate(self.events) if event[0] == "http"]
                for first, second in zip(positions, positions[1:]):
                    self.assertTrue(any(kind == "sleep" and delay >= 0.7
                                        for kind, delay in self.events[first + 1:second]))

    def test_midway_error_rejects_partial_table(self):
        self.pages[2] = OSError("interrupted")
        with self.assertRaises(RuntimeError):
            market_data.all_symbols()

    def test_early_empty_page_rejects_partial_table(self):
        self.pages[2] = []
        with self.assertRaises(RuntimeError):
            market_data.all_symbols()

    def test_duplicates_cannot_fill_expected_count(self):
        self.pages[2] = self.rows[:100]
        with self.assertRaises(RuntimeError):
            market_data.all_symbols()

    def test_duplicate_symbol_is_deduplicated(self):
        self.pages[32] = self.rows[3100:] + self.rows[:1]
        self.assertEqual(len(market_data.all_symbols()), 3101)

    def test_conflicting_duplicate_symbol_rejects_table(self):
        self.pages[32] = self.rows[3100:] + [{**self.rows[0], "name": "Changed name"}]
        with self.assertRaises(RuntimeError):
            market_data.all_symbols()

    def test_missing_shenzhen_rejects_table(self):
        for i, row in enumerate(self.rows):
            row["symbol"] = f"sh{i:06d}"
        with self.assertRaises(RuntimeError):
            market_data.all_symbols()

    def test_invalid_count_rejects_before_paging(self):
        for count in (None, True, {}, [], "bad", "2999", 3101.5):
            with self.subTest(count=count):
                self.count = count
                self.http.reset_mock()
                with self.assertRaises(RuntimeError):
                    market_data.all_symbols()
                self.assertEqual(self.http.call_count, 1)

    def test_count_transport_or_json_error_rejects(self):
        for value in (OSError("count unavailable"), "not json"):
            with self.subTest(value=value):
                self.http.side_effect = value if isinstance(value, Exception) else lambda *a, **k: value
                with self.assertRaises(RuntimeError):
                    market_data.all_symbols()

    def test_max_pages_is_a_hard_bound(self):
        with self.assertRaises(RuntimeError):
            market_data.all_symbols(max_pages=1)
        self.assertEqual(self.http.call_count, 2)

    def test_invalid_page_shape_rejects(self):
        for data in ({"error": "blocked"}, "blocked", [None]):
            with self.subTest(data=data):
                self.pages[2] = data
                with self.assertRaises(RuntimeError):
                    market_data.all_symbols()

    def test_invalid_symbol_or_name_rejects(self):
        for field, value in (("symbol", "invalid"), ("symbol", None),
                             ("name", "  "), ("name", None)):
            with self.subTest(field=field, value=value):
                original = self.rows[0][field]
                self.rows[0][field] = value
                with self.assertRaises(RuntimeError):
                    market_data.all_symbols()
                self.rows[0][field] = original

    def test_missing_and_nonfinite_quotes_keep_symbols_as_null(self):
        self.rows[0].pop("trade")
        self.rows[0].pop("changepercent")
        for row, value in zip(self.rows[1:6], (None, "", "NaN", "Infinity", "-Infinity")):
            row["trade"] = row["changepercent"] = value
        result = market_data.all_symbols()
        self.assertEqual(len(result), 3101)
        for row in result[:6]:
            self.assertIsNone(row["price"])
            self.assertIsNone(row["pct_chg"])
        json.dumps(result, allow_nan=False)


if __name__ == "__main__":
    unittest.main()
