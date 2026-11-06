"""Offline unit test of the IntiMed Python client against a local stub server (no internet, no IntiMed instance).

Run from intimed/sdk/python:  python3 -m unittest discover -s tests -v
"""
import json
import os
import sys
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from intimed_client import IntiMedClient, IntiMedError  # noqa: E402


class _Stub(BaseHTTPRequestHandler):
    token_requests = 0
    last_auth = None
    last_body = None

    def log_message(self, *args):  # silence
        pass

    def _json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode()
        if self.path == "/oauth2/token":
            form = urllib.parse.parse_qs(raw)
            if form.get("client_secret") != ["s3cret+/="] or form.get("grant_type") != ["client_credentials"]:
                return self._json(401, {"detail": "invalid client"})
            _Stub.token_requests += 1
            return self._json(200, {"access_token": "tok-1", "token_type": "Bearer", "expires_in": 900, "scope": form.get("scope", [""])[0]})
        if self.path == "/api/v1/harness/runs":
            _Stub.last_auth = self.headers.get("Authorization")
            _Stub.last_body = json.loads(raw)
            return self._json(200, {"id": "run-abc", "configId": _Stub.last_body["configId"], "driver": "spiro@1.0.0", "status": "PASSED",
                                    "passed": 16, "total": 16, "seal": "a" * 64, "results": {}})
        self._json(404, {"detail": "not found"})

    def do_GET(self):
        _Stub.last_auth = self.headers.get("Authorization")
        if self.path.startswith("/api/v1/catalogue/models"):
            return self._json(200, [{"id": "IMD-001", "manufacturer": "ndd", "model": "EasyOne Air", "family": "Spirometry", "options": ["P2"], "fdaCodes": ["BZG"],
                                     "certifiedDrivers": 1, "badges": [{"id": "bdg-1", "level": "GOLD", "driver": "x@1", "grantedAt": "2026-11-06T00:00:00Z",
                                                                        "harnessVersion": "2.0", "evidenceSeal": "b" * 64, "runId": "run-abc"}]}])
        if self.path.startswith("/fhir/r4/Observation?"):
            return self._json(200, {"resourceType": "Bundle", "type": "searchset", "total": 0, "entry": [], "query": self.path})
        if self.path == "/api/public/openapi.json":
            return self._json(200, {"openapi": "3.1.0", "paths": {"/fhir/r4/Observation": {}}})
        if self.path == "/redirect/api/public/openapi.json":
            self.send_response(302)
            self.send_header("Location", "http://169.254.169.254/")
            self.end_headers()
            return None
        if self.path == "/api/v1/events":
            return self._json(403, {"detail": "Requires scope events.read"})
        return self._json(404, {"detail": "not found"})


class ClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), _Stub)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = "http://127.0.0.1:%d" % cls.server.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def client(self, **kw):
        return IntiMedClient(self.base, "client-1", "s3cret+/=", "catalogue.read harness.run", allow_insecure_localhost=True, **kw)

    def test_https_required_except_explicit_localhost(self):
        with self.assertRaises(ValueError):
            IntiMedClient("http://intimed.example.org", "a", "b")
        with self.assertRaises(ValueError):
            IntiMedClient(self.base, "a", "b")

    def test_token_is_fetched_once_and_cached(self):
        _Stub.token_requests = 0
        c = self.client()
        models = c.search_catalogue("spiro")
        c.search_catalogue()
        self.assertEqual(_Stub.token_requests, 1)
        self.assertEqual(_Stub.last_auth, "Bearer tok-1")
        self.assertEqual(models[0].id, "IMD-001")
        self.assertEqual(models[0].badges[0].level, "GOLD")
        self.assertEqual(models[0].badges[0].evidence_seal, "b" * 64)

    def test_harness_run_and_fhir_search(self):
        c = self.client()
        run = c.run_harness("cfg-1")
        self.assertEqual(_Stub.last_body, {"configId": "cfg-1"})
        self.assertEqual(run.status, "PASSED")
        self.assertEqual(run.passed, run.total)
        bundle = c.search_observations(subject="MRN 1", count=5)
        self.assertIn("subject=MRN+1", bundle["query"])
        self.assertIn("_count=5", bundle["query"])

    def test_errors_carry_status_and_problem_detail(self):
        with self.assertRaises(IntiMedError) as e:
            self.client().events()
        self.assertEqual(e.exception.status, 403)
        self.assertIn("events.read", str(e.exception))
        with self.assertRaises(IntiMedError) as bad:
            IntiMedClient(self.base, "client-1", "wrong", allow_insecure_localhost=True).search_catalogue()
        self.assertEqual(bad.exception.status, 401)

    def test_openapi_and_no_redirects(self):
        self.assertEqual(self.client().openapi()["openapi"], "3.1.0")
        c = IntiMedClient(self.base + "/redirect", "client-1", "s3cret+/=", allow_insecure_localhost=True)
        with self.assertRaises(IntiMedError) as e:
            c.openapi()
        self.assertEqual(e.exception.status, 302)


if __name__ == "__main__":
    unittest.main()
