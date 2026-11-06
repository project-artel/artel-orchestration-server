#!/usr/bin/env python3
"""Mint an HS256 JWT that artel-orchestration-server accepts.

The server signs its own sessions with `artel.auth.jwt-secret` (HS256) and
validates issuer, audience and expiry only. Anyone holding the secret can mint
an equivalent token, which is how you get an authenticated session without
going through GitHub OAuth.

The `sub` claim must be an existing `app_user.id` — the token proves identity,
the profile is read from the database.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import sys
import time
from pathlib import Path

# Audience per filter chain in SecurityConfig. A token is accepted only by the
# chain whose decoder was built for its audience.
AUDIENCE_DEFAULTS = {
    "home": ("artel-home", "15m"),
    "sdk": ("artel-sdk", "30d"),
    "refresh": ("artel-refresh", "14d"),
    "tracker-setup": ("artel-tracker-setup", "15m"),
}

DEFAULT_ISSUER = "artel-orchestration-server"

# RefreshTokenService.REFRESH_TARGET_CLAIM
REFRESH_TARGET_CLAIM = "for"


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def b64url_decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def parse_duration(text: str) -> int:
    """Seconds from `900`, `15m`, `2h`, `7d`, or ISO-8601 `PT15M` / `P14D`."""
    text = text.strip()
    if re.fullmatch(r"\d+", text):
        return int(text)

    iso = re.fullmatch(
        r"P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?", text, re.IGNORECASE
    )
    if iso and any(iso.groups()):
        days, hours, minutes, seconds = (int(g or 0) for g in iso.groups())
        return days * 86400 + hours * 3600 + minutes * 60 + seconds

    short = re.fullmatch(r"(\d+)([smhd])", text, re.IGNORECASE)
    if short:
        amount, unit = int(short.group(1)), short.group(2).lower()
        return amount * {"s": 1, "m": 60, "h": 3600, "d": 86400}[unit]

    raise argparse.ArgumentTypeError(f"cannot read a duration from {text!r}")


def read_env_file(path: Path) -> dict[str, str]:
    """The `KEY=value` subset that a Spring `.env` actually uses."""
    if not path.is_file():
        return {}
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key.strip()] = value
    return values


def env_file_candidates() -> list[Path]:
    """Where the orchestration server's `.env` can be, from this copy of the skill.

    The skill is installed in several repositories, so the file is searched for
    upward: the orchestration server's own `.env` when installed there (a
    `pom.xml` marks that root), the sibling submodule's when installed in
    another repository of the workspace.
    """
    candidates: list[Path] = []
    for parent in Path(__file__).resolve().parents:
        if (parent / "pom.xml").is_file():
            candidates.append(parent / ".env")
        if (parent / "artel-orchestration-server").is_dir():
            candidates.append(parent / "artel-orchestration-server" / ".env")
    return candidates


def default_env_file() -> Path:
    """The first candidate that carries the secret, else the first to name in an error."""
    candidates = env_file_candidates()
    for candidate in candidates:
        if "ARTEL_JWT_SECRET" in read_env_file(candidate):
            return candidate
    return candidates[0] if candidates else Path(".env")


def resolve_settings(args: argparse.Namespace) -> dict[str, str]:
    env_file = Path(args.env_file) if args.env_file else default_env_file()
    from_file = read_env_file(env_file)

    def pick(name: str, fallback: str | None = None) -> str | None:
        return os.environ.get(name) or from_file.get(name) or fallback

    secret = args.secret or pick("ARTEL_JWT_SECRET")
    if not secret:
        sys.exit(
            f"ARTEL_JWT_SECRET is not set and {env_file} does not carry one.\n"
            "Pass --secret, export ARTEL_JWT_SECRET, or point --env-file at the file that has it."
        )
    if len(secret.encode("utf-8")) < 32:
        sys.exit("ARTEL_JWT_SECRET is shorter than the 32 bytes HS256 requires.")

    # A refresh token names its target audience in a claim, so both strings are
    # resolved whichever audience was asked for.
    resolved = {
        "home": pick("ARTEL_JWT_AUDIENCE", AUDIENCE_DEFAULTS["home"][0]),
        "sdk": pick("ARTEL_JWT_SDK_AUDIENCE", AUDIENCE_DEFAULTS["sdk"][0]),
        "refresh": pick("ARTEL_JWT_REFRESH_AUDIENCE", AUDIENCE_DEFAULTS["refresh"][0]),
        "tracker-setup": pick("ARTEL_JWT_TRACKER_SETUP_AUDIENCE", AUDIENCE_DEFAULTS["tracker-setup"][0]),
    }
    ttl = AUDIENCE_DEFAULTS[args.audience][1]
    if args.audience == "home":
        ttl = pick("ARTEL_JWT_TTL", ttl)
    elif args.audience == "refresh":
        ttl = pick("ARTEL_JWT_REFRESH_TTL", ttl)

    return {
        "secret": secret,
        "issuer": args.issuer or pick("ARTEL_JWT_ISSUER", DEFAULT_ISSUER),
        "audience": args.aud or resolved[args.audience],
        "target_audience": resolved[args.refresh_for],
        "ttl": args.ttl or ttl,
        "cookie_name": cookie_name(args.audience, pick),
        "env_file": str(env_file),
    }


def cookie_name(audience: str, pick) -> str:
    """The cookie the browser carries this token in, empty when it has none."""
    if audience == "home":
        return pick("ARTEL_AUTH_COOKIE", "artel_access_token")
    if audience == "refresh":
        return pick("ARTEL_REFRESH_COOKIE", "artel_refresh_token")
    return ""


def build_claims(args: argparse.Namespace, settings: dict[str, str]) -> dict:
    issued_at = int(time.time())
    claims = {
        "iss": settings["issuer"],
        "aud": [settings["audience"]],
        "iat": issued_at,
        "exp": issued_at + parse_duration(settings["ttl"]),
        "sub": str(args.sub),
    }
    # JwtService puts the display claims on the browser session token only.
    # Nothing on the server reads them, but they keep a decoded token legible.
    if args.audience == "home":
        claims |= {"provider": args.provider, "login": args.login, "name": args.name}
    # RefreshTokenService.verify rejects a refresh token whose `for` claim does not
    # name the audience being refreshed, so a browser refresh token cannot buy an
    # SDK token. Without the claim every refresh call answers 401.
    elif args.audience == "refresh":
        claims[REFRESH_TARGET_CLAIM] = settings["target_audience"]
    # TrackerSetupStateService.verify reads the project out of the state itself —
    # that signature is what stops someone attaching their installation to another
    # project. A state without these two claims is rejected.
    elif args.audience == "tracker-setup":
        if args.project_id is None:
            sys.exit("--audience tracker-setup needs --project-id")
        claims |= {"project_id": str(args.project_id), "provider": args.provider}
    for pair in args.claim:
        key, _, value = pair.partition("=")
        if not _:
            sys.exit(f"--claim needs key=value, got {pair!r}")
        try:
            claims[key] = json.loads(value)
        except json.JSONDecodeError:
            claims[key] = value
    return claims


def sign(claims: dict, secret: str) -> str:
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64url(json.dumps(claims, separators=(",", ":")).encode())
    signing_input = f"{header}.{payload}".encode("ascii")
    signature = hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    return f"{header}.{payload}.{b64url(signature)}"


def decode(token: str) -> None:
    parts = token.split(".")
    if len(parts) != 3:
        sys.exit("that is not a three-part JWT")
    for label, part in (("header", parts[0]), ("payload", parts[1])):
        body = json.loads(b64url_decode(part))
        print(f"{label}: {json.dumps(body, indent=2, ensure_ascii=False)}")
        if label == "payload" and "exp" in body:
            remaining = body["exp"] - int(time.time())
            state = f"{remaining}s left" if remaining > 0 else f"expired {-remaining}s ago"
            print(f"exp: {state}")


LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0"}


def require_local(origin: str) -> None:
    """This tool mints sessions for a local server and nothing else.

    Signing a token needs the server's secret, so pointing it at a deployed host
    would mean holding that host's secret on this machine. Refusing a non-local
    origin keeps the tool from being the reason a deployment secret travels.
    """
    host = origin.split("://", 1)[-1].split("/", 1)[0].rsplit(":", 1)[0]
    if host not in LOCAL_HOSTS:
        sys.exit(
            f"--origin {origin} is not local. This mints sessions for a local server only —\n"
            "a deployed environment's secret must not be on this machine to begin with."
        )


def render(token: str, claims: dict, settings: dict[str, str], fmt: str, origin: str) -> str:
    cookie = settings["cookie_name"]
    if fmt in ("cookie", "browser", "playwright") and not cookie:
        sys.exit(
            f"the {settings['audience']} token is not carried in a cookie — "
            "use --format token or --format header"
        )
    if fmt == "token":
        return token
    if fmt == "header":
        return f"Authorization: Bearer {token}"
    if fmt == "cookie":
        return f"{cookie}={token}"
    if fmt == "curl":
        return f"curl -sS -H 'Authorization: Bearer {token}' {origin}/api/auth/me"
    if fmt == "browser":
        # The server sets this cookie httpOnly, but it only ever reads the value,
        # so a cookie written from the console works the same way.
        return (
            f"document.cookie = '{cookie}={token}; path=/; SameSite=Lax';"
            " location.reload();"
        )
    if fmt == "playwright":
        host = origin.split("://", 1)[-1].split("/", 1)[0].split(":", 1)[0]
        return json.dumps(
            [{"name": cookie, "value": token, "domain": host, "path": "/",
              "httpOnly": False, "secure": origin.startswith("https"), "sameSite": "Lax"}],
            indent=2,
        )
    if fmt == "json":
        body = {"token": token, "claims": claims}
        if cookie:
            body["cookie"] = cookie
        return json.dumps(body, indent=2)
    raise AssertionError(fmt)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Mint a JWT that artel-orchestration-server accepts.",
        epilog="The --sub value must be an existing app_user.id.",
    )
    parser.add_argument("--sub", default="1", help="app_user.id the token speaks for (default: 1)")
    parser.add_argument(
        "--audience", default="home", choices=sorted(AUDIENCE_DEFAULTS),
        help="which filter chain the token is for (default: home)",
    )
    parser.add_argument(
        "--format", default="token",
        choices=["token", "header", "cookie", "curl", "browser", "playwright", "json"],
        help="how to print it (default: token)",
    )
    parser.add_argument("--ttl", help="lifetime, e.g. 15m, 8h, 30d, PT15M (default: per audience)")
    parser.add_argument("--secret", help="signing secret; overrides env and --env-file")
    parser.add_argument("--issuer", help="override the iss claim")
    parser.add_argument("--aud", help="override the audience string outright")
    parser.add_argument("--env-file", help="file to read ARTEL_JWT_* from (default: artel-orchestration-server/.env)")
    parser.add_argument("--origin", default="http://localhost:8080", help="local host used by --format curl/playwright (default: http://localhost:8080)")
    parser.add_argument(
        "--refresh-for", default="home", choices=["home", "sdk"], metavar="TARGET",
        help="which access token a refresh token may buy (default: home)",
    )
    parser.add_argument("--project-id", help="project the tracker-setup state points at")
    parser.add_argument(
        "--provider", default="github",
        help="provider claim; a login provider for home, a TrackerProvider for tracker-setup (default: github)",
    )
    parser.add_argument("--login", default="local-dev", help="login claim (home audience)")
    parser.add_argument("--name", default="Local Dev", help="name claim (home audience)")
    parser.add_argument("--claim", action="append", default=[], metavar="KEY=VALUE",
                        help="add or replace a claim; repeatable")
    parser.add_argument("--decode", metavar="TOKEN", help="print an existing token instead of minting one")
    args = parser.parse_args()

    if args.decode:
        decode(args.decode)
        return

    require_local(args.origin)
    settings = resolve_settings(args)
    claims = build_claims(args, settings)
    token = sign(claims, settings["secret"])
    print(render(token, claims, settings, args.format, args.origin.rstrip("/")))
    if args.format != "json":
        remaining = claims["exp"] - claims["iat"]
        print(f"# aud={claims['aud'][0]} sub={claims['sub']} valid for {remaining}s", file=sys.stderr)


if __name__ == "__main__":
    main()
