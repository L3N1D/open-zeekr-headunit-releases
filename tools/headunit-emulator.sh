#!/bin/bash
# Starts the HeadUnit_Zeekr emulator (Android 11, 1920x1080 at 160 dpi, like the Zeekr head unit)
# and adds an 800x480 presentation display standing in for the car's HUD.
#
#   tools/headunit-emulator.sh          start, keeping the emulator's data
#   tools/headunit-emulator.sh --wipe   start from a clean system
#
# The emulator cannot be told to add the display at boot on this image (its multi-display
# service comes up after the emulator tries), so the script adds it once Android has booted.

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
AVD=HeadUnit_Zeekr

# Flags 11 = PUBLIC | PRESENTATION | OWN_CONTENT_ONLY: a presentation display that apps find
# through DisplayManager.DISPLAY_CATEGORY_PRESENTATION, as on the car's HUD.
HUD_WIDTH=800
HUD_HEIGHT=480
HUD_DPI=160
HUD_FLAGS=11

#   tools/headunit-emulator.sh --swangle   software GLES 3.1 (ANGLE + SwiftShader): slower, but
#                                          needed by apps whose shaders want more than the GLES 3.0
#                                          that the Mac's OpenGL translator offers (the Zeekr 3D launcher)
EXTRA=()
for arg in "$@"; do
    case "$arg" in
        --wipe) EXTRA+=(-wipe-data) ;;
        --swangle) EXTRA+=(-gpu swangle_indirect) ;;
    esac
done

"$SDK/emulator/emulator" -avd "$AVD" -no-boot-anim -no-snapshot-load "${EXTRA[@]}" >/dev/null 2>&1 &

echo "Waiting for Android to boot..."
"$ADB" -e wait-for-device
until [ "$("$ADB" -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
done

"$ADB" -e emu multidisplay add 1 "$HUD_WIDTH" "$HUD_HEIGHT" "$HUD_DPI" "$HUD_FLAGS" >/dev/null
sleep 2
echo "Displays:"
"$ADB" -e shell dumpsys display | grep -E "mBaseDisplayInfo=" | sed -E 's/.*DisplayInfo\{"([^"]+)", displayId ([0-9]+).*real ([0-9]+ x [0-9]+).*/  \2: \1, \3/'
