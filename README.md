# PDF Maker

An offline Android app for turning images and existing PDFs into a single PDF, and for
turning a PDF back into images — the common "iLovePDF" workflow, without an upload.

Everything happens on the device. The app has no internet permission at all.

## Features

### Create PDF

- **Images → PDF.** PNG, JPEG, WebP, HEIC, BMP and GIF, one page per image.
- **PDF → PDF.** Existing PDFs are merged in page-for-page. Text and vectors are
  copied through rather than re-rendered, so merged pages stay sharp and selectable.
- **Bulk select from your phone's files.** Uses the system document picker, so you
  can multi-select a whole folder of scans in one go. No storage permission needed.
- **Photo picker support** for adding straight from your gallery.
- **Sorting** by name (natural order, so `page2` comes before `page10`), by date
  modified, or by size — each ascending or descending — plus manual reordering and a
  one-tap reverse.
- **Bulk selection inside the app.** Long-press any row to enter selection mode, then
  select all / remove the selected files.
- **Share sheet integration.** Share images or PDFs from any app straight into PDF Maker.
- **Page setup:** fit-to-image, A4, US Letter or US Legal; four margin sizes;
  auto-landscape for wide images.
- **Quality control:** lossless, high, medium or compact, trading file size against
  fidelity. Camera photos are EXIF-rotated correctly.

### Seeing what you are building

- **Two view modes.** A compact list for reordering forty scans quickly, or large
  previews where each page fills the width of the phone. Toggle it from the app bar.
- **Full-screen viewer.** Tap any preview to open the page full screen. Pinch or
  double-tap to zoom, drag to pan, and swipe sideways to page through a multi-page PDF.
  Pages are re-rendered at viewer resolution, so zooming in really does show more detail.

### PDF → Images

- **A whole PDF into one `.zip`**, one image per page, on the second tab.
- **JPEG or PNG**, at 96, 150 or 300 dpi, with a JPEG quality slider.
- Pages are rendered and streamed into the archive one at a time, so a 300-page
  document never needs more than a single page in memory. Progress is live and the
  export can be cancelled.

## Install

Grab the APK from the [Releases page](../../releases) and open it on your phone.
Android will ask you to allow "install unknown apps" for whatever opened the file —
that prompt is expected for any app not installed through Google Play.

Requires Android 7.0 (API 24) or newer.

## Building it yourself

```bash
git clone https://github.com/teterw/pdf-maker-android.git
cd pdf-maker-android
./gradlew assembleRelease
```

The output lands in `app/build/outputs/apk/release/`.

Release builds are signed with the project key when `keystore.properties` is present
at the repo root:

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file the release build falls back to the debug key, so a fresh clone
still builds. CI reconstructs `keystore.properties` from repository secrets
(`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

Pushing a `v*` tag builds the APK and attaches it to a GitHub Release.

## How it works

| Concern | Approach |
| --- | --- |
| Reading files | Storage Access Framework + the Android photo picker — no broad storage permission |
| PDF merging | [PDFBox-Android](https://github.com/TomRoush/PdfBox-Android) `PDFMergerUtility`, which clones page objects instead of rasterising them |
| Image embedding | PDFBox `JPEGFactory` / `LosslessFactory`, with images downsampled to a quality-dependent cap first |
| PDF → images | Platform `PdfRenderer` at the chosen dpi, compressed straight into a `ZipOutputStream` |
| Thumbnails | Platform `PdfRenderer` for PDFs, `BitmapFactory` for images, behind a small `LruCache` keyed by item *and* size |
| Zoom viewer | Pages re-rendered at ~1800 px behind `detectTransformGestures`, in a `HorizontalPager` for multi-page PDFs |
| UI | Jetpack Compose + Material 3, with dynamic colour on Android 12+ |

Long documents are built off the main thread with a live progress count, and the
export can be cancelled partway through.

## Licence

MIT — see [LICENSE](LICENSE).
