#!/usr/bin/env python3
"""Build a signed Majung APK using owner-only files outside the checkout."""
import argparse
import json
import os
import pathlib
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--config', type=pathlib.Path,
                    default=pathlib.Path.home()/'.local/share/majung-signing/release-signing.json')
args = parser.parse_args()
if not args.config.expanduser().is_file():
    raise SystemExit('Signing configuration is missing; use the team APK or provide --config')
config = args.config.expanduser().resolve(strict=True)
if config.stat().st_uid != os.getuid() or config.stat().st_mode & 0o077:
    raise SystemExit('Signing configuration must belong to this user with mode 600')
data = json.loads(config.read_text())
store = pathlib.Path(data['storeFile']).expanduser().resolve(strict=True)
if store.stat().st_uid != os.getuid() or store.stat().st_mode & 0o077:
    raise SystemExit('Signing keystore must belong to this user with mode 600')
environment = os.environ.copy()
for field, variable in [('storeFile','MAJUNG_SIGNING_STORE_FILE'),('storePassword','MAJUNG_SIGNING_STORE_PASSWORD'),
                        ('keyAlias','MAJUNG_SIGNING_KEY_ALIAS'),('keyPassword','MAJUNG_SIGNING_KEY_PASSWORD')]:
    value = data[field]
    if not isinstance(value,str) or not value: raise SystemExit('Incomplete signing configuration')
    environment[variable] = str(store) if field == 'storeFile' else value
root = pathlib.Path(__file__).resolve().parents[1]
if subprocess.check_output(['git','status','--porcelain','--untracked-files=all'],cwd=root).strip():
    raise SystemExit('Commit source changes before producing a release APK')
environment['MAJUNG_BUILD_SHA'] = subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
subprocess.run(['./gradlew',':vehicle:assembleRelease','-PpickupApi=https://majung.cloud',
                '--no-configuration-cache','--no-daemon'],cwd=root,env=environment,check=True)
print('Source revision:', environment['MAJUNG_BUILD_SHA'])
print('Signed APK: vehicle/build/outputs/apk/release/vehicle-release.apk')
