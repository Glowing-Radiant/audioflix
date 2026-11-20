# Changelog

Every release needs an entry here. The release workflow copies the section for the tagged version
into the GitHub release, and the in-app updater shows it on the update screen.

Add changes under **Unreleased** as you make them, grouped under `### Added`, `### Changed`,
`### Fixed` or `### Removed`. Write each line for listeners: say what changed for them, not how
the code changed. `scripts/release.sh` turns Unreleased into the new version's section.

## [Unreleased]

## [0.7.1] - 2026-11-20

### Added
- Recordings from the newer kinds of website sources show their description: when you choose whether to stream or download one, and on its page in your library once it's added.

## [0.7.0] - 2026-11-20

### Added
- More kinds of websites work as a website source. On some sites where people share recordings, paste a person's profile page to browse everything they've shared and add it all as one title, or paste the page of one recording to stream it or download it.

## [0.6.0] - 2026-11-19

### Added
- Settings, under App updates, lets you choose how many connections an update downloads over at once, from 1 to 8. More is usually faster; on a weak or patchy network, fewer can be more dependable. It's 6 unless you change it.

### Changed
- App updates cope better with weak or patchy networks. Each part of the download gets more tries, and if the connection keeps dropping, the download carries on by itself over fewer connections, saying so on the update screen.
- Every downloaded update is checked before it's installed: its size and fingerprint against what was published, and that Android can read it as the right version of AudioFlix, signed the same way. One that arrived damaged is downloaded again once over a single connection. If Android still can't read it, trying again downloads it afresh instead of reusing the damaged copy.

### Fixed
- An update download that takes more than an hour no longer fails when the download link expires: AudioFlix gets a new link and carries on.
- If an update was replaced on the server part way through a download, the two versions could be pieced together. Pieces from a different version are now thrown away.

## [0.5.0] - 2026-11-19

### Added
- Phone folders as a fourth source in Browse: choose a folder on your phone, SD card or USB drive with the system folder picker, and browse it like a Drive folder. A whole folder or a single file can be played where it is, or copied into AudioFlix so it stays even if the folder is moved. Copying works without an internet connection.
- Faster downloads for AudioVault, Google Drive and website folders: a file comes down over several connections at once, the same way app updates do. Each source has its own switch in Settings, with what it means for that source. It's on for Google Drive and website folders, and off for AudioVault unless you turn it on, since AudioVault is a small volunteer-run site.
- Auto-rewind, in Settings under Playback: when you press play after pausing, AudioFlix goes back 5 seconds, or 30 seconds if the pause was 30 minutes or longer. It's off unless you turn it on, and all three amounts can be changed. It also works after a phone call, unplugging headphones, or closing the app.
- The Library can be sorted by recently played, title, or source. Sorted by source, each source has its own heading.
- A streamed folder's page has a button to browse that folder at its source, and a "Check the folder for new files" option that adds files put there since.
- Titles with subfolders, such as seasons, list each subfolder's tracks under its own heading.

### Changed
- The Account tab is now Settings, in categories: Playback, Downloads and storage, a page for each source (its account, faster downloads and support links), and App updates.
- Adding a title for streaming says straight away that it's working, and carries on if you leave the screen. The result is announced wherever you are.

### Fixed
- A Drive or website folder added for streaming could show up in the Library with nothing in it, for good, if you left the folder screen while its files were still being read (which can take a while for audiobooks). Titles are now added only once all their files are read, and a title left empty this way is repaired when you open it.

## [0.4.0] - 2026-11-18

### Added
- Website folders as a third source in Browse: paste the address of a folder a website lists its files in, like https://example.com/audio/, and browse its folders with nothing to sign in to. Folders you open are kept under "Your folders", and a whole folder (subfolders included) or a single file can be added to your library to stream or to download, the same as a Google Drive one. Zip files can only be downloaded, and addresses have to start with https://.

### Changed
- The address box in Browse keeps what you typed for each source separately, so switching source doesn't clear it.

## [0.3.1] - 2026-11-17

### Changed
- App updates download much faster: the update now comes down over several connections at once, and if a download is interrupted, trying again carries on where it stopped instead of starting over.

## [0.3.0] - 2026-11-17

### Added
- Google Drive as a second source in Browse: paste a link to a folder or file shared as "Anyone with the link" and browse its folders, with no Google account. Folders you open are kept under "Your folders".
- Add a whole Drive folder (subfolders included) or a single file to your library, either to stream or to download. Streaming plays straight from Drive and takes no space; downloads work offline, and zip files in them are unpacked.
- A streamed title can be downloaded later from its page, keeping your listening progress, and a downloaded one can go back to streaming with "Remove download, keep streaming".
- The player says why playback stopped when a file can't be played, for example when Drive is limiting a popular file.
- Audiobook files (m4b and m4a) with chapters show each chapter as its own track, streamed or downloaded. Next and previous move between chapters, listening progress is kept per chapter, and when a title has several books, the track list puts each book's chapters under its own heading.
- Streamed audio is kept in a small cache on the phone, so moving between chapters or going back doesn't download the same part again.

## [0.2.0] - 2026-10-29

### Added
- The Library filter and the Browse category can be changed from TalkBack's actions menu, without opening the list.
- Browse results have Download and View summary actions in TalkBack's actions menu. Activating a title still opens its details first.
- Title details in Browse can show a short summary from Wikipedia: the first few sentences of the film's plot or the show's premise, so it says what happens without giving away the ending. Summaries are saved on the phone, so each title is looked up only once.
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
