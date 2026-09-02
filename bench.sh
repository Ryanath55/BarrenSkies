#!/bin/sh
# Temporary. One benchmark run: stop anything still holding the port, wipe the world, generate, report.
powershell.exe -NoProfile -Command "Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force" >/dev/null 2>&1
sleep 4
rm -rf run/barrenskies_test run/logs/latest.log 2>/dev/null
timeout 900 ./gradlew runServer -Pbench="${1:-16}" -q >/dev/null 2>&1 &
GRADLE=$!
i=0
while [ $i -lt 120 ]; do
  if grep -qa "\[bench\] pos" run/logs/latest.log 2>/dev/null; then break; fi
  if grep -qa "Failed to initialize server" run/logs/latest.log 2>/dev/null; then echo "FAILED TO START"; break; fi
  sleep 5
  i=$((i + 1))
done
grep -a "\[bench\]" run/logs/latest.log 2>/dev/null || echo "no result"
powershell.exe -NoProfile -Command "Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force" >/dev/null 2>&1
