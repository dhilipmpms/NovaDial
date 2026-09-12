<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="160" height="160" alt="NovaDial Icon">
</p>

<h1 align="center">NovaDial</h1>

<p align="center">
  <strong>Call with Speed. Manage Contacts. Call with Privacy.</strong>
</p>

<p align="center">
  A modern, privacy-friendly Android Phone app combining a full-featured
  dialer and complete contact management in one application.
</p>

<p align="center">
  <a href="https://f-droid.org/en/packages/com.novadial.phone/">
    <img
      src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
      alt="Get it on F-Droid"
      width="220">
  </a>
</p>

<p align="center">
  <a href="https://github.com/dhilipmpms/NovaDial/releases">
    <img src="https://img.shields.io/github/v/release/dhilipmpms/NovaDial?style=flat-square&color=blue" alt="Latest Release">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-GPL--3.0-green.svg?style=flat-square" alt="License">
  </a>
  <img src="https://img.shields.io/badge/Platform-Android-00C853?style=flat-square" alt="Platform">
  <img src="https://img.shields.io/badge/Design-Material--3-blueviolet?style=flat-square" alt="Material 3">
  <img src="https://img.shields.io/github/downloads/dhilipmpms/NovaDial/total" alt="Downloads">
</p>

---

## About NovaDial

**NovaDial is more than just a dialer.**

It combines a powerful **Phone Dialer** and a complete **Contacts Manager**
into one unified Android application.

You can make calls, manage contacts, view call history, create and edit
contacts, add contact photos, import and export contacts, and more —
without needing to install a separate contacts application.

### One app for your phone and contacts

NovaDial brings together:

-  Dialer
-  Contacts
-  Favorites
-  Recent calls
-  Maximum ringtone Volume feature
-  Contact photos
-  Contact creation and editing
-  VCF contact import and export
-  In-call experience
-  QR and contact sharing
-  Customization options

All in one application.

---

## Features

### Dialer

- Fast and responsive dialer
- Dual SIM support
- Fast contact lookup
- Direct calling from contacts and recent calls
- Clean and modern dialer interface
- Offline-first experience

### Contacts

NovaDial includes a built-in contact manager, so you don't need a
separate Contacts application.

- Create new contacts
- Edit existing contacts
- Delete contacts
- Multiple phone numbers
- Contact favorites
- Contact photos
- Camera photo capture
- Contact details
- QR code sharing
- Contact sharing
- Native Android contact integration
- VCF import and export

### Recent Calls

- Fast call history loading
- Optimized recent calls view
- Contact-centric call history
- Improved call history grouping
- Multiple Recents UI styles
- Quick actions from recent calls

### Privacy

NovaDial is designed with privacy in mind.

- No advertisements
- Open source
- Offline-first
- No unnecessary cloud dependency
- Your contacts remain on your device unless you choose to share or
  export them

---

### Stability

Existing calling, Telecom, CallLog, and Recents functionality has been
preserved while adding the new contact-management capabilities.

---

## Screenshots

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png" width="30%" alt="Call History" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png" width="30%" alt="Dialer" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png" width="30%" alt="Call Details" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_en-US.png" width="30%" alt="Contact Details" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5_en-US.png" width="30%" alt="QR Code" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6_en-US.png" width="30%" alt="About" />
</p>

---

## Installation

Download the latest APK from the Releases section or build from source.

## Building

### Clone the repository

```bash
git clone https://github.com/dhilipmpms/NovaDial.git
cd NovaDial
```
### Configure Android SDK

create a `local.properties` file in the project root and specify the path to you Android SDK:

```properties
sdk.dir=/path/to/your/Android/sdk
```
Examples:

```properties
sdk.dir=/home/username/Andoird/sdk
```

### Build the application

Debug build:

```bash
./gradlew assembleCoreDebug
```

Release build:

```bash
./gradlew assembleCoreRelease
```

Generated APKs can be found in:

```text
app/build/outputs/apk/
```

## Credits

NovaDial is based on the excellent Fossify Phone project.

Original Project:

https://github.com/FossifyOrg/Phone

Huge thanks to the Fossify team for creating and maintaining the original open-source application.


## Maintainer

Dhilip

GitHub:
https://github.com/dhilipmpms

NovaDial Repository:
https://github.com/dhilipmpms/NovaDial

## License

NovaDial follows the same open-source license as the original Fossify Phone project.

Please refer to the LICENSE file for details.

## Disclaimer

NovaDial is an independent community fork and is not affiliated with, endorsed by, or maintained by the Fossify organization.

All credit for the original foundation of this project belongs to the Fossify contributors.
