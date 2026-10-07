#!/usr/bin/env python3
"""Read-only recon of a ZTE ZXHN web UI. ONE login attempt, GET-only afterwards.

Usage:  python3 probe.py          (reads ROUTER_* from .env, never prints the password)
Output: recon/out/*.txt  (git-ignored)
"""
import hashlib, http.cookiejar, json, os, re, ssl, sys, urllib.parse, urllib.request

def load_env(path=".env"):
    env = {}
    for line in open(path):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1); env[k.strip()] = v.strip()
    return env

env = load_env()
base, user, password = env.get("ROUTER_URL", "").rstrip("/"), env.get("ROUTER_USERNAME", ""), env.get("ROUTER_PASSWORD", "")
if not (base and user and password):
    sys.exit("Fill ROUTER_USERNAME and ROUTER_PASSWORD in .env first (nothing was sent to the router).")

ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE  # self-signed router cert
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), urllib.request.HTTPSHandler(context=ctx))
os.makedirs("recon/out", exist_ok=True)

def get(path): return opener.open(base + path, timeout=10).read().decode("utf-8", "ignore")
def post(path, data): return opener.open(base + path, urllib.parse.urlencode(data).encode(), timeout=10).read().decode("utf-8", "ignore")

home = get("/")
# Step 1 (what the login page does on load): ask for a session token and read the lock state.
state = json.loads(get("/?_type=loginData&_tag=login_entry"))
session_token = state.get("sess_token", "")
if str(state.get("lockingTime", "0")) not in ("0", "-1", "") or state.get("loginErrMsg"):
    sys.exit("Router reports a login lock or error before we tried: %r. STOP; wait and retry later." % {k: state.get(k) for k in ("loginErrMsg", "lockingTime", "promptMsg")})
login_token = re.search(r">([^<]+)<", get("/?_type=loginData&_tag=login_token")).group(1)
digest = hashlib.sha256((password + login_token).encode()).hexdigest()

form = {"Username": user, "Password": digest, "action": "login", "_sessionTOKEN": session_token}
reply = json.loads(post("/?_type=loginData&_tag=login_entry", form))
if reply.get("loginErrMsg") or str(reply.get("lockingTime", "0")) not in ("0", "-1", ""):
    sys.exit("Login refused: %s (lock %ss). STOP and do not retry; check the credentials." % (reply.get("loginErrMsg"), reply.get("lockingTime")))
print("login OK")

page = get("/")
tags = sorted(set(re.findall(r"_type=(?:menuView|menuData|hiddenData)&_tag=([A-Za-z0-9_]+)", page + home)))
open("recon/out/tags.txt", "w").write("\n".join(tags))
print(len(tags), "read-only pages found; saved to recon/out/tags.txt")

interesting = re.compile(r"dev|host|lan|wan|status|stat|traffic|flow|eth|wlan|client", re.I)
for tag in [t for t in tags if interesting.search(t)][:40]:
    for kind in ("menuData", "hiddenData", "menuView"):
        try:
            body = get("/?_type=%s&_tag=%s&_=1" % (kind, tag))
        except Exception:
            continue
        if len(body) > 60:
            open("recon/out/%s_%s.txt" % (kind, tag), "w").write(body)
            print("saved", kind, tag, len(body), "bytes"); break

m = re.search(r'_sessionTmpToken\s*=\s*"([^"]*)"', page)
post("/?_type=loginData&_tag=logout_entry", {"IF_LogOff": 1, "_sessionTOKEN": m.group(1) if m else ""})  # leave the session clean
print("logged out")
