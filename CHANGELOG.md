# Changelog

Every release needs an entry here. The release workflow copies the section for the tagged version
into the GitHub release, and the in-app updater shows it on the update screen.

Add changes under **Unreleased** as you make them, grouped under `### Added`, `### Changed`,
`### Fixed` or `### Removed`. Write each line for listeners: say what changed for them, not how
the code changed. `scripts/release.sh` turns Unreleased into the new version's section.

## [Unreleased]

### Added
- The Library filter and the Browse category can be changed from TalkBack's actions menu, without opening the list.
- Browse results have Download and View summary actions in TalkBack's actions menu. Activating a title still opens its details first.
- Title details in Browse can show a short summary from Wikipedia. Summaries are saved on the phone, so each title is looked up only once.
- AudioFlix keeps to AudioVault's limit of 25 downloads a day. Title details and the Account screen say how many are left and, once they're used up, when the next one is available.
- The Account screen links to AudioVault's support page and Discord.

## [0.1.0] - 2026-10-29

### Added
- Browse AudioVault's featured titles, recently added movies and shows, the full catalogs, and search.
- Download movies and shows for offline listening, with a progress notification, resume after interruptions and an optional Wi-Fi-only setting.
- Library with filters for movies, shows, in progress, downloads and finished titles.
- Player with background playback, lock screen and Bluetooth controls, 10 second back and 30 second forward, episode skipping and speeds from 0.75x to 2x.
- Listening progress saved for every movie and episode, with options to mark them played or unplayed.
- In-app updates: AudioFlix checks for new versions when it starts, shows what changed, and downloads and installs the update. You can also check from the Account screen.
- Screen reader support throughout: labelled controls, headings, spoken durations and announced download progress.
