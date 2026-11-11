#!/usr/bin/env python3
"""Client asset download recovery; deterministic, no network or JDK."""
import contextlib
import hashlib
import http.client
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import urllib.error

spec = importlib.util.spec_from_file_location(
    "fetch_client", Path(__file__).resolve().parents[1] / "fetch-visual-client.py")
fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch)


class FetchClientTest(unittest.TestCase):
    def response(self, data=b"complete"):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = data
        return response

    def test_disconnect_and_partial_read_retry_whole_response(self):
        partial = self.response()
        partial.__enter__.return_value.read.side_effect = http.client.IncompleteRead(b"partial", 10)
        with patch.object(fetch.urllib.request, "urlopen", side_effect=[
                http.client.RemoteDisconnected("closed"), partial, self.response()]) as get, \
                patch.object(fetch.time, "sleep") as sleep, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(fetch.http_get("https://example.test/asset"), b"complete")
            self.assertEqual(get.call_count, 3)
            self.assertEqual([c.args[0] for c in sleep.call_args_list], [1, 2])

    def test_retryable_http_error_recovers(self):
        error = urllib.error.HTTPError("https://example.test/asset", 503, "unavailable", {}, None)
        with patch.object(fetch.urllib.request, "urlopen", side_effect=[error, self.response()]) as get, \
                patch.object(fetch.time, "sleep"), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(fetch.http_get(error.url), b"complete")
            self.assertEqual(get.call_count, 2)

    def test_permanent_http_error_fails_immediately(self):
        error = urllib.error.HTTPError("https://example.test/asset", 404, "missing", {}, None)
        with patch.object(fetch.urllib.request, "urlopen", side_effect=error) as get, \
                patch.object(fetch.time, "sleep") as sleep:
            with self.assertRaises(urllib.error.HTTPError):
                fetch.http_get(error.url)
            self.assertEqual(get.call_count, 1)
            sleep.assert_not_called()

    def test_unsolicited_304_retries_fresh_unconditional_body(self):
        url = "https://example.test/asset?known=1"
        error = urllib.error.HTTPError(url, 304, "not modified", {}, None)
        with patch.object(fetch.urllib.request, "urlopen", side_effect=[error, self.response()]) as get, \
                patch.object(fetch.time, "sleep"), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(fetch.http_get(url), b"complete")
            retry = get.call_args_list[1].args[0]
            parsed = fetch.urllib.parse.urlsplit(retry.full_url)
            query = fetch.urllib.parse.parse_qs(parsed.query)
            self.assertEqual(parsed.path, "/asset")
            self.assertEqual(query["known"], ["1"])
            self.assertIn("cim_retry", query)
            self.assertEqual(retry.get_header("Cache-control"), "no-cache")
            self.assertIsNone(retry.get_header("If-none-match"))

    def test_repeated_304_never_becomes_success(self):
        error = urllib.error.HTTPError("https://example.test/asset", 304, "not modified", {}, None)
        with patch.object(fetch.urllib.request, "urlopen", side_effect=error) as get, \
                patch.object(fetch.time, "sleep"), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(urllib.error.HTTPError):
                fetch.http_get(error.url)
            self.assertEqual(get.call_count, 4)

    def test_wrong_asset_hash_cannot_replace_existing_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "asset"
            path.write_bytes(b"existing")
            with patch.object(fetch.urllib.request, "urlopen", return_value=self.response(b"broken")):
                with self.assertRaisesRegex(ValueError, "SHA1 mismatch"):
                    fetch.fetch_to("https://example.test/asset", path,
                                   expected_sha1=hashlib.sha1(b"wanted").hexdigest())
            self.assertEqual(path.read_bytes(), b"existing")
            with patch.object(fetch.urllib.request, "urlopen", return_value=self.response(b"wanted")):
                self.assertEqual(fetch.fetch_to("https://example.test/asset", path,
                                               expected_sha1=hashlib.sha1(b"wanted").hexdigest()), 6)
            self.assertEqual(path.read_bytes(), b"wanted")

    def test_exhaustion_preserves_existing_asset(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "asset"
            path.write_bytes(b"existing")
            with patch.object(fetch.urllib.request, "urlopen", side_effect=TimeoutError("timed out")) as get, \
                    patch.object(fetch.time, "sleep") as sleep, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(TimeoutError):
                    fetch.fetch_to("https://example.test/asset", path)
                self.assertEqual(get.call_count, 4)
                self.assertEqual([c.args[0] for c in sleep.call_args_list], [1, 2, 4])
                self.assertEqual(path.read_bytes(), b"existing")


if __name__ == "__main__":
    unittest.main()
