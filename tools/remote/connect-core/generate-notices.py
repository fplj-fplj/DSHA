"""Generate notices and a module SBOM from the pinned Go module graph."""
from pathlib import Path
import argparse
import json
import os
import subprocess


def json_stream(data):
    decoder = json.JSONDecoder()
    while data.strip():
        data = data.lstrip()
        value, offset = decoder.raw_decode(data)
        yield value
        data = data[offset:]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--go", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    raw = subprocess.check_output([args.go, "list", "-m", "-json", "all"], cwd=root, text=True, encoding="utf-8")
    modules = list(json_stream(raw))
    android_env = dict(os.environ, GOOS="android", GOARCH="arm64", CGO_ENABLED="1")
    package_raw = subprocess.check_output([args.go, "list", "-deps", "-json", "./mobile"], cwd=root, env=android_env, text=True, encoding="utf-8")
    used_modules = {p["Module"]["Path"] for p in json_stream(package_raw) if "Module" in p}
    used_modules.add("golang.org/x/mobile")
    modules = [m for m in modules if m["Path"] in used_modules]
    go_root = Path(subprocess.check_output([args.go, "env", "GOROOT"], text=True).strip())
    go_version = subprocess.check_output([args.go, "env", "GOVERSION"], text=True).strip()
    notices = ["DSHA remote transport third-party notices\n", "Go " + go_version + "\n", (go_root / "LICENSE").read_text(encoding="utf-8")]
    components = []
    missing = []
    for module in modules:
        if module.get("Main"):
            continue
        path, version = module["Path"], module.get("Version", "")
        directory = Path(module["Dir"]) if "Dir" in module else None
        licenses = []
        if directory and directory.is_dir():
            licenses = sorted(p for p in directory.iterdir() if p.is_file() and p.name.upper().startswith(("LICENSE", "COPYING", "NOTICE")))
        if not licenses:
            missing.append(path + "@" + version)
        for license_file in licenses:
            notices += ["\n" + "=" * 72 + "\n" + path + "@" + version + " / " + license_file.name + "\n", license_file.read_text(encoding="utf-8", errors="replace")]
        components.append({"type": "library", "name": path, "version": version,
            "purl": "pkg:golang/" + path + "@" + version,
            "properties": [{"name": "go:module:sum", "value": module.get("Sum", "")},
                           {"name": "go:module:goModSum", "value": module.get("GoModSum", "")},
                           {"name": "dsha:licenseFiles", "value": ",".join(p.name for p in licenses)}]})
    if missing:
        raise RuntimeError("License material unavailable: " + ", ".join(missing))
    (output / "THIRD_PARTY_NOTICES.txt").write_text("\n".join(notices), encoding="utf-8", newline="\n")
    (output / "go-sbom.cdx.json").write_text(json.dumps({"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
        "metadata": {"component": {"type": "library", "name": "dsha-connect", "version": "1.0.0"},
                     "properties": [{"name": "go:toolchain", "value": go_version},
                                    {"name": "dsha:scope", "value": "Android arm64 compile dependency graph and gomobile binding runtime"}]},
        "components": components}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"modules": len(components), "noticesBytes": (output / "THIRD_PARTY_NOTICES.txt").stat().st_size}))


if __name__ == "__main__":
    main()
