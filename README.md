# NetSession

NetSession is an Android app that measures per-app network traffic during a manually selected time period.

It uses Android's network statistics through a Shizuku UserService and compares the UID counters at the beginning and end of each measurement.

> **Development status:** Early alpha
>
> NetSession is functional, but interfaces, storage formats and behavior may still change.

## Features

- Manual start and stop of a measurement period
- Per-app download, upload and total traffic
- Wi-Fi and mobile-data separation
- Android NetStats `FOREGROUND` and `DEFAULT` traffic classes
- Resolution of Android UIDs to app and package names
- Named measurement sessions
- Persistent session history
- Exact date and measurement period
- Expandable per-app technical details
- Copyable text report
- Automatic light and dark theme

## Requirements

- Android 8.0 or newer
- Shizuku installed and running
- Shizuku permission granted to NetSession

NetSession currently targets Android 16 and has been tested on:

- OnePlus 15
- Samsung Galaxy S24 Ultra

## How it works

When a measurement starts, NetSession stores a compact snapshot of Android's network statistics.

When the measurement is stopped, a second snapshot is read. NetSession calculates the non-negative difference between both snapshots for each UID, network type and Android traffic class.

The app does not use a VPN and does not inspect packet contents.

## Important limitations

Android attributes network traffic to Linux UIDs. Several packages can share the same UID, especially system components. In those cases, NetSession may display a combined entry.

The labels **Foreground** and **Background** are simplified UI descriptions:

- Foreground corresponds to Android NetStats `FOREGROUND`
- Background corresponds to Android NetStats `DEFAULT`

`DEFAULT` does not always mean that an app was visibly running in the background. It is Android's accounting class for traffic not assigned to `FOREGROUND`.

Traffic statistics are supplied by Android and device manufacturers. Their availability and exact behavior can vary between devices and Android versions.

## Privacy

NetSession processes network accounting data locally on the device.

It does not:

- inspect transmitted content
- capture packets
- operate a VPN
- send measurement data to a server
- include advertising or analytics

Saved sessions remain in the app's local storage unless the user copies or shares a report.

## Building

Clone the repository and build the debug APK with:

    ./gradlew assembleDebug

The APK will be created at:

    app/build/outputs/apk/debug/app-debug.apk

## Project status

The current version is intended for testing and technical evaluation. Bug reports and device compatibility feedback are welcome.

## License

NetSession is licensed under the GNU General Public License version 3 or later. See LICENSE.
