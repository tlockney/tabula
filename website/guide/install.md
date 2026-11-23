# Install

::: warning Early development
There is no packaged release yet. Installation is manual and the app is installed over ADB. Expect rough edges.
:::

## Requirements

- Home Assistant 2025.1.0 or later.
- An Android tablet. The first target is the Amazon Fire HD 10 Plus (Fire OS 7, Android 9). Other devices are not yet tested.
- A computer with `adb`, `make`, `git`, Python 3.13 and Node 22.

## 1. Install the integration

Copy `integration/custom_components/tabula` from the repository into `custom_components/tabula` in your Home Assistant config directory, then restart Home Assistant. Add the Tabula hub under Settings → Devices & services → Add integration.

## 2. Build and install the app

```sh
git clone https://github.com/tlockney/tabula
cd tabula
make setup
make setup-android
make android-apk
ANDROID_SERIAL=<serial> tools/device/provision.sh
```

The provision script installs the APK and applies the ADB grants the app needs, such as brightness control and battery-optimization exemption.

## 3. Pair

Launch the app, enter your Home Assistant address and a name for the tablet, and confirm the six-digit code in Home Assistant.

Use HTTPS if you can. Pairing sends a credential over your network, and plain HTTP lets someone on that network read it.

The full runbook, including upgrade, removal and recovery, is [`docs/DEVICE.md`](https://github.com/tlockney/tabula/blob/main/docs/DEVICE.md).
