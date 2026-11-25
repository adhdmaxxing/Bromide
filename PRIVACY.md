# Bromide privacy policy

*Last updated: November 2026*

Bromide develops your photos on your phone. It has no accounts, no ads, no
analytics and no tracking, and it never sends your photos or any data about you
to anyone, including its developer.

## What Bromide reads

- **Photos your camera sends.** Over Wi-Fi (FTP), your camera uploads photos
  straight to Bromide on your phone. Nothing goes through the internet.
- **Photos your camera maker's app saves** (if you set up a camera "through its
  app"). With your permission to see your photos, Bromide looks at new photos as
  they are added and only develops the ones whose camera make matches one of
  your cameras. Your phone's own photos, screenshots and downloads are skipped.
  With the "photo location" permission, Bromide keeps the GPS position your
  camera's app added, so the developed photo has the same location as the
  original. Deny it and developed photos have no location.
- **Photos you pick** in *Phone photos* (through Android's photo picker: Bromide
  only sees the photos you choose) and **a memory card folder you pick** in
  *Develop from card*.

## What Bromide stores

- Developed photos go to `Pictures/Bromide` on your phone, with the original's
  EXIF information (date, camera, lens, exposure settings, and location if the
  original had one and you allowed it) and the film recipe used.
- Your settings (cameras, films, adjustments) and a small copy of the latest
  photo (used to preview films) stay in Bromide's private storage. Android may
  include the settings in your own device backup; photos are excluded.
- Photos waiting to be developed are kept in Bromide's private storage only until
  they are developed.

Your originals are never changed or deleted.

## Network

Bromide uses the network permission only to run a small FTP server that your
camera connects to over your phone's hotspot or local Wi-Fi. Bromide makes no
connections to the internet. Links in the app (source code, this policy) open in
your browser.

## Purchases

The one-time unlock is bought through Google Play. Bromide asks the Play Store app
on your phone whether this Google account owns the unlock, and Google Play handles
the payment under [Google's privacy policy](https://policies.google.com/privacy).
Bromide never sees your payment details, and nothing about the purchase is sent to
its developer.

## Permissions

| Permission | Why |
|---|---|
| Network, Wi-Fi state, Wi-Fi lock | Receive photos from your camera over Wi-Fi |
| Photos and videos (read) | Develop the photos your camera maker's app saves |
| Photo location | Keep the location of those photos |
| Notifications | Show that Bromide is receiving or developing photos |
| Foreground service, wake lock | Keep receiving and developing while the screen is off |

## Children

Bromide is not directed at children and collects no personal data from anyone.

## Contact

Questions: open an issue at <https://github.com/adhdmaxxing/Bromide/issues>.

Bromide is free software (GPLv3). You can check everything above in its source
code: <https://github.com/adhdmaxxing/Bromide>.
