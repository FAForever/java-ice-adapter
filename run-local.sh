#!/usr/bin/env bash
set -e

PLATFORM="linux"
CLASSPATH="included"
CLIENT_COUNT=${1:-3}

echo "============================================"
echo " Java ICE Adapter - Local Run (Linux)"
echo "============================================"

# --- Build ---
echo ""
echo "[1/3] Building JARs..."
./gradlew -PjavafxPlatform="$PLATFORM" -PwebrtcPlatform="$PLATFORM" -PjavafxClasspath="$CLASSPATH" \
    :client:shadowJar :server:shadowJar :ice-adapter:shadowJar

# --- Copy JARs to root ---
echo ""
echo "[2/3] Copying JARs to project root..."

ICE_JAR=$(ls -1 ice-adapter/build/libs/faf-ice-adapter-*.jar 2>/dev/null | head -n1)
CLIENT_JAR=$(ls -1 client/build/libs/client-*-all.jar 2>/dev/null | head -n1)
SERVER_JAR=$(ls -1 server/build/libs/server-*-all.jar 2>/dev/null | head -n1)

if [ -z "$ICE_JAR" ]; then
    echo "ERROR: ice-adapter shadow JAR not found!"
    exit 1
fi
if [ -z "$CLIENT_JAR" ]; then
    echo "ERROR: client shadow JAR not found!"
    exit 1
fi
if [ -z "$SERVER_JAR" ]; then
    echo "ERROR: server shadow JAR not found!"
    exit 1
fi

cp -f "$ICE_JAR" "faf-ice-adapter.jar"
cp -f "$CLIENT_JAR" "test-client.jar"
cp -f "$SERVER_JAR" "test-server.jar"

echo "JARs copied successfully."

# --- Start Server ---
echo ""
echo "[3/3] Starting test server..."
java -jar test-server.jar &
SERVER_PID=$!
sleep 3

# --- Start Clients ---
echo "Starting $CLIENT_COUNT test clients..."
for i in $(seq 1 "$CLIENT_COUNT"); do
    echo "  Starting client test-$i ..."
    java -jar test-client.jar --name=test"$i" &
done

echo ""
echo "============================================"
echo " All processes started!"
echo "============================================"
echo ""
echo "Server PID: $SERVER_PID"
echo "To stop: kill $SERVER_PID  (and close client windows)"
echo ""
