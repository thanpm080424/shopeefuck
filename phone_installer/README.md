# HKPick Cài Nhanh

Android phone-to-phone installer for the HKPick driver app. The phone running
this helper acts as the USB host and uses an ADB connection explicitly approved
on the target phone. It installs only the embedded or user-selected driver APK,
then opens the driver's launcher activity.

## Build a signed installer APK

From PowerShell in the Flutter project, run:

```powershell
.\tools\build_phone_installer.ps1
```

The script selects the newest `hkpick_driver_release.apk` under a
`Desktop\HKPick-release-*` folder, checks its package, Android minimum version,
signature, and APK validity, and embeds it in the installer. It then builds and
signs the helper APK with the existing HKPick release key. The finished APK is
placed in a timestamped folder on the Desktop.

## Install another driver build without rebuilding the helper

The helper's **CHỌN BẢN TÀI XẾ MỚI** button opens Android's file picker on the
phone running the helper. Select a newer release APK there. The helper accepts
it only when the package is `com.samsung.health.addon`, its signing certificate
matches the embedded release, and its `versionCode` is not lower. It stores the
selected APK in the helper's private app storage. The next **RUN** installs that
version. This update file is not uploaded anywhere.

Future driver releases must continue using the same release signing key. A
different key is rejected to avoid installing a substituted app.

## Phone-to-phone setup

1. Install HKPick Cài Nhanh on the phone that will act as the installer.
2. On the target phone, open **Settings → About phone → Software information**
   and tap **Build number** seven times if Developer options are not enabled.
3. On the target, open **Settings → Developer options**, enable **USB debugging**,
   and keep the phone unlocked.
4. Connect the phones with a data-capable OTG cable/adapter. If the installer
   phone asks for USB access, allow it.
5. Press **RUN**. On the target phone, inspect and approve the
   **Allow USB debugging?** prompt. The helper reports each waiting step.
6. When install and launch finish, turn off USB debugging or revoke the trusted
   computer authorization on the target if you will not use it again.

The installer phone must support USB Host/OTG. The driver requires Android 11
(API 30) or later on the target. If an older APK signed with a different key is
already installed, the helper will show the signing conflict and will not
uninstall it automatically.

## Security and distribution notes

ADB authorization gives the installer phone broad debugging access to the
target while authorized. Approve only phones you own or have permission to
service, and revoke the authorization or disable USB debugging afterward.

This changes how the driver is delivered and installed; it does not make the
driver APK impossible to extract from the helper or from an installed phone.
