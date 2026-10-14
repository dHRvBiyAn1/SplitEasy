"""Container smoke check. Creates a disposable account/group; use only against test stacks."""
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

origin = os.environ.get("SMOKE_ORIGIN", "http://localhost:18080").rstrip("/")
state_file = Path(os.environ.get("SMOKE_STATE", "/tmp/spliteasy-smoke.json"))


def request(path, body=None, token=None):
    headers = {}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = Request(origin + path, headers=headers,
                  data=json.dumps(body).encode() if body is not None else None)
    with urlopen(req, timeout=20) as response:
        return response.read().decode()


assert json.loads(request("/api/health"))["status"] == "UP"
for path, expected in [("/actuator/health/readiness", 404), ("/api/dashboard", 401), ("/login", 404)]:
    try:
        request(path)
        raise AssertionError(f"{path} should return {expected}")
    except HTTPError as error:
        assert error.code == expected, (path, error.code)

for path in ["/api/auth/login", "/api/groups"]:
    req = Request(origin + path, method="OPTIONS", headers={
        "Origin": "https://split-easy.io",
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "authorization,content-type",
    })
    with urlopen(req, timeout=20) as response:
        assert response.headers["Access-Control-Allow-Origin"] == "https://split-easy.io"
        assert "authorization" in response.headers["Access-Control-Allow-Headers"].lower()
    req.headers["Origin"] = "https://untrusted.example"
    try:
        with urlopen(req, timeout=20) as response:
            assert response.headers.get("Access-Control-Allow-Origin") is None
    except HTTPError as error:
        assert error.code == 403

if len(sys.argv) > 1 and sys.argv[1] == "verify-persistence":
    state = json.loads(state_file.read_text())
    token = json.loads(request("/api/auth/login", {
        "email": state["email"], "password": state["password"]
    }))["accessToken"]
    assert json.loads(request(f'/api/groups/{state["group"]}', token=token))["name"] == "Docker smoke"
else:
    state = {"email": f"docker-{uuid4()}@example.com", "password": uuid4().hex}
    token = json.loads(request("/api/auth/register", {
        **state, "displayName": "Docker smoke"
    }))["accessToken"]
    group = json.loads(request("/api/groups", {"name": "Docker smoke", "type": "OTHER"}, token))
    state["group"] = group["id"]
    json.loads(request("/api/dashboard", token=token))
    state_file.write_text(json.dumps(state))
    state_file.chmod(0o600)
print("Container smoke checks passed.")
