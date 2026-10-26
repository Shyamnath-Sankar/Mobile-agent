#!/usr/bin/env python3
"""Fails unless every LOAD segment in `readelf -lW` output is aligned to at least 16 KB.

Google Play requires native code in apps targeting Android 15+ to support 16 KB page sizes.
"""
import sys

loads = [line.split() for line in open(sys.argv[1]) if line.split()[:1] == ["LOAD"]]
if not loads:
    sys.exit("no LOAD segments found")
bad = [cols for cols in loads if int(cols[-1], 16) < 16384]
for cols in loads:
    print("LOAD align", cols[-1])
if bad:
    sys.exit(f"{len(bad)} LOAD segment(s) aligned below 16 KB")
print("All LOAD segments are 16 KB aligned.")
