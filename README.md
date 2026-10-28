# AudioFlix

An Android library for audio-described movies and TV shows. You browse a provider's catalog, download titles for offline listening, and the app remembers where you stopped in every movie and episode.

The only provider today is [AudioVault](https://audiovault.net). Browsing works without an account; downloading needs an AudioVault login.

## Features

- **Browse**: AudioVault's featured titles plus recently added movies and shows, the full movie and show catalogs (they load more as you scroll), and search.
- **Download**: runs in the background with a progress notification and resumes partial files. Shows arrive as a zip, which the app extracts into episodes sorted in natural order (`Episode 2` comes before `Episode 10`). Movies arrive as a single mp3. There's an optional Wi-Fi-only setting.
- **Library**: filter by movies, shows, in progress, downloads or finished. Each title shows its download state or how much you've listened.
- **Player**: plays in the background with a media notification and lock-screen and Bluetooth controls. It has 10 s back, 30 s forward, previous and next episode, and speeds from 0.75x to 2x. It moves to the next episode on its own.
- **Progress**: the playback service saves the position of every track every 5 s, on pause, on seek and on track changes. Finished tracks and titles are marked, and you can mark them played or unplayed yourself.
- **Accessibility**: built for screen-reader users. Controls have labels, headings are marked as headings, and durations are read in words. Download progress is announced, and each episode row has a TalkBack action to mark it played or unplayed.

## Building

Requirements: JDK 17+ and Android SDK platform 37.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # parser and sorting tests
```

## Code layout

```
app/src/main/kotlin/app/audioflix/
  provider/            CatalogProvider interface: list, search, sign in, open a download
    audiovault/        AudioVault scraper (Jsoup) + provider implementation
  net/                 OkHttp client (browser UA for Cloudflare) + persistent cookie jar
  data/                Room database: LibraryItem (title + download state) and Track (per-file progress)
  download/            WorkManager DownloadWorker, zip extraction and track scanning
  player/              Media3 PlaybackService (owns progress saving) + PlayerConnection for the UI
  ui/                  Compose screens: Library, Browse, Item detail, Player, Account
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

### Adding a provider

Implement `CatalogProvider` and register it in `AppContainer` (`AudioFlixApp.kt`). Items are keyed as `providerId:remoteId`. The download pipeline accepts any provider whose `openDownload` returns an audio file or a zip of audio files.
