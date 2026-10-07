"""Tiny client for the ZTE ZXHN H188A web UI (recon helper; the real client will be Java)."""
import hashlib, http.cookiejar, json, re, ssl, urllib.parse, urllib.request

def load_env(path=".env"):
    env = {}
    for line in open(path):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1); env[k.strip()] = v.strip()
    return env

class Router:
    def __init__(self, base, user, password):
        self.base, self.user, self.password = base.rstrip("/"), user, password
        ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar), urllib.request.HTTPSHandler(context=ctx))
        self.sess_token = ""; self.login_reply = {}

    def get(self, path):
        return self.opener.open(self.base + path, timeout=10).read().decode("utf-8", "ignore")

    def post(self, path, data):
        return self.opener.open(self.base + path, urllib.parse.urlencode(data).encode(), timeout=10).read().decode("utf-8", "ignore")

    def login(self):
        self.get("/")
        state = json.loads(self.get("/?_type=loginData&_tag=login_entry"))
        if str(state.get("lockingTime", "0")) not in ("0", "-1", "") or state.get("loginErrMsg"):
            raise RuntimeError("router locked: %r" % {k: state.get(k) for k in ("loginErrMsg", "lockingTime", "promptMsg")})
        token = re.search(r">([^<]+)<", self.get("/?_type=loginData&_tag=login_token")).group(1)
        digest = hashlib.sha256((self.password + token).encode()).hexdigest()
        reply = json.loads(self.post("/?_type=loginData&_tag=login_entry",
                                     {"Username": self.user, "Password": digest, "action": "login", "_sessionTOKEN": state.get("sess_token", "")}))
        if reply.get("loginErrMsg") or str(reply.get("lockingTime", "0")) not in ("0", "-1", ""):
            raise RuntimeError("login refused: %s (lock %s)" % (reply.get("loginErrMsg"), reply.get("lockingTime")))
        self.login_reply = reply; self.sess_token = reply.get("sess_token", "")
        return reply

    def logout(self):
        page = self.get("/"); m = re.search(r'_sessionTmpToken\s*=\s*"([^"]*)"', page)
        try: self.post("/?_type=loginData&_tag=logout_entry", {"IF_LogOff": 1, "_sessionTOKEN": m.group(1) if m else self.sess_token})
        except Exception: pass
