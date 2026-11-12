# CarPlay USB lab bridge (macOS → emulator)

Experimental, debug-emulator-only transport. Successful connection and video from TBox are not yet verified. This does not replace Android Auto transport.

Requires Python with pyusb/scapy, Homebrew libusb, and Android SDK adb. Switch TBox to CarPlay physically, then use `bridge.py --list` to identify its serial. For Apple Ethernet mode, identify the device's macOS network interface; the bridge verifies it belongs to the selected USB serial.

```sh
sudo /path/to/venv/bin/python bridge.py --serial DEVICE_SERIAL --ethernet-interface en5
adb -s emulator-5554 reverse tcp:5278 tcp:5278
adb -s emulator-5554 shell am start -n com.lenid.zoh/.debug.CarPlayBridgeTestActivity
```

Select USB in the CarPlay panel. The Ethernet option keeps the Apple Ethernet driver attached and uses BPF; USBMUX still uses libusb. Start only one bridge process. Stop it with Ctrl+C before changing options. A `Pipe error` alone does not identify the failed stage: retain the preceding diagnostic lines.

The bridge listens only on loopback, selects the exact Apple-mode USB serial, and exposes only fixed projection read/write operations. It does not expose arbitrary USB control requests or shell commands. The Android entry point exists only in debug builds and rejects non-emulator hardware.

Cleanup:

```sh
adb -s emulator-5554 shell am start -n com.lenid.zoh/.debug.CarPlayBridgeTestActivity --ez disable true
adb -s emulator-5554 reverse --remove tcp:5278
```

Stop CarPlay in the app and Ctrl+C the host bridge. To test Android Auto again, switch TBox physically to its Android Auto mode and use the separate AA transport.

Tests: `python -m unittest discover -s tools/carplay-bridge -p 'test_*.py'` from the project root.
