"""IntiMed reference client for Python (IM-E07-BL-004, partial).

Standard library only (urllib). OAuth2 client-credentials token helper with caching, typed helpers for the machine
APIs (FHIR R4, harness runs, catalogue, events) and the public OpenAPI document. HTTPS is required; plain http is
accepted for localhost only when ``allow_insecure_localhost=True`` (development and the offline unit test). Redirects
are never followed, so a bearer token can never be forwarded to another host.
"""
from __future__ import annotations

import json
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


class IntiMedError(Exception):
    """HTTP error answered by IntiMed (status code + problem body when available)."""

    def __init__(self, status: int, message: str, body: Any = None) -> None:
        super().__init__(f"HTTP {status}: {message}")
        self.status = status
        self.body = body


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401 - urllib API
        raise IntiMedError(code, "redirect refused")


@dataclass
class Token:
    access_token: str
    expires_at: float
    scope: str


@dataclass
class CatalogueBadge:
    id: str
    level: str
    driver: str
    granted_at: str
    harness_version: str
    evidence_seal: str
    run_id: str


@dataclass
class CatalogueModel:
    id: str
    manufacturer: str
    model: str
    family: str
    options: List[str]
    fda_codes: List[str]
    certified_drivers: int
    badges: List[CatalogueBadge] = field(default_factory=list)

    @staticmethod
    def from_json(d: Dict[str, Any]) -> "CatalogueModel":
        return CatalogueModel(
            id=d["id"], manufacturer=d.get("manufacturer", ""), model=d.get("model", ""), family=d.get("family", ""),
            options=list(d.get("options", [])), fda_codes=list(d.get("fdaCodes", [])), certified_drivers=int(d.get("certifiedDrivers", 0)),
            badges=[CatalogueBadge(b["id"], b.get("level", ""), b.get("driver", ""), b.get("grantedAt", ""), b.get("harnessVersion", ""),
                                   b.get("evidenceSeal", ""), b.get("runId", "")) for b in d.get("badges", [])])


@dataclass
class HarnessRun:
    id: str
    config_id: str
    driver: str
    status: str
    passed: int
    total: int
    seal: str
    results: Dict[str, Any]

    @staticmethod
    def from_json(d: Dict[str, Any]) -> "HarnessRun":
        return HarnessRun(d["id"], d.get("configId", ""), d.get("driver", ""), d.get("status", ""), int(d.get("passed", 0)), int(d.get("total", 0)),
                          d.get("seal", ""), d.get("results", {}))


class ClientCredentials:
    """OAuth2 client-credentials helper (client_secret_post); caches the token and refreshes 60 s before expiry."""

    def __init__(self, client: "IntiMedClient", client_id: str, client_secret: str, scope: Optional[str]) -> None:
        self._client = client
        self._id = client_id
        self._secret = client_secret
        self._scope = scope
        self._token: Optional[Token] = None
        self._lock = threading.Lock()

    def access_token(self) -> str:
        with self._lock:
            now = time.time()
            if self._token and now < self._token.expires_at - 60:
                return self._token.access_token
            form = {"grant_type": "client_credentials", "client_id": self._id, "client_secret": self._secret}
            if self._scope:
                form["scope"] = self._scope
            data = urllib.parse.urlencode(form).encode("ascii")
            body = self._client._send("POST", "/oauth2/token", data, {"Content-Type": "application/x-www-form-urlencoded"})
            self._token = Token(body["access_token"], now + float(body.get("expires_in", 300)), body.get("scope", ""))
            return self._token.access_token


class IntiMedClient:
    """Machine-API client. ``base_url`` e.g. ``https://intimed.example.org``."""

    def __init__(self, base_url: str, client_id: str, client_secret: str, scope: Optional[str] = None, *,
                 ssl_context: Optional[ssl.SSLContext] = None, timeout: float = 20.0, allow_insecure_localhost: bool = False) -> None:
        parsed = urllib.parse.urlparse(base_url)
        local = parsed.hostname in ("localhost", "127.0.0.1")
        if parsed.scheme != "https" and not (allow_insecure_localhost and local and parsed.scheme == "http"):
            raise ValueError("IntiMed client requires https")
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        handlers: List[Any] = [_NoRedirect()]
        if ssl_context is not None:
            handlers.append(urllib.request.HTTPSHandler(context=ssl_context))
        self._opener = urllib.request.build_opener(*handlers)
        self.credentials = ClientCredentials(self, client_id, client_secret, scope)

    # ------------------------------------------------------------------ transport
    def _send(self, method: str, path: str, data: Optional[bytes], headers: Dict[str, str]) -> Any:
        req = urllib.request.Request(self.base_url + path, data=data, method=method, headers={"Accept": "application/json", **headers})
        try:
            with self._opener.open(req, timeout=self.timeout) as r:
                text = r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                body = json.loads(raw) if raw else None
            except ValueError:
                body = raw
            detail = body.get("detail") if isinstance(body, dict) else None
            raise IntiMedError(e.code, detail or e.reason, body) from None
        return json.loads(text) if text else None

    def request(self, method: str, path: str, body: Any = None) -> Any:
        """Authenticated JSON request against any machine-API path (see /api/public/openapi.json)."""
        headers = {"Authorization": "Bearer " + self.credentials.access_token()}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/fhir+json" if path.startswith("/fhir/") else "application/json"
            data = json.dumps(body).encode("utf-8")
        return self._send(method, path, data, headers)

    # ------------------------------------------------------------------ FHIR R4
    def search_observations(self, subject: Optional[str] = None, code: Optional[str] = None, count: int = 50) -> Dict[str, Any]:
        q = {"_count": str(count)}
        if subject:
            q["subject"] = subject
        if code:
            q["code"] = code
        return self.request("GET", "/fhir/r4/Observation?" + urllib.parse.urlencode(q))

    def get_observation(self, observation_id: str) -> Dict[str, Any]:
        return self.request("GET", "/fhir/r4/Observation/" + urllib.parse.quote(observation_id, safe=""))

    def list_devices(self) -> Dict[str, Any]:
        return self.request("GET", "/fhir/r4/Device")

    # ------------------------------------------------------------------ machine API v1
    def run_harness(self, config_id: str) -> HarnessRun:
        return HarnessRun.from_json(self.request("POST", "/api/v1/harness/runs", {"configId": config_id}))

    def get_harness_run(self, run_id: str) -> HarnessRun:
        return HarnessRun.from_json(self.request("GET", "/api/v1/harness/runs/" + urllib.parse.quote(run_id, safe="")))

    def search_catalogue(self, q: Optional[str] = None) -> List[CatalogueModel]:
        path = "/api/v1/catalogue/models" + ("?" + urllib.parse.urlencode({"q": q}) if q else "")
        return [CatalogueModel.from_json(m) for m in self.request("GET", path)]

    def events(self) -> List[Dict[str, Any]]:
        return self.request("GET", "/api/v1/events")

    # ------------------------------------------------------------------ public
    def openapi(self) -> Dict[str, Any]:
        return self._send("GET", "/api/public/openapi.json", None, {})
