# Mac Dashboard APK

An Android dashboard for monitoring a Mac on the same local Wi-Fi network. The Android app displays live CPU load, memory, disk usage, network totals, uptime, and the 30 busiest processes with their CPU and RAM use.

## How it works

The APK cannot inspect another computer's processes by itself. Run the small Python companion on the Mac; it reads standard macOS system tools and serves a token-protected JSON snapshot on your local network. The app polls it every three seconds. Telemetry is not sent to a cloud service.

## Start the Mac companion

1. Keep the Mac and Android phone on the same Wi-Fi network.
2. On the Mac, open Terminal in this repository and run:

   ```sh
   python3 mac-agent/mac_agent.py
   ```

3. The companion prints a phone address and a pairing token. If macOS asks whether Python may accept incoming connections, allow it on your private network.
4. Open **Mac Dashboard** on Android. Enter the printed address (for example `192.168.1.20:8765`) and token, then tap **Connect / refresh**.

The companion creates `~/.macdashboard-token` with owner-only permissions and reuses that token on future launches. Keep the token private. Stop the companion with `Ctrl+C`; remove the token file to rotate it.

## Build the APK

The **Build Mac Dashboard APK** GitHub Actions workflow runs on pushes to `main` and can also be started manually. Download the `MacDashboardAPK` artifact from the successful workflow run. It is a debug-signed APK for direct installation.

To build locally, install JDK 17, Android SDK 35, and Gradle 8.9, then run:

```sh
gradle --no-daemon assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Network and privacy

- The companion binds to all network interfaces so a paired phone can reach it, but it requires a random bearer token for the metrics endpoint.
- Use this on a trusted private network. Do not forward port 8765 to the internet.
- Process names, PIDs, usernames, and resource usage are visible to anyone with the pairing token.
- The dashboard uses local HTTP on the LAN. It does not upload metrics, credentials, or process data.
