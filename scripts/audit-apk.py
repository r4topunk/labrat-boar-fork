#!/usr/bin/env python3
"""Static audit of an Android APK's network surface: "can this APK talk to the network, and what would it talk to?"

Works on any APK (Kotlin or React Native/Hermes), not only BOAR's. Needs python3 (stdlib only) and the
Android build-tools' aapt2; no Java. Same APK + same tool version => byte-identical report, so anyone can
reproduce a published result.

    scripts/audit-apk.py app.apk [more.apk ...] --out dist/offline-proof/
    scripts/audit-apk.py app.apk                      # markdown to stdout
    scripts/audit-apk.py app.apk --expect-signer <cert sha256>

Exit code: 0 = every APK is NO-NETWORK-BY-CONSTRUCTION, 1 = at least one is not, 2 = tool error,
3 = a signer check failed (--require-release-key or --expect-signer).
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

TOOL_VERSION = "0.2.0"

# ---------------------------------------------------------------- signatures

NETWORK_PERMS = {
    "android.permission.INTERNET": "open network sockets (kernel-enforced via the inet group)",
    "android.permission.ACCESS_NETWORK_STATE": "read connectivity state",
    "android.permission.ACCESS_WIFI_STATE": "read Wi-Fi state (SSID/BSSID can locate the user)",
    "android.permission.CHANGE_NETWORK_STATE": "change connectivity",
    "android.permission.CHANGE_WIFI_STATE": "change Wi-Fi",
    "android.permission.NEARBY_WIFI_DEVICES": "Wi-Fi Direct / nearby devices",
    "android.permission.BLUETOOTH_CONNECT": "Bluetooth link",
    "android.permission.BLUETOOTH_SCAN": "Bluetooth scan",
    "android.permission.BLUETOOTH_ADVERTISE": "Bluetooth advertise",
    "android.permission.BLUETOOTH": "Bluetooth (legacy)",
    "android.permission.BLUETOOTH_ADMIN": "Bluetooth admin (legacy)",
    "android.permission.NFC": "NFC",
}
SENSITIVE_PERMS = {
    "android.permission.ACCESS_FINE_LOCATION": "precise location",
    "android.permission.ACCESS_COARSE_LOCATION": "approximate location",
    "android.permission.ACCESS_BACKGROUND_LOCATION": "location in background",
    "android.permission.RECORD_AUDIO": "microphone",
    "android.permission.CAMERA": "camera",
    "android.permission.READ_CONTACTS": "contacts",
    "android.permission.READ_EXTERNAL_STORAGE": "shared storage read",
    "android.permission.WRITE_EXTERNAL_STORAGE": "shared storage write",
    "android.permission.MANAGE_EXTERNAL_STORAGE": "all-files access (shared storage is readable by other apps)",
    "android.permission.QUERY_ALL_PACKAGES": "enumerate installed apps",
    "android.permission.SYSTEM_ALERT_WINDOW": "draw over other apps",
    "android.permission.READ_PHONE_STATE": "phone identity",
    "android.permission.POST_NOTIFICATIONS": "notifications",
}

# Class-prefix signatures (dex type descriptors, dotted). Subset modelled on the
# Exodus Privacy tracker list: https://reports.exodus-privacy.eu.org/en/trackers/
TRACKERS = {
    "Google Firebase Analytics": ["com.google.firebase.analytics"],
    "Google Crashlytics": ["com.google.firebase.crashlytics", "com.crashlytics"],
    "Google Analytics": ["com.google.android.gms.analytics"],
    "Google AdMob": ["com.google.android.gms.ads"],
    "Firebase transport (datatransport)": ["com.google.android.datatransport"],
    "Facebook SDK (analytics/ads/login)": ["com.facebook.appevents", "com.facebook.ads", "com.facebook.login", "com.facebook.FacebookSdk"],
    "Sentry": ["io.sentry"],
    "Bugsnag": ["com.bugsnag"],
    "Amplitude": ["com.amplitude"],
    "Mixpanel": ["com.mixpanel"],
    "Segment": ["com.segment.analytics"],
    "AppsFlyer": ["com.appsflyer"],
    "Adjust": ["com.adjust.sdk"],
    "Branch": ["io.branch"],
    "OneSignal": ["com.onesignal"],
    "Datadog": ["com.datadog"],
    "New Relic": ["com.newrelic"],
    "Instabug": ["com.instabug"],
    "PostHog": ["com.posthog"],
    "Microsoft App Center": ["com.microsoft.appcenter"],
    "Unity Ads": ["com.unity3d.ads"],
    "AppLovin": ["com.applovin"],
}
GMS = {
    "Google Play Services": ["com.google.android.gms"],
    "Firebase": ["com.google.firebase"],
    "Google ML Kit": ["com.google.mlkit"],
    "Play Core / in-app updates": ["com.google.android.play.core", "com.google.android.play"],
    "Play Install Referrer": ["com.android.installreferrer"],
    "Play Billing / vending": ["com.android.vending", "com.android.billingclient"],
}
REMOTE_CODE = {
    "expo-updates (OTA JS)": ["expo.modules.updates."],
    "CodePush (OTA JS)": ["com.microsoft.codepush"],
    "Expo dev launcher (loads JS from a URL)": ["expo.modules.devlauncher"],
}
NET_LIBS = {
    "OkHttp": ["okhttp3"],
    "Retrofit": ["retrofit2"],
    "Ktor client": ["io.ktor.client"],
    "Volley": ["com.android.volley"],
    "Cronet": ["org.chromium.net"],
    "java.net.HttpURLConnection": ["java.net.HttpURLConnection"],
    "javax.net.ssl": ["javax.net.ssl.HttpsURLConnection"],
    "java.net.Socket": ["java.net.Socket"],
    "android.webkit.WebView": ["android.webkit.WebView"],
    "android.app.DownloadManager": ["android.app.DownloadManager"],
    "React Native networking": ["com.facebook.react.modules.network"],
    "React Native WebSocket": ["com.facebook.react.modules.websocket"],
}
NATIVE_NET_IMPORTS = [
    "socket", "connect", "getaddrinfo", "gethostbyname", "sendto", "sendmsg", "bind", "listen", "accept",
    "curl_easy_init", "curl_easy_perform", "SSL_connect", "SSL_CTX_new", "BIO_new_connect",
]
# Bare domains worth flagging even without a scheme (built-at-runtime URLs, SDK configs).
WATCH_DOMAINS = [
    "supabase.co", "supabase.in", "firebaseio.com", "googleapis.com", "google-analytics.com", "app-measurement.com",
    "crashlytics.com", "sentry.io", "posthog.com", "amplitude.com", "mixpanel.com", "segment.io", "appsflyer.com",
    "adjust.com", "branch.io", "onesignal.com", "datadoghq.com", "bugsnag.com", "u.expo.dev", "exp.host",
    "huggingface.co", "hf.co", "githubusercontent.com", "ipfs.io", "eth.limo", "infura.io", "alchemy.com",
]
# Hosts that appear as XML namespaces / licence / doc links and are never dereferenced by runtime code paths
# we care about. They are still listed, only classified.
NAMESPACE_HOSTS = {
    "schemas.android.com", "www.w3.org", "w3.org", "ns.adobe.com", "xmlpull.org", "www.xmlpull.org", "xml.org",
    "xml.apache.org", "www.apache.org", "apache.org", "purl.org", "schemas.microsoft.com", "schemas.openxmlformats.org",
    "json-schema.org", "www.xfa.org", "www.iec.ch", "www.color.org", "iptc.org", "ns.useplus.org", "www.aiim.org",
    "www.npes.org", "cipa.jp", "www.bouncycastle.org",
}
DOC_HOSTS = {
    "github.com", "developer.android.com", "reactnative.dev", "react.dev", "fb.me", "docs.expo.dev", "expo.fyi",
    "reactnavigation.org", "docs.swmansion.com", "goo.gl", "g.co", "d.android.com", "issuetracker.google.com",
    "kotlinlang.org", "www.gnu.org", "opensource.org", "stackoverflow.com", "developer.mozilla.org", "tools.ietf.org",
    "www.rfc-editor.org", "unicode.org", "www.unicode.org", "react.i18next.com", "dev.to",
}
# Signer certs that are public (anyone holding the repo template can sign "updates" with them).
PUBLIC_DEBUG_CERTS = {
    "fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c":
        "React Native / Expo prebuild template debug.keystore (SHA-1 5E:8F:16:06:2E:A3:CD:2C:...)",
}
LOCAL_HOSTS = {"localhost", "127.0.0.1", "10.0.2.2", "0.0.0.0", "[::1]"}

URL_RE = re.compile(rb"(?:https?|wss?|ftp)://[A-Za-z0-9._~\-]+(?::[0-9]{1,5})?(?:/[\x21\x23-\x26\x28\x2a-\x3b\x3d\x3f-\x5b\x5d\x5f\x61-\x7a\x7e]*)?")
URL_RE_S = re.compile(r"(?:https?|wss?|ftp)://[A-Za-z0-9._~\-]+(?::[0-9]{1,5})?(?:/[^\s\"'<>\\`)\]}|^]*)?")
IPV4_RE = re.compile(rb"(?<![0-9.])(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?![0-9.])")

# ---------------------------------------------------------------- helpers


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def find_aapt2(explicit: str | None) -> str:
    if explicit:
        return explicit
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
             str(Path.home() / "Library/Android/sdk"), str(Path.home() / "Android/Sdk"),
             "/opt/homebrew/share/android-commandlinetools"]
    found = []
    for r in filter(None, roots):
        bt = Path(r) / "build-tools"
        if bt.is_dir():
            for d in bt.iterdir():
                if (d / "aapt2").exists():
                    found.append(d / "aapt2")
    if not found:
        print("aapt2 not found: install Android build-tools or pass --aapt2 PATH", file=sys.stderr)
        sys.exit(2)

    def ver(p):
        return [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.parent.name)]
    return str(sorted(found, key=ver)[-1])


def host_of(url: str) -> str:
    m = re.match(r"^[a-z]+://([^/:?#]+)", url)
    return (m.group(1) if m else url).lower()


HOST_OK = re.compile(r"^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,24}$")


def classify_host(h: str) -> str:
    if not HOST_OK.match(h) and h not in LOCAL_HOSTS and not re.match(r"^\d+\.\d+\.\d+\.\d+$", h):
        return "junk"  # not a syntactically valid hostname (e.g. text fragments like http://www.style)
    if h in LOCAL_HOSTS:
        return "local/dev"
    if h in NAMESPACE_HOSTS:
        return "namespace"
    if h in DOC_HOSTS:
        return "doc-link"
    return "review"


def prefix_hits(names: set[str], sigs: dict[str, list[str]]) -> dict[str, list[str]]:
    out = {}
    for label, prefixes in sigs.items():
        hits = sorted({n for n in names for p in prefixes if n == p or n.startswith(p if p.endswith(".") else p + ".")
                       or n.startswith(p + "$")})
        if hits:
            out[label] = hits[:5] + ([f"... +{len(hits) - 5} more"] if len(hits) > 5 else [])
    return out

# ---------------------------------------------------------------- manifest (aapt2 xmltree)


def parse_manifest(aapt2: str, apk: Path):
    txt = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)],
                         capture_output=True, text=True, check=True).stdout
    root = {"tag": "#root", "attrs": {}, "kids": []}
    stack = [(-1, root)]
    for line in txt.splitlines():
        ind = len(line) - len(line.lstrip())
        s = line.strip()
        if s.startswith("E: "):
            node = {"tag": s[3:].split(" ")[0], "attrs": {}, "kids": []}
            while stack[-1][0] >= ind:
                stack.pop()
            stack[-1][1]["kids"].append(node)
            stack.append((ind, node))
        elif s.startswith("A: "):
            m = re.match(r"A: (?:http://schemas\.android\.com/apk/res/android:)?([A-Za-z]+)(?:\(0x[0-9a-f]+\))?=(.*)$", s)
            if m:
                val = m.group(2)
                q = re.match(r'^"(.*?)"', val)
                stack[-1][1]["attrs"][m.group(1)] = q.group(1) if q else val.split(" ")[0]
    return root


def walk(node, tag=None):
    for k in node["kids"]:
        if tag is None or k["tag"] == tag:
            yield k
        yield from walk(k, tag)


def manifest_facts(root):
    man = next(walk(root, "manifest"))
    app = next(walk(man, "application"), {"attrs": {}, "kids": []})
    perms = sorted({n["attrs"].get("name", "") for n in walk(man) if n["tag"] in ("uses-permission", "uses-permission-sdk-23")})
    uses_sdk = next(walk(man, "uses-sdk"), {"attrs": {}})["attrs"]
    comps = []
    for n in walk(app):
        if n["tag"] in ("activity", "activity-alias", "service", "receiver", "provider"):
            has_filter = any(True for _ in walk(n, "intent-filter"))
            exported = n["attrs"].get("exported")
            if exported is None:  # pre-S default: exported iff it has an intent-filter
                exported = "true (implicit)" if has_filter else "false"
            if exported.startswith("true") or exported.startswith("0xffffffff") or exported == "-1":
                schemes = sorted({d["attrs"].get("scheme") for d in walk(n, "data") if d["attrs"].get("scheme")})
                comps.append({"type": n["tag"], "name": n["attrs"].get("name", ""), "exported": exported,
                              "permission": n["attrs"].get("permission", ""), "schemes": schemes})
    queries = []
    for q in walk(man, "queries"):
        for it in q["kids"]:
            if it["tag"] == "intent":
                act = [a["attrs"].get("name", "") for a in walk(it, "action")]
                data = [d["attrs"].get("scheme") or d["attrs"].get("mimeType") or "" for d in walk(it, "data")]
                queries.append(" ".join(act + data))
            elif it["tag"] == "package":
                queries.append("package:" + it["attrs"].get("name", ""))
    meta = sorted(f'{m["attrs"].get("name", "")}={m["attrs"].get("value", m["attrs"].get("resource", ""))}'
                  for m in walk(app, "meta-data"))
    libs = sorted(f'{u["attrs"].get("name", "")} (required={u["attrs"].get("required", "true")})' for u in walk(app, "uses-library"))
    a = app["attrs"]
    return {
        "package": man["attrs"].get("package", ""),
        "versionName": man["attrs"].get("versionName", ""),
        "versionCode": man["attrs"].get("versionCode", ""),
        "minSdk": uses_sdk.get("minSdkVersion", ""), "targetSdk": uses_sdk.get("targetSdkVersion", ""),
        "sharedUserId": man["attrs"].get("sharedUserId", ""),
        "permissions": perms,
        "application": {k: a.get(k, "<unset>") for k in ("allowBackup", "fullBackupContent", "dataExtractionRules",
                                                        "usesCleartextTraffic", "networkSecurityConfig", "debuggable",
                                                        "extractNativeLibs")},
        "exported_components": comps,
        "queries": sorted(set(queries)),
        "meta_data": meta,
        "uses_library": libs,
    }

# ---------------------------------------------------------------- DEX


def uleb(b, o):
    r = s = 0
    while True:
        x = b[o]; o += 1
        r |= (x & 0x7F) << s
        if x < 0x80:
            return r, o
        s += 7


def dex_strings_and_types(b: bytes):
    n_str, off_str, n_typ, off_typ = struct.unpack_from("<IIII", b, 0x38)
    strs = []
    for i in range(n_str):
        (so,) = struct.unpack_from("<I", b, off_str + 4 * i)
        _, o = uleb(b, so)
        e = b.index(b"\0", o)
        strs.append(b[o:e].decode("utf-8", "replace"))
    types = set()
    for i in range(n_typ):
        (si,) = struct.unpack_from("<I", b, off_typ + 4 * i)
        t = strs[si]
        if t.startswith("L") and t.endswith(";"):
            types.add(t[1:-1].replace("/", "."))
    return strs, types

# ---------------------------------------------------------------- Hermes bytecode


def hermes_strings(b: bytes):
    """Decode the string table of a Hermes bytecode bundle (RN). Returns None if not HBC/unparseable.

    Anchors on the run-length string-kind table (its counts sum to stringCount), which makes it
    robust to header/function-header size changes across HBC versions."""
    if len(b) < 128 or struct.unpack_from("<Q", b, 0)[0] != 0x1F1903C103BC1FC6:
        return None
    version = struct.unpack_from("<I", b, 8)[0]
    f = struct.unpack_from("<8I", b, 32)
    fc, skc, idc, sc, osc, ssz = f[2], f[3], f[4], f[5], f[6], f[7]
    al = lambda x: (x + 3) & ~3
    anchor = None
    for o in range(96, min(len(b) - 4 * skc, 128 + fc * 64), 4):
        v = struct.unpack_from("<%dI" % skc, b, o)
        if sum(x & 0x7FFFFFFF for x in v) == sc and all(x & 0x7FFFFFFF for x in v):
            anchor = o
            break
    if anchor is None:
        return None
    sst = al(al(anchor + skc * 4) + idc * 4)
    ovt = al(sst + sc * 4)
    stor = al(ovt + osc * 8)
    if stor + ssz > len(b):
        return None
    out = []
    for i in range(sc):
        e = struct.unpack_from("<I", b, sst + 4 * i)[0]
        u, off, ln = e & 1, (e >> 1) & 0x7FFFFF, e >> 24
        if ln == 255:
            off, ln = struct.unpack_from("<II", b, ovt + 8 * off)
        s = b[stor + off: stor + off + (2 * ln if u else ln)]
        out.append(s.decode("utf-16le" if u else "latin-1", "replace"))
    printable = sum(1 for s in out if s.isprintable()) / max(1, len(out))
    return {"version": version, "strings": out, "printable_ratio": round(printable, 4)}

# ---------------------------------------------------------------- ELF


def elf_imports(b: bytes):
    if b[:4] != b"\x7fELF" or b[4] != 2 or b[5] != 1:
        return None
    shoff = struct.unpack_from("<Q", b, 0x28)[0]
    shentsize, shnum, _ = struct.unpack_from("<HHH", b, 0x3A)
    secs = [struct.unpack_from("<IIQQQQIIQQ", b, shoff + i * shentsize) for i in range(shnum)]
    imports, needed = set(), []
    for s in secs:
        if s[1] == 11:  # SHT_DYNSYM
            strtab = secs[s[6]]
            so, ss = strtab[4], strtab[5]
            for i in range(s[5] // 24):
                name_off, info, other, shndx = struct.unpack_from("<IBBH", b, s[4] + 24 * i)
                if shndx == 0 and name_off:
                    e = b.index(b"\0", so + name_off)
                    imports.add(b[so + name_off:e].decode("latin-1"))
        if s[1] == 6:  # SHT_DYNAMIC
            strtab = secs[s[6]]
            for i in range(s[5] // 16):
                tag, val = struct.unpack_from("<qQ", b, s[4] + 16 * i)
                if tag == 1:
                    o = strtab[4] + val
                    needed.append(b[o:b.index(b"\0", o)].decode("latin-1"))
                if tag == 0:
                    break
    return {"imports": imports, "needed": needed}

# ---------------------------------------------------------------- signing block (v2/v3), no Java


def signing_info(apk: Path):
    data = apk.read_bytes()
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        return {"error": "no EOCD"}
    cd_off = struct.unpack_from("<I", data, eocd + 16)[0]
    info = {"v1_jar": any(n.startswith("META-INF/") and n.endswith((".RSA", ".DSA", ".EC"))
                          for n in zipfile.ZipFile(io.BytesIO(data)).namelist()),
            "v2": False, "v3": False, "cert_sha256": [], "debug_cert": False, "subject": ""}
    if data[cd_off - 16:cd_off] != b"APK Sig Block 42":
        return info
    size = struct.unpack_from("<Q", data, cd_off - 24)[0]
    start = cd_off - size - 8
    o, end = start + 8, cd_off - 24
    ids = {0x7109871A: "v2", 0xF05368C0: "v3"}
    while o < end:
        ln, pid = struct.unpack_from("<QI", data, o)
        val = data[o + 12:o + 8 + ln]
        if pid in ids:
            info[ids[pid]] = True
            try:
                certs = _certs_from_scheme_block(val)
                for c in certs:
                    d = hashlib.sha256(c).hexdigest()
                    if d not in info["cert_sha256"]:
                        info["cert_sha256"].append(d)
                    if b"Android Debug" in c:
                        info["debug_cert"] = True
                    info["subject"] = info["subject"] or _subject(c)
            except Exception as e:  # noqa: BLE001
                info["parse_error"] = str(e)
        o += 8 + ln
    return info


def _subject(der: bytes) -> str:
    """O and CN of the subject (last occurrence = subject; issuer comes first in TBSCertificate)."""
    out = {}
    for name, oid in (("O", b"\x06\x03\x55\x04\x0a"), ("CN", b"\x06\x03\x55\x04\x03")):
        i = der.rfind(oid)
        if i >= 0:
            j = i + len(oid)
            ln = der[j + 1]
            out[name] = der[j + 2:j + 2 + ln].decode("utf-8", "replace")
    return ", ".join(f"{k}={v}" for k, v in out.items())


def _lp(b, o):
    (n,) = struct.unpack_from("<I", b, o)
    return b[o + 4:o + 4 + n], o + 4 + n


def _certs_from_scheme_block(val):
    signers, _ = _lp(val, 0)
    certs, o = [], 0
    while o < len(signers):
        signer, o = _lp(signers, o)
        signed_data, _ = _lp(signer, 0)
        _digests, p = _lp(signed_data, 0)
        cert_seq, _ = _lp(signed_data, p)
        q = 0
        while q < len(cert_seq):
            c, q = _lp(cert_seq, q)
            certs.append(c)
    return certs

# ---------------------------------------------------------------- main audit


def audit(apk: Path, aapt2: str) -> dict:
    zf = zipfile.ZipFile(apk)
    names = sorted(zf.namelist())
    mf = manifest_facts(parse_manifest(aapt2, apk))
    perms = set(mf["permissions"])

    dex_types: set[str] = set()
    url_sources: dict[str, set[str]] = {}
    watch_hits: dict[str, set[str]] = {}
    ip_hits: dict[str, set[str]] = {}
    native = {}
    hermes = None

    def add_urls(src: str, urls):
        for u in urls:
            u = u.rstrip(".,;:'\"")
            if len(u) > 12:
                url_sources.setdefault(u, set()).add(src)

    def add_watch(src: str, blob: bytes):
        low = blob.lower()
        for d in WATCH_DOMAINS:
            if d.encode() in low:
                watch_hits.setdefault(d, set()).add(src)

    for n in names:
        if n.endswith("/"):
            continue
        b = zf.read(n)
        if re.fullmatch(r"classes\d*\.dex", n):
            strs, types = dex_strings_and_types(b)
            dex_types |= types
            strs = [x for x in strs if len(x) <= 4096]  # skip data blobs (e.g. the Brotli static dictionary)
            add_urls(n, (m for s in strs for m in URL_RE_S.findall(s)))
            add_watch(n, "\n".join(strs).encode("utf-8", "replace"))
            for s in strs:
                for m in IPV4_RE.findall(s.encode()):
                    ip_hits.setdefault(m.decode(), set()).add(n)
        elif n.endswith(".so"):
            ei = elf_imports(b)
            if ei:
                net = sorted(i for i in ei["imports"] if i in NATIVE_NET_IMPORTS)
                native[n] = {"network_imports": net,
                             "needed": sorted(x for x in ei["needed"] if re.search(r"curl|ssl|crypto|http|net", x))}
            add_urls(n, (m.decode("latin-1") for m in URL_RE.findall(b)))
            add_watch(n, b)
        elif n.startswith("assets/") and b[:8] == struct.pack("<Q", 0x1F1903C103BC1FC6):
            hermes = hermes_strings(b)
            if hermes:
                add_urls(n, (m for s in hermes["strings"] for m in URL_RE_S.findall(s)))
                add_watch(n, "\n".join(hermes["strings"]).encode("utf-8", "replace"))
            else:
                add_urls(n, (m.decode("latin-1") for m in URL_RE.findall(b)))
                add_watch(n, b)
        elif n.startswith(("assets/", "res/raw/")) or n.endswith((".json", ".xml", ".properties", ".txt", ".js", ".config")):
            if n.startswith("res/") and not n.startswith("res/raw/"):
                continue  # binary XML / images: covered by aapt2
            add_urls(n, (m.decode("latin-1") for m in URL_RE.findall(b)))
            add_watch(n, b)

    hosts: dict[str, dict] = {}
    for u, srcs in url_sources.items():
        h = host_of(u)
        cls = classify_host(h)
        if cls == "doc-link" and re.search(r"/releases/download/|/raw/|\.(gguf|sqlite|bin|zip|json)$", u):
            cls = "review"  # an asset download, not a doc link
        key = h if cls != "review" or classify_host(h) == "review" else h + " (downloads)"
        e = hosts.setdefault(key, {"class": cls, "count": 0, "sources": set(), "examples": []})
        e["count"] += 1
        e["sources"] |= {s.split("/")[0] if s.startswith("res/") else s for s in srcs}
        if len(e["examples"]) < 3:
            e["examples"].append(u[:160])
    for e in hosts.values():
        e["sources"] = sorted(e["sources"])
        e["examples"] = sorted(e["examples"])

    trackers = prefix_hits(dex_types, TRACKERS)
    gms = prefix_hits(dex_types, GMS)
    remote_code = prefix_hits(dex_types, REMOTE_CODE)
    net_libs = prefix_hits(dex_types, NET_LIBS)
    has_internet = "android.permission.INTERNET" in perms

    indirect = []
    q = " | ".join(mf["queries"])
    if "android.intent.action.VIEW" in q and ("https" in q or "http" in q):
        indirect.append("Declares <queries> for VIEW http(s): can hand a URL to a browser (user-visible; URL could carry data).")
    if "android.intent.action.SEND" in q:
        indirect.append("Declares <queries> for SEND: can open the share sheet (user-mediated export).")
    if gms:
        indirect.append("Google Play Services client code present: GMS runs in another process that HAS network; "
                        "binder calls can cause egress without this app holding INTERNET.")
    if "android.permission.MANAGE_EXTERNAL_STORAGE" in perms or "android.permission.WRITE_EXTERNAL_STORAGE" in perms:
        indirect.append("Shared-storage access: files placed there (models, corpora, exports) are readable/writable by "
                        "other apps that may have INTERNET (exfiltration or tampering by a colluding app).")
    if mf["sharedUserId"]:
        indirect.append(f"sharedUserId={mf['sharedUserId']}: shares a UID (and permissions) with other apps.")
    for c in mf["exported_components"]:
        if c["type"] != "activity" or c["schemes"]:
            indirect.append(f"Exported {c['type']} {c['name']} schemes={c['schemes'] or '-'} permission={c['permission'] or '-'}")

    sig = signing_info(apk)
    findings = []
    for c in sig.get("cert_sha256", []):
        if c in PUBLIC_DEBUG_CERTS:
            findings.append(f"HIGH if distributed / MEDIUM for local test builds: signed with a PUBLIC key ({PUBLIC_DEBUG_CERTS[c]}). "
                            "Anyone can sign an APK with the same package name that Android accepts as an update over this one, "
                            "inheriting the app's private data. Never ship it; check the distributed APK's signer.")
    if sig.get("debug_cert") and not any(c in PUBLIC_DEBUG_CERTS for c in sig.get("cert_sha256", [])):
        findings.append("MEDIUM: signer certificate is an Android debug certificate.")
    if not (sig.get("v2") or sig.get("v3")):
        findings.append("MEDIUM: no v2/v3 signature block.")
    if has_internet:
        findings.append("HIGH: declares INTERNET; every `review` host below needs a justification and a dynamic test.")
    if trackers:
        findings.append("HIGH: tracker SDK classes present: " + ", ".join(trackers))
    if gms:
        findings.append("MEDIUM: Google Play Services/Firebase client code present: " + ", ".join(gms))
    full_launcher = any(t.startswith(("expo.modules.devlauncher.launcher.", "expo.modules.devlauncher.DevLauncherController"))
                        for t in dex_types)
    for label in remote_code:
        if label.startswith("Expo dev launcher") and not full_launcher:
            n = sum(1 for t in dex_types if t.startswith("expo.modules.devlauncher."))
            findings.append(f"INFO: Expo dev-launcher module present as a {n}-class stub (no DevLauncherController/launcher "
                            "package): consistent with the release no-op variant.")
        else:
            findings.append(f"MEDIUM: remote-code-loading code present ({label}); inert without INTERNET, "
                            "but should not ship in a release build.")
    ab, der = mf["application"].get("allowBackup"), mf["application"].get("dataExtractionRules")
    if ab not in ("false", "0x0") and der == "<unset>":
        findings.append("MEDIUM: allowBackup is not false and no dataExtractionRules: chats/indexes can leave via cloud backup "
                        "or device-to-device transfer.")
    elif ab not in ("false", "0x0"):
        findings.append("LOW: allowBackup not false; relies on dataExtractionRules (Android 12+) and fullBackupContent "
                        f"({mf['application'].get('fullBackupContent')}) for Android <=11. Check the rules exclude app data.")
    if mf["application"].get("debuggable") in ("true", "0xffffffff"):
        findings.append("HIGH: android:debuggable=true.")
    if any("MANAGE_EXTERNAL_STORAGE" in p or "WRITE_EXTERNAL_STORAGE" in p for p in perms):
        findings.append("MEDIUM: shared-storage write access: model/corpus files there can be tampered with by other apps.")
    if not has_internet and not trackers and not gms:
        verdict = "NO-NETWORK-BY-CONSTRUCTION"
    elif not has_internet:
        verdict = "NO-INTERNET-PERMISSION but GMS/tracker code present (proxy/dead-code risk)"
    elif trackers:
        verdict = "NETWORK-CAPABLE + TRACKERS"
    else:
        verdict = "NETWORK-CAPABLE"

    return {
        "tool": {"name": "scripts/audit-apk.py", "version": TOOL_VERSION},
        "apk": {"file": apk.name, "bytes": apk.stat().st_size, "sha256": sha256_file(apk), "entries": len(names)},
        "signing": sig,
        "findings": findings,
        "manifest": mf,
        "verdict": verdict,
        "network_permissions": {p: NETWORK_PERMS[p] for p in sorted(perms) if p in NETWORK_PERMS},
        "sensitive_permissions": {p: SENSITIVE_PERMS[p] for p in sorted(perms) if p in SENSITIVE_PERMS},
        "gms": gms,
        "trackers": trackers,
        "remote_code_loading": remote_code,
        "network_libraries_present": net_libs,
        "native_network_imports": {k: v for k, v in sorted(native.items()) if v["network_imports"] or v["needed"]},
        "hermes": {"version": hermes["version"], "strings": len(hermes["strings"]),
                   "printable_ratio": hermes["printable_ratio"]} if hermes else None,
        "hosts": dict(sorted(hosts.items(), key=lambda kv: (kv[1]["class"] != "review", kv[0]))),
        "watch_domains": {d: sorted(s) for d, s in sorted(watch_hits.items())},
        "ipv4_literals": {ip: sorted(s) for ip, s in sorted(ip_hits.items()) if int(ip.split(".")[0]) > 3},  # 0-3.x = ASN.1 OIDs
        "indirect_channels": indirect,
    }

# ---------------------------------------------------------------- rendering


def md(r: dict) -> str:
    a, m, s = r["apk"], r["manifest"], r["signing"]
    L = []
    w = L.append
    w(f"# offline-proof report: `{a['file']}`\n")
    w(f"TL;DR: **{r['verdict']}**. INTERNET={'yes' if 'android.permission.INTERNET' in m['permissions'] else 'no'}, "
      f"GMS={'yes' if r['gms'] else 'no'}, trackers={len(r['trackers'])}, "
      f"hosts to review={sum(1 for h in r['hosts'].values() if h['class'] == 'review')}.\n")
    w("## Findings\n")
    for f in r["findings"] or ["none above INFO level"]:
        w(f"- {f}")
    w("")
    w("## Identity\n")
    w("| field | value |\n|---|---|")
    w(f"| package | `{m['package']}` {m['versionName']} ({m['versionCode']}) |")
    w(f"| sha256 | `{a['sha256']}` |")
    w(f"| size | {a['bytes']:,} bytes, {a['entries']} entries |")
    w(f"| sdk | min {m['minSdk']} / target {m['targetSdk']} |")
    w(f"| signing | v1={s.get('v1_jar')} v2={s.get('v2')} v3={s.get('v3')} debug-cert={s.get('debug_cert')} subject=`{s.get('subject')}` |")
    for c in s.get("cert_sha256", []):
        w(f"| signer cert sha256 | `{c}` |")
    w(f"| tool | {r['tool']['name']} {r['tool']['version']} (signing block parsed, signature NOT cryptographically verified: use `apksigner verify`) |\n")

    w("## 1. Permissions\n")
    w("All declared: " + (", ".join(f"`{p.replace('android.permission.', '')}`" for p in m["permissions"]) or "none") + "\n")
    w("Network-related: " + (", ".join(f"`{k}` ({v})" for k, v in r["network_permissions"].items()) or "**none**") + "\n")
    w("Sensitive: " + (", ".join(f"`{k.replace('android.permission.', '')}` ({v})" for k, v in r["sensitive_permissions"].items()) or "none") + "\n")

    w("## 2. Manifest flags\n")
    w("| flag | value |\n|---|---|")
    for k, v in m["application"].items():
        w(f"| {k} | `{v}` |")
    w(f"| sharedUserId | `{m['sharedUserId'] or '<none>'}` |")
    w(f"| uses-library | {', '.join(m['uses_library']) or '<none>'} |\n")
    if m["queries"]:
        w("`<queries>`: " + "; ".join(f"`{q}`" for q in m["queries"]) + "\n")
    if m["meta_data"]:
        w("<details><summary>meta-data (" + str(len(m["meta_data"])) + ")</summary>\n")
        for x in m["meta_data"]:
            w(f"- `{x}`")
        w("\n</details>\n")

    def block(title, d):
        w(f"## {title}\n")
        if not d:
            w("none found\n")
            return
        for k, v in d.items():
            w(f"- **{k}**: " + ", ".join(f"`{x}`" for x in v))
        w("")

    block("3. Google Play Services / Firebase", r["gms"])
    block("4. Tracker / analytics SDKs (Exodus-style class signatures)", r["trackers"])
    block("5. Remote code loading (OTA / dev launcher)", r["remote_code_loading"])
    block("6. Network-capable libraries present (capability, not proof of use)", r["network_libraries_present"])

    w("## 7. Native libraries with network imports\n")
    if r["native_network_imports"]:
        w("| lib | imported network symbols | linked net/TLS libs |\n|---|---|---|")
        for k, v in r["native_network_imports"].items():
            w(f"| `{k}` | {', '.join(v['network_imports']) or '-'} | {', '.join(v['needed']) or '-'} |")
        w("")
    else:
        w("none\n")

    if r["hermes"]:
        h = r["hermes"]
        w(f"JS bundle: Hermes bytecode v{h['version']}, {h['strings']:,} strings decoded "
          f"({h['printable_ratio']:.1%} printable, parse sanity check).\n")

    w("## 8. Embedded hosts\n")
    w("Classes: `review` = could be contacted at runtime and needs a justification; `doc-link` = error/doc URLs; "
      "`namespace` = XML/schema identifiers; `local/dev` = loopback/dev server. Syntactically invalid hosts "
      "(text fragments) are counted but not listed.\n")
    w("| host | class | urls | where | example |\n|---|---|---|---|---|")
    junk = [h for h, e in r["hosts"].items() if e["class"] == "junk"]
    for h, e in r["hosts"].items():
        if e["class"] == "junk":
            continue
        w(f"| `{h}` | {e['class']} | {e['count']} | {', '.join(e['sources'][:3])}{' …' if len(e['sources']) > 3 else ''} "
          f"| `{e['examples'][0]}` |")
    w(f"\n{len(junk)} invalid host fragments skipped.\n")
    w("Watch-listed domains (substring, catches runtime-built URLs): " +
      (", ".join(f"`{d}` ({', '.join(v[:2])})" for d, v in r["watch_domains"].items()) or "none") + "\n")
    if r["ipv4_literals"]:
        w("IPv4 literals in dex: " + ", ".join(f"`{ip}`" for ip in list(r["ipv4_literals"])[:20]) + "\n")

    w("## 9. Indirect egress channels (work even without INTERNET)\n")
    for x in r["indirect_channels"] or ["none detected"]:
        w(f"- {x}")
    w("")
    w("## Limits of a static audit\n")
    w("- Proves *capability*, not *behaviour*. Without INTERNET the kernel blocks sockets for this UID, which is the strongest "
      "static guarantee; with INTERNET, hosts built at runtime or fetched from config are invisible here.")
    w("- Does not see what other apps (browser, GMS, keyboard, speech service) do with data handed to them.")
    w("- Pair with a dynamic test on a phone (scripts/netstats-uid.sh).")
    return "\n".join(L) + "\n"


def summary_md(results: list[dict]) -> str:
    L = ["# offline-proof summary\n",
         "TL;DR: one row per APK. `NO-NETWORK-BY-CONSTRUCTION` = no INTERNET permission, no GMS, no tracker classes.\n",
         "| apk | package | verdict | INTERNET | net perms | GMS | trackers | remote code | review hosts | sensitive perms | signer | sha256 (12) |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in results:
        m = r["manifest"]
        rv = [h for h, e in r["hosts"].items() if e["class"] == "review"]
        L.append("| `{}` | `{}` | **{}** | {} | {} | {} | {} | {} | {} | {} | {} | `{}` |".format(
            r["apk"]["file"], m["package"], r["verdict"],
            "yes" if "android.permission.INTERNET" in m["permissions"] else "no",
            len(r["network_permissions"]), ", ".join(r["gms"]) or "-", ", ".join(r["trackers"]) or "-",
            (", ".join(r["remote_code_loading"]) + (" (stub)" if any(f.startswith("INFO: Expo dev-launcher") for f in r["findings"]) else "")) or "-",
            f"{len(rv)} ({', '.join(rv[:4])}{'…' if len(rv) > 4 else ''})",
            ", ".join(p.replace("android.permission.", "") for p in r["sensitive_permissions"]) or "-",
            ("**public debug key**" if r["signing"].get("debug_cert") else r["signing"].get("subject") or "?"), r["apk"]["sha256"][:12]))
    return "\n".join(L) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("apks", nargs="+", type=Path)
    ap.add_argument("--out", type=Path, help="write <apk>.md/.json + SUMMARY.md here")
    ap.add_argument("--stdout", action="store_true")
    ap.add_argument("--aapt2")
    ap.add_argument("--require-release-key", action="store_true",
                    help="exit 3 if any APK is signed with an Android debug / known public certificate (release gate)")
    ap.add_argument("--expect-signer", metavar="SHA256",
                    help="exit 3 unless every APK's signer certificate SHA-256 is this one (e.g. the release key's)")
    args = ap.parse_args()
    aapt2 = find_aapt2(args.aapt2)
    results = []
    for apk in args.apks:
        if not apk.is_file():
            print(f"not found: {apk}", file=sys.stderr)
            sys.exit(2)
        r = audit(apk, aapt2)
        results.append(r)
        if args.out:
            args.out.mkdir(parents=True, exist_ok=True)
            stem = apk.name.removesuffix(".apk")
            (args.out / f"{stem}.md").write_text(md(r))
            (args.out / f"{stem}.json").write_text(json.dumps(r, indent=2, sort_keys=True) + "\n")
        if args.stdout or not args.out:
            print(md(r))
        print(f"{apk.name}: {r['verdict']}", file=sys.stderr)
    if args.out:
        (args.out / "SUMMARY.md").write_text(summary_md(results))
    signer_fail = False
    if args.require_release_key:
        bad = [r["apk"]["file"] for r in results if r["signing"].get("debug_cert")
               or any(c in PUBLIC_DEBUG_CERTS for c in r["signing"].get("cert_sha256", []))]
        if bad:
            print("SIGNER FAIL (debug/public signing key): " + ", ".join(bad), file=sys.stderr)
            signer_fail = True
    if args.expect_signer:
        want = args.expect_signer.lower().replace(":", "")
        bad = [r["apk"]["file"] for r in results if r["signing"].get("cert_sha256") != [want]]
        if bad:
            print(f"SIGNER FAIL (not signed only by {want}): " + ", ".join(bad), file=sys.stderr)
            signer_fail = True
    if signer_fail:
        sys.exit(3)
    sys.exit(0 if all(r["verdict"] == "NO-NETWORK-BY-CONSTRUCTION" for r in results) else 1)


if __name__ == "__main__":
    # Exit codes: 0 all offline, 1 a verdict other than offline, 3 signer, 2 the audit itself failed
    # (a crash must never read as "1, network-capable" or leave an old report looking current).
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:  # noqa: BLE001
        print(f"audit failed: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(2)
