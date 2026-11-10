#!/usr/bin/env python3
"""USB-to-TCP bridge for testing Carlink in the Android emulator with a real Carlinkit dongle.

The Mac holds the dongle over USB (the emulator cannot capture USB devices on macOS) and relays
raw bytes in both directions to one TCP client. In Carlink enable
Settings -> Debug -> "Dongle over network"; the emulator reaches the Mac at 10.0.2.2.

    pip install pyusb && brew install libusb
    DYLD_LIBRARY_PATH=/opt/homebrew/lib python3 dongle_bridge.py [--port 5277]

When the dongle disappears (it reboots after a session ends) the client is dropped, just like a
USB detach on the head unit, and the app reconnects once the dongle is back.
"""
import argparse
import socket
import threading
import time

import usb.core
import usb.util

VENDOR = 0x1314
PRODUCTS = (0x1520, 0x1521)
EP_OUT, EP_IN = 0x01, 0x81


def find_dongle():
    for pid in PRODUCTS:
        d = usb.core.find(idVendor=VENDOR, idProduct=pid)
        if d is not None:
            return d
    return None


def serve(client, addr):
    dev = find_dongle()
    if dev is None:
        print(f"{addr}: no dongle on USB, closing")
        client.close()
        return
    try:
        dev.set_configuration()
    except usb.core.USBError as e:
        print(f"{addr}: set_configuration failed: {e}")
        client.close()
        return
    print(f"{addr}: connected to dongle {dev.idVendor:04x}:{dev.idProduct:04x}")
    client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    stop = threading.Event()
    stats = {"in": 0, "out": 0}

    def usb_to_tcp():
        while not stop.is_set():
            try:
                data = dev.read(EP_IN, 65536, 500)
            except usb.core.USBTimeoutError:
                continue
            except usb.core.USBError as e:
                print(f"{addr}: USB read ended: {e}")
                break
            try:
                client.sendall(bytes(data))
            except OSError:
                break
            stats["in"] += len(data)
        stop.set()

    reader = threading.Thread(target=usb_to_tcp, daemon=True)
    reader.start()
    try:
        while not stop.is_set():
            try:
                data = client.recv(65536)
            except OSError:
                break
            if not data:
                break
            try:
                dev.write(EP_OUT, data, 2000)
            except usb.core.USBError as e:
                print(f"{addr}: USB write failed: {e}")
                break
            stats["out"] += len(data)
    finally:
        stop.set()
        reader.join(2)
        try:
            client.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        client.close()
        usb.util.dispose_resources(dev)
        print(f"{addr}: closed, from dongle {stats['in'] / 1e6:.1f} MB, to dongle {stats['out'] / 1e3:.1f} KB")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=5277)
    args = ap.parse_args()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.port))
    srv.listen(1)
    print(f"bridge listening on 127.0.0.1:{args.port} (emulator: 10.0.2.2:{args.port})")
    print("dongle:", "found" if find_dongle() else "not connected yet")
    while True:
        client, addr = srv.accept()
        # One session at a time, like a single USB host.
        serve(client, f"{addr[0]}:{addr[1]}")
        time.sleep(0.2)


if __name__ == "__main__":
    main()
