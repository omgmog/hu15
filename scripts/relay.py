#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Time-boxed TCP relay: LAN 0.0.0.0:LISTEN -> 127.0.0.1:TARGET, so a tablet can reach `adb forward` ports on the host.
Usage: scripts/relay.py [listen=5277] [target=5278] [seconds=1800]   (adb forward tcp:5278 tcp:5277 first)
No auth: exposes whatever is behind TARGET to the LAN until it exits."""
import select, socket, sys, threading, time

listen, target, secs = (int(x) for x in (sys.argv[1:] + ["5277", "5278", "1800"][len(sys.argv) - 1:])[:3])
end = time.time() + secs

def pipe(a, b, name):
    n = 0
    try:
        while True:
            d = a.recv(65536)
            if not d:
                print(f"{time.strftime('%H:%M:%S')} {name}: EOF from source after {n} bytes", flush=True); break
            n += len(d); b.sendall(d)
    except OSError as e:
        print(f"{time.strftime('%H:%M:%S')} {name}: error {e!r} after {n} bytes", flush=True)
    for s in (a, b):
        try: s.shutdown(socket.SHUT_RDWR)
        except OSError: pass

ls = socket.socket(); ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
ls.bind(("0.0.0.0", listen)); ls.listen(4)
print(f"relay :{listen} -> 127.0.0.1:{target} for {secs}s", flush=True)
while time.time() < end:
    r, _, _ = select.select([ls], [], [], 1)
    if not r: continue
    c, addr = ls.accept()
    try:
        t = socket.create_connection(("127.0.0.1", target), 5)
        t.settimeout(None)   # create_connection's timeout sticks to the socket: a quiet phone screen would otherwise read as a dead link after 5 s
    except OSError as e:
        print("target unreachable:", e, flush=True); c.close(); continue
    print("relaying", addr, flush=True)
    threading.Thread(target=pipe, args=(c, t, 'tablet->phone'), daemon=True).start()
    threading.Thread(target=pipe, args=(t, c, 'phone->tablet'), daemon=True).start()
