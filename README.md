# Aadhaar Lock

Lock and unlock your Aadhaar biometrics from your phone, and keep your Aadhaar details one fingerprint away.

Aadhaar Lock is an unofficial client for the public myAadhaar web sign-in. It doesn't need the mAadhaar app, Play Integrity or an unlocked bootloader. It is not affiliated with UIDAI.

<p>
  <img src="docs/screenshots/card.webp" width="200" alt="Signed in, with the Aadhaar card hidden">
  <img src="docs/screenshots/signed-out.webp" width="200" alt="Signed out, with a saved number">
  <img src="docs/screenshots/settings.webp" width="200" alt="Settings">
  <img src="docs/screenshots/ai.webp" width="200" alt="Captcha AI settings">
</p>

Personal details are removed from these screenshots, which were taken before the app was renamed and its text was shortened.

## Features

- Lock, unlock or temporarily unlock biometrics
- Aadhaar card with photo, identity, address and contact details. Tap it to flip
- Values stay hidden until you tap View, and hidden values use random dot counts so their length doesn't show
- Copy any single value, or all of them
- Details stay readable offline and after your session ends
- OTP fills in from SMS when Android allows it. You can always type it
- Captcha can be read by any OpenAI-compatible vision model, or typed by hand

## Storage

| Data | Protection |
| --- | --- |
| Aadhaar number | AES-GCM with a Keystore key, in StrongBox when available. Every use needs a strong biometric |
| Saved details and last lock status | AES-GCM in `noBackupFilesDir/details.bin`. The key is sealed with the number, so one biometric opens both |
| Session tokens | AES-GCM with a Keystore key that needs no biometric, so requests can refresh them |

Saving details needs a saved number. Changing enrolled fingerprints or replacing the number destroys the saved copy. App backup is off.

## SMS

Aadhaar Lock reads OTPs through `RECEIVE_SMS` and `READ_SMS`. Android hides OTP messages from apps on newer versions, so auto-fill may not work without root. Type the OTP in that case.

On a rooted phone, turn on **Settings → Read SMS with root**. Aadhaar Lock then copies the SMS database through `su` and grants itself the restricted message app-ops.

## Captcha

Add an API key in **Settings → Captcha AI** to solve captchas automatically. The default model is `gemini-2.5-flash-lite` at `https://generativelanguage.googleapis.com/v1beta/openai/`. Any OpenAI-compatible base URL and vision model work. Without a key, or if a solve fails, the captcha field opens for typing.

## Build

Requires JDK 17 or newer and Android SDK platform 37. Android 12 or newer is needed to run it.

```sh
./gradlew assembleDebug
adb install build/outputs/apk/debug/aadhaar-lock-debug.apk
```

## Sign-in flow

All requests go to `https://tathya.uidai.gov.in`.

1. `GET /access/oauth/authorize` with PKCE S256 (Base64 of the hex SHA-256) sets `SESSION` and redirects
2. `GET /access/generateCaptcha` returns the captcha image and `transactionId`
3. `POST /access/generateOTPForOAuth` with `{uid, captcha, captchaTxnId}` sends the OTP
4. `POST /access/login` with `{uid, otp}` returns `redirectURL`
5. Following `redirectURL` to authorize gives a 303 with `code`
6. `POST /access/oauth/token` with `code` and `code_verifier` returns an access token (299 s) and a refresh token (about 45 min)

| Endpoint | Body |
| --- | --- |
| `POST /lockUnlockBiometrics/ext/v1/oauth/getUidPreferences` | `{uid}` |
| `POST /lockUnlockBiometrics/ext/v1/oauth/lockUnlock` | `{uid, bioLockOrUnlock}` with 2 lock, 3 unlock, 4 temporary unlock |
| `POST /profileService/api/v1/oauth/profile` | `{uidNumber}` |

Authenticated requests send `appID: MYAADHAAR` and `Authorization: Bearer <token>`.
