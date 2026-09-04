#!/bin/sh
# Same as bench.sh but keeps run/config/barrenskies-common.toml, so a setting can be varied between runs.
powershell.exe -NoProfile -Command "Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force" >/dev/null 2>&1
sleep 4
rm -rf run/barrenskies_test run/logs/latest.log 2>/dev/null
if ! ./gradlew compileJava -q; then echo "COMPILE FAILED"; exit 1; fi
timeout 900 ./gradlew runServer -Pbench="${1:-16}" -q >/dev/null 2>&1 &
i=0
while [ $i -lt 120 ]; do
  if grep -qa "\[bench\] world is" run/logs/latest.log 2>/dev/null; then break; fi
  if grep -qa "Failed to initialize server" run/logs/latest.log 2>/dev/null; then echo "FAILED TO START"; break; fi
  sleep 5
  i=$((i + 1))
done
grep -a "\[bench\]" run/logs/latest.log 2>/dev/null || echo "no result"
powershell.exe -NoProfile -Command "Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force" >/dev/null 2>&1
