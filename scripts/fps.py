#!/usr/bin/env python3 -I
"""Instantaneous fps per `video: pic=N` log line (the logged fps is a running average). usage: fps.py hu15.log [...]"""
import re, sys, statistics as st
for path in sys.argv[1:]:
    rows, prev = [], None
    for line in open(path, errors="replace"):
        m = re.match(r"(\d+):(\d+):(\d+\.\d+) video: pic=(\d+) ", line)
        if not m: continue
        t = int(m[1]) * 3600 + int(m[2]) * 60 + float(m[3]); n = int(m[4])
        if prev and n >= prev[1] and 0.5 < t - prev[0] < 5: rows.append((t, (n - prev[1]) / (t - prev[0])))
        prev = (t, n)
    if not rows: print(path, "no data"); continue
    f = [r[1] for r in rows]
    print(f"{path}: {len(f)} windows, median {st.median(f):.1f} fps, <10 fps {100*sum(x<10 for x in f)/len(f):.0f}% of the time, >=25 fps {100*sum(x>=25 for x in f)/len(f):.0f}%")
