#!/usr/bin/env python3
"""Compile real production sources against Android SDK; execute with narrow JVM fakes.

The fakes model deterministic callbacks and immediate in-memory preference writes.
They do not validate ART verification, Android threads/suspend, asynchronous disk
writes, UI, lifecycle callbacks, smali hook placement, gapless playback or native audio.
No downloaded dependencies or production build directories are used.
"""
from pathlib import Path
import os
import subprocess
import tempfile

MOD = Path(__file__).resolve().parents[1]
SDK = MOD.parent / 'tools/sdk-platform/android-34/android.jar'
JAVA = os.environ.get('JAVA', 'java')

def run(arguments):
    subprocess.run(arguments, check=True)

def compile_java(output, classpath, sources):
    run([JAVA, 'com.sun.tools.javac.Main', '-source', '8', '-target', '8',
         '-Xlint:-options', '-encoding', 'UTF-8', '-classpath', str(classpath),
         '-d', str(output), *map(str, sources)])

def main():
    if not SDK.is_file():
        raise SystemExit('Missing existing Android SDK android.jar: ' + str(SDK))
    with tempfile.TemporaryDirectory(prefix='rea-service-tests-') as folder:
        root = Path(folder)
        production, fake, tests = (root / name for name in ('production', 'fake', 'tests'))
        for path in (production, fake, tests): path.mkdir()
        compile_java(production, SDK, sorted((MOD / 'src').rglob('*.java')))
        compile_java(fake, SDK, sorted((MOD / 'test-support').rglob('*.java')))
        classpath = os.pathsep.join(map(str, (fake, production, SDK)))
        compile_java(tests, classpath, [MOD / 'test/PlaybackExtrasTest.java'])
        runtime = os.pathsep.join(map(str, (fake, production, tests, SDK)))
        run([JAVA, '-classpath', runtime, 'PlaybackExtrasTest'])

if __name__ == '__main__': main()
