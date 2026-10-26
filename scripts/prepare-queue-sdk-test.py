#!/usr/bin/env python3
"""Prepare synthetic passenger B against the loopback-only backend test fixture."""
import argparse
import html
import http.cookiejar
import json
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ORIGIN = "http://127.0.0.1:18081"


class FixtureRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # The browser fixture normally uses the web proxy on 18082. API-only
        # verification sends its callback to the same backend and cookie jar.
        if newurl.startswith("http://127.0.0.1:18082/"):
            newurl = ORIGIN + newurl[len("http://127.0.0.1:18082"):]
        if urllib.parse.urlparse(newurl).hostname != "127.0.0.1":
            raise RuntimeError("Only the local fixture may receive test credentials")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", required=True, type=Path,
                        help="Private majung-browser-vehicle.json from AccountBrowserFixtureTest")
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    capability = json.loads(args.fixture.read_text())
    jar = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), FixtureRedirect())

    def request(path, body=None, csrf=None):
        headers = {"Accept": "application/json"}
        if csrf:
            headers["X-CSRF-TOKEN"] = csrf
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        with client.open(urllib.request.Request(ORIGIN + path, data=data, headers=headers), timeout=10) as response:
            return json.load(response)

    with client.open(ORIGIN + "/api/v1/auth/login", timeout=10) as response:
        page = response.read().decode()
        provider = response.url
    relative = html.unescape(re.search(r"href='([^']*person=three[^']*)'", page).group(1))
    try:
        client.open(urllib.parse.urljoin(provider, relative), timeout=10).close()
    except urllib.error.HTTPError as error:
        if error.code != 404 or urllib.parse.urlparse(error.url).path != "/account":
            raise
    session = request("/api/v1/auth/session")
    assert session["authenticated"] and session["member"]["nickname"] == "가상탑승자B"
    assert request("/api/v1/connections") == [], "Passenger must have no previous driver connection"
    drivers = request("/api/v1/accounts/search?query=" + urllib.parse.quote("가상운전자"))
    assert len(drivers) == 1
    import uuid
    submitted = request("/api/v1/ride-requests", {
        "driverId": drivers[0]["memberId"], "requestId": str(uuid.uuid4()),
        "place": {"label": "SDK 시험 장소", "latitude": 37.48544722, "longitude": 127.03636666}
    }, session["csrfToken"])
    assert submitted["status"] == "PENDING"
    capability.update(requestId=submitted["requestId"], csrfToken=session["csrfToken"],
                      cookie="; ".join(f"{cookie.name}={cookie.value}" for cookie in jar))
    user = subprocess.check_output([args.adb, "shell", "am", "get-current-user"], text=True).strip()
    subprocess.run([args.adb, "reverse", "tcp:18081", "tcp:18081"], check=True, stdout=subprocess.DEVNULL)
    subprocess.run([args.adb, "shell", "run-as", "app.majung.vehicle", "--user", user,
                    "sh", "-c", "'cat > files/queue-sdk-input.json'"],
                   input=json.dumps(capability).encode(), check=True)
    print("Synthetic passenger request prepared; private test input delivered without logging credentials.")


if __name__ == "__main__":
    main()
