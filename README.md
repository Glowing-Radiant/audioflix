# AudioFlix

An Android library for audio-described movies and TV shows. You browse a provider's catalog, download titles for offline listening, and the app remembers where you stopped in every movie and episode.

There are two sources. [AudioVault](https://audiovault.net) is the catalog: browsing works without an account, and downloading needs an AudioVault login. Google Drive covers anything shared as "Anyone with the link": paste a folder or file link, then stream or download it, with no Google account.

## Features

- **Browse**: AudioVault's featured titles plus recently added movies and shows, the full movie and show catalogs (they load more as you scroll), and search.
- **Google Drive**: open a shared folder or file link and browse its subfolders. Folders you open are kept under "Your folders". Add a folder (subfolders included) or a single file to the library to **stream** it straight from Drive or **download** it. A streamed title can be downloaded later, or a downloaded one can go back to streaming, keeping listening progress either way. Zip files can only be downloaded, not streamed.
- **Chapters**: m4b and m4a files with chapters are split into one track per chapter, from the Nero `chpl` list or the Apple chapter track, whichever the file has. Each chapter plays as a clip of its file, so next and previous move by chapter and progress is saved per chapter.
- **Download**: runs in the background with a progress notification and resumes partial files. Shows arrive as a zip, which the app extracts into episodes sorted in natural order (`Episode 2` comes before `Episode 10`). Movies arrive as a single mp3. There's an optional Wi-Fi-only setting.
- **Library**: filter by movies, shows, in progress, downloads or finished. Each title shows its download state or how much you've listened.
- **Player**: plays in the background with a media notification and lock-screen and Bluetooth controls. It has 10 s back, 30 s forward, previous and next episode, and speeds from 0.75x to 2x. It moves to the next episode on its own.
- **Progress**: the playback service saves the position of every track every 5 s, on pause, on seek and on track changes. Finished tracks and titles are marked, and you can mark them played or unplayed yourself.
- **Updates**: on start, the app checks this repository's GitHub releases. When there's a newer version it opens an update screen with the release notes of every version you're missing, then downloads the APK, checks its SHA-256 and hands it to Android's installer. The Account screen has a manual "Check for updates" button.
- **Accessibility**: built for screen-reader users. Controls have labels, headings are marked as headings, and durations are read in words. Download progress is announced, and each episode row has a TalkBack action to mark it played or unplayed.

## Building

Requirements: JDK 17+ and Android SDK platform 37.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # parser, sorting and update tests
```

Debug builds install as `app.audioflix.debug` ("AudioFlix Dev"), so they sit next to the released app instead of clashing with its signing key. They don't check for updates on start.

## Releasing

Releases are built by GitHub Actions ([`release.yml`](.github/workflows/release.yml)) when a `v1.2.3` tag is pushed. **Every release needs release notes**: the workflow copies that version's section of [`CHANGELOG.md`](CHANGELOG.md) into the GitHub release and fails if it's empty. The in-app updater shows those notes on its update screen.

1. As you work, add lines under `## [Unreleased]` in `CHANGELOG.md` (`### Added`, `### Changed`, `### Fixed`, `### Removed`). Write them for listeners.
2. Run `scripts/release.sh 1.2.3`. It moves the Unreleased entries under `## [1.2.3] - <today>`, sets `appVersion` in `app/build.gradle.kts`, commits and tags `v1.2.3`.
3. Run `git push --follow-tags`. The workflow checks that the tag matches `appVersion`, runs the tests, builds and signs the APK, and publishes the release with `audioflix-1.2.3.apk` attached.

`versionCode` is derived from the version (`1.2.3` → `10203`), so minor and patch stay below 100.

### Signing

Updates only install over an app signed with the same key, so every release must use the same keystore. The workflow reads it from these repository secrets:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the `.jks` file, base64-encoded |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key password |

To sign a release build locally, put a `keystore.properties` file (ignored by git) in the project root with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it, `assembleRelease` produces an unsigned APK. Keep a backup of the keystore: if it's lost, installed copies can't be updated and everyone has to reinstall.

## Code layout

```
app/src/main/kotlin/app/audioflix/
  provider/            CatalogProvider interface: list, search, sign in, open a download
    audiovault/        AudioVault scraper (Jsoup) + provider implementation
    gdrive/            Shared Google Drive folders: folder-view parser + provider (no account or API key)
  chapters/            MP4 chapter reader (Nero chpl and Apple chapter tracks), for files and HTTP byte ranges
  net/                 OkHttp client (browser UA for Cloudflare) + persistent cookie jar
  data/                Room database: LibraryItem (title + download state) and Track (per-file progress)
  download/            WorkManager DownloadWorker, zip extraction and track scanning
  player/              Media3 PlaybackService (owns progress saving) + PlayerConnection for the UI
  update/              GitHub Releases check, APK download and verification, PackageInstaller session
  ui/                  Compose screens: Library, Browse, Item detail, Player, Account, Update
```

### How AudioVault works

The site has no API, so the app reads its HTML (see `AudioVaultParser`):

| What | Request |
|---|---|
| Home (featured, recent) | `GET /`: `button.collapsible` + `div.content` for featured titles; `<h5>Recent Movies/Shows</h5>` followed by a table |
| Catalog | `GET /movies?page=N`, `GET /shows?page=N`: `<tbody>` rows of (ID, Name, download link); a `rel="next"` link means there's another page |
| Search | the same URLs with `&search=<query>` added |
| Sign in | `GET /login` for the `_token` CSRF field, then `POST /login` with `_token, email, password, remember=on` (Laravel) |
| Download | `GET /download/{id}`. Without a valid session the site redirects to `/login` |

Every request sends a browser User-Agent, because Cloudflare blocks unknown clients. After sign-in the app keeps only the session cookies, including the long-lived "remember me" cookie. The password is never stored.

### How Google Drive works

Shared folders are read without an account or API key:

| What | Request |
|---|---|
| Folder listing | `GET drive.google.com/embeddedfolderview?id={folderId}`: `div.flip-entry` rows. A link to `/drive/folders/` is a subfolder and `/file/d/` is a file. Folders that aren't public answer 404 |
| Download or stream | `GET drive.usercontent.google.com/download?id={fileId}&export=download&confirm=t`. `confirm=t` skips the virus-scan warning Drive shows for files over about 100 MB. Byte ranges work, so the player can seek and resume |
| Name and size | the same URL with `Range: bytes=0-0`: `Content-Disposition` and `Content-Range` |

When Drive sends an HTML page instead of the file, the app follows its "Download anyway" form once, or explains the problem (not shared, or Drive's download limit for popular files). A Drive library title is `gdrive:folder-{id}` or `gdrive:file-{id}`. Streamed tracks keep their Drive file id (`Track.remoteFileId`), and the player plays the local file when one exists, otherwise the stream.

### How chapters work

`Mp4Chapters` walks the MP4 box tree and reads only `moov`: `mvhd` for the length, `udta/chpl` for Nero chapters, and otherwise the text track that the audio track names in `tref/chap`, whose samples are the chapter titles. Audiobooks usually keep `moov` at the end, after the audio, so over HTTP the reader skips `mdat` by its size and reads 64 KB blocks with range requests. For a 200 MB file that is about six requests. A chapter is a `Track` with `startMs` and `endMs` in its file. The player gives it a `ClippingConfiguration`, and streams go through a 256 MB `SimpleCache`, so each chapter doesn't fetch the file's index again.

### Adding a provider

Implement `CatalogProvider` and register it in `AppContainer` (`AudioFlixApp.kt`). Items are keyed as `providerId:remoteId`. The download pipeline accepts any provider whose `openDownload` returns an audio file or a zip of audio files. Providers whose titles are several files (folders) override `files()`; with `streamUrl()` as well, titles can be streamed without downloading.
