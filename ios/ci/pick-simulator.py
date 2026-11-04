"""En yeni iOS sürümündeki bir iPhone simülatörünün UDID'sini yazar (tercihen düz "iPhone NN")."""
import json
import re
import subprocess
import sys

data = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "-j"]))["devices"]


def version(runtime):
    m = re.search(r"iOS-(\d+)-(\d+)", runtime)
    return (int(m.group(1)), int(m.group(2))) if m else (0, 0)


runtimes = sorted((r for r in data if "iOS" in r and data[r]), key=version, reverse=True)
for r in runtimes:
    phones = [d for d in data[r] if d["name"].startswith("iPhone")]
    if not phones:
        continue
    plain = [d for d in phones if re.fullmatch(r"iPhone \d+", d["name"])]
    pick = (plain or phones)[0]
    print(pick["udid"])
    print(f"{pick['name']} / {r}", file=sys.stderr)
    sys.exit(0)
sys.exit("iPhone simülatörü bulunamadı")
