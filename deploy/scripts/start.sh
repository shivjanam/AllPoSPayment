#!/bin/bash
# ISO8583Studio noVNC Startup Script

set -e

# Configuration
export DISPLAY=:99
RESOLUTION=${RESOLUTION:-1280x800x24}
VNC_PORT=${VNC_PORT:-5900}
NOVNC_PORT=${NOVNC_PORT:-6080}

echo "=========================================="
echo "  ISO8583Studio Remote Demo"
echo "  Resolution: $RESOLUTION"
echo "  noVNC Port: $NOVNC_PORT"
echo "=========================================="

# Create necessary directories
mkdir -p ~/.config/openbox

# Start Xvfb (virtual framebuffer)
echo "[1/4] Starting virtual display..."
Xvfb $DISPLAY -screen 0 $RESOLUTION -ac +extension GLX +render -noreset &
sleep 2

# Start window manager (lightweight)
echo "[2/4] Starting window manager..."
openbox --config-file ~/.config/openbox/rc.xml &
sleep 1

# Start x11vnc (VNC server)
echo "[3/4] Starting VNC server..."
x11vnc -display $DISPLAY -forever -shared -rfbport $VNC_PORT -nopw -xkb -noxrecord -noxfixes -noxdamage &
sleep 2

# Start noVNC (WebSocket proxy)
echo "[4/4] Starting noVNC web interface..."
websockify --web=/usr/share/novnc/ $NOVNC_PORT localhost:$VNC_PORT &
sleep 2

echo "=========================================="
echo "  noVNC ready at: http://localhost:$NOVNC_PORT"
echo "  Click 'Connect' in browser to start"
echo "=========================================="

# Launch ISO8583Studio
echo "Launching ISO8583Studio..."
exec java \
    -Dfile.encoding=UTF-8 \
    -Dsun.java2d.xrender=true \
    -Dawt.useSystemAAFontSettings=on \
    -Dswing.aatext=true \
    -Xmx512m \
    -jar /app/iso8583studio.jar
