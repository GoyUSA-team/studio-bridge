#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
UPSTREAM_SHA=a030f552f452158a2db64fdb32b490fa19c0b48d
if [[ -e upstream-app ]]; then echo 'Refusing to overwrite upstream-app' >&2; exit 1; fi
git init -q upstream-app
git -C upstream-app remote add origin https://github.com/brunodev85/winlator-app.git
git -C upstream-app fetch --depth 1 origin "$UPSTREAM_SHA"
git -C upstream-app checkout --detach FETCH_HEAD
test "$(git -C upstream-app rev-parse HEAD)" = "$UPSTREAM_SHA"
git -C upstream-app apply --check "$ROOT/studio-bridge-changes.patch"
git -C upstream-app apply "$ROOT/studio-bridge-changes.patch"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]]; then echo 'ANDROID_HOME is required' >&2; exit 1; fi
SDKMANAGER="$(find "$SDK/cmdline-tools" -path '*/bin/sdkmanager' | sort | tail -1)"
if [[ -z "$SDKMANAGER" ]]; then echo 'Android sdkmanager is required' >&2; exit 1; fi
# The GitHub-hosted Android SDK licenses are normally already provisioned.
# Accept standard SDK licenses for any additional required build components.
set +o pipefail
yes | "$SDKMANAGER" --licenses >/dev/null
set -o pipefail
"$SDKMANAGER" 'platforms;android-35' 'build-tools;34.0.0' 'ndk;24.0.8215888' 'cmake;3.22.1'
printf 'sdk.dir=%s\n' "$SDK" > upstream-app/local.properties
chmod +x upstream-app/gradlew
(cd upstream-app && ./gradlew --no-daemon --max-workers=2 assembleDebug)
mkdir -p dist
APK_OUT="dist/StudioBridge-0.2-experimental.apk"
cp upstream-app/app/build/outputs/apk/debug/app-debug.apk "$APK_OUT"

if [[ -n "${ANDROID_KEYSTORE_BASE64:-}" ]]; then
  echo "Signing APK with configured ANDROID_KEYSTORE_BASE64 secret..."
  KS_FILE="$(mktemp /tmp/studiobridge-ks.XXXXXX.p12)"
  trap 'rm -f "$KS_FILE"' EXIT
  printf '%s' "$ANDROID_KEYSTORE_BASE64" | tr -d ' \t\r\n' | base64 -d > "$KS_FILE"
  KS_PASS="${ANDROID_KEYSTORE_PASSWORD:-studiobridge123}"
  KEY_ALIAS="${ANDROID_KEY_ALIAS:-studiobridge}"
  KEY_PASS="${ANDROID_KEY_PASSWORD:-$KS_PASS}"
  "$SDK/build-tools/34.0.0/apksigner" sign \
    --ks "$KS_FILE" \
    --ks-type PKCS12 \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KEY_PASS" \
    "$APK_OUT"
  rm -f "$KS_FILE"
fi

"$SDK/build-tools/34.0.0/apksigner" verify --verbose "$APK_OUT"
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
import hashlib
root=Path('upstream-app');dist=Path('dist')
apk_name='StudioBridge-0.2-experimental.apk'
with ZipFile(dist/'StudioBridge-APK-download.zip','w',ZIP_DEFLATED,compresslevel=1) as z:
 z.write(dist/apk_name,apk_name)
 z.write(root/'README-STUDIO-BRIDGE.md','READ-FIRST.md')
with ZipFile(dist/'StudioBridge-source.zip','w',ZIP_DEFLATED,compresslevel=6) as z:
 for p in root.rglob('*'):
  rel=p.relative_to(root)
  if any(part in {'.git','.gradle','.cxx','.idea','build'} for part in rel.parts):continue
  if p.name=='local.properties' or p.suffix in {'.jks','.keystore','.p12'}:continue
  if p.is_file():z.write(p,'StudioBridge-source/'+str(rel))
(dist/'SHA256SUMS.txt').write_text('\n'.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name for p in sorted(dist.iterdir()) if p.is_file())+'\n')
PY
