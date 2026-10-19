#!/usr/bin/env python3
"""Rebuild the clone from the exact original APK; requires JDK, apktool and Android SDK."""
import argparse
import hashlib
import os
from pathlib import Path
import secrets
import subprocess
import sys

SOURCE_SHA256 = 'e9c996b748d9440d8ae6a440a5bb8f74e4897b57c05355057a920711e3886064'
ROOT = Path(__file__).resolve().parent


def run(*args):
    subprocess.run([str(x) for x in args], check=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--tools', type=Path, required=True,
                   help='directory with apktool.jar, sdk-build/android-14 and sdk-platform/android-34')
    p.add_argument('--work', type=Path, default=ROOT / 'repro-build')
    a = p.parse_args()
    assert hashlib.sha256(a.apk.read_bytes()).hexdigest() == SOURCE_SHA256, 'Unexpected original APK'
    a.apk = a.apk.resolve()
    tools = a.tools.resolve()
    work = a.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    decoded = work / 'decoded'
    if decoded.exists():
        raise SystemExit('Use a fresh --work directory to avoid modifying a previous build')
    sdk = tools / 'sdk-build/android-14'
    platform = tools / 'sdk-platform/android-34/android.jar'
    run('java', '-jar', tools/'apktool.jar', 'd', '--no-res', '--no-assets', '-o', decoded, a.apk)
    run(sys.executable, ROOT/'patch_manifest.py', a.apk, work/'AndroidManifest.xml',
        '--metadata', work/'manifest-metadata.json', '--dump-output', work/'manifest-readable.xml')
    run(sys.executable, ROOT/'patch_smali.py', decoded, work/'manifest-metadata.json', '--log', work/'smali-changes.json')
    run('java', '-jar', tools/'apktool.jar', 'b', decoded, '-o', work/'rebuilt.apk')
    classes, dex = work/'classes', work/'dex'
    classes.mkdir(); dex.mkdir()
    run('java', 'com.sun.tools.javac.Main', '-source', '8', '-target', '8', '-Xlint:-options',
        '-classpath', platform, '-d', classes, *sorted((ROOT/'src').rglob('*.java')))
    run('java', '-cp', sdk/'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '34',
        '--lib', platform, '--output', dex, *sorted(classes.rglob('*.class')))
    run(sys.executable, ROOT/'package_apk.py', a.apk, work/'rebuilt.apk', work/'AndroidManifest.xml',
        dex/'classes.dex', work/'manifest-metadata.json', work/'unsigned.apk')
    run(sdk/'zipalign', '-f', '-p', '4', work/'unsigned.apk', work/'aligned.apk')
    signing = ROOT/'signing'
    signing.mkdir(exist_ok=True)
    password = signing/'password.txt'
    key = signing/'clone.p12'
    if not key.exists():
        password.write_text(secrets.token_urlsafe(32))
        password.chmod(0o600)
        run('keytool', '-genkeypair', '-keystore', key, '-storetype', 'PKCS12',
            '-storepass:file', password, '-alias', 'walkman-sleep-stat', '-keyalg', 'RSA',
            '-keysize', '3072', '-validity', '10000', '-dname', 'CN=Walkman Sleep Statistics Local Build', '-noprompt')
    output = work/'Walkman-SleepStats-3.0.4-experimental.apk'
    run(sdk/'apksigner', 'sign', '--ks', key, '--ks-pass', 'file:'+str(password),
        '--ks-key-alias', 'walkman-sleep-stat', '--v1-signing-enabled', 'true', '--v2-signing-enabled',
        'true', '--v3-signing-enabled', 'true', '--out', output, work/'aligned.apk')
    run(sdk/'apksigner', 'verify', '--verbose', output)
    run(sdk/'zipalign', '-c', '-p', '4', output)
    print(output)


if __name__ == '__main__':
    main()
