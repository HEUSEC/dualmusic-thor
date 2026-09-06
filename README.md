# DualMusic — dual-screen music app for the AYN Thor

Two panels on the Thor's two displays: a big, glanceable now-playing screen and a touch
control panel. The goal is a music front-end that is better than Apple Music's own
Android app, so it has to *start* music, not only control what is already playing.

Kotlin, `minSdk 33`, arm64. Framework APIs only, except the Spotify SDK.

The UI takes the *DualMusic Redesign* canvas' light "console" look — mint ground, one
rounded shell per panel, a floating dock — but drops its badge layer. Pills, chips and
rotating tile gradients were carrying no information, so hierarchy is size, weight and
space instead, and colour is left to the artwork.
Every value in the layouts is that canvas divided by 2.31 (369 dpi), so 1080 px in the
artboard is 468 dp on the device. Built entirely from framework widgets and XML shape
drawables: no component library, no icon pack, no bitmaps.

## What each source can actually do

The three sources are not equal, and the difference is imposed from outside:

| Source | Browse & start playback | Control what plays | How |
|---|---|---|---|
| Spotify | yes (Premium) | yes | App Remote SDK, `ContentApi` + Web API library |
| Apple Music | **no** | yes | MediaSession only |
| Local MP3 | yes (planned) | yes | MediaStore + our own player |

**Apple Music refuses third-party clients.** It does expose a `MediaBrowserService`
(`com.apple.android.music.player.MediaPlaybackService`), but its `onGetRoot()` returns
null for us — verified on the device with `BrowseProbeActivity`:

```
MediaBrowserService: No root for client com.dualmusic.thor
BrowseProbe: RESULT connection REFUSED
```

That allowlist has no legitimate workaround. The only supported way to start Apple Music
playback from another app is the MusicKit SDK for Android, which needs a developer token
and therefore a paid Apple Developer Program membership; that SDK is also stale (Javadoc
generated 2019, still hand-downloaded AARs). Decision: **Apple Music stays a remote
control** — transport, metadata and artwork through MediaSession, plus a button that
hands the user over to the app to start something.

**But `ContentApi` is not your library.** `getRecommendedContentItems` returns Spotify's
editorial sections and nothing else, whichever root type is asked for: `default`,
`navigation` and `automotive` were all run against the account on the device and all
answered with the same thirty rows — "Buonasera", "Stazioni consigliate", "Creato per
HEUSEC". There is no node for saved tracks and none for the user's own playlists, and
every one of those rows carries an *empty* image id, which is why they had no covers.
So the root of the browse tree is the library from the Web API — `/me/playlists`,
`/me/tracks`, `/playlists/{id}/tracks` — and Spotify's recommendations are one row
inside it rather than the whole screen. Their covers are ordinary https URLs, fetched
directly, while App Remote rows keep going through `ImagesApi`; `ArtworkLoader` is the
one place that knows the difference.

**And the Web API only lends you your own playlists.** Two things had to be measured
against the live API rather than read in the documentation. `/playlists/{id}/tracks`
answers 403 Forbidden while `/playlists/{id}` answers 200 — the paging object inside it
points at `/playlists/{id}/items`, and each entry there wraps the track as `item`, not
`track`, the way `/me/tracks` still does. And of the playlists `/me/playlists` returns,
`/items` succeeds for every one the user owns and fails with 403 for every one they only
follow: twelve checked, the split exactly on ownership. So a followed playlist falls back
to App Remote, which has no such rule, and the row leads somewhere either way.

**Spotify collaborates.** App Remote drives the installed Spotify app: `ContentApi`
gives the same browse tree Spotify exposes to car head units (no separate OAuth token
needed) and `playContentItem` / `play(uri)` start playback. Playing a *specific* track
URI requires Premium — `UserApi.getCapabilities().canPlayOnDemand` is checked at connect
time rather than discovered through a failed `play()`.

### The consent flow, and why we send the SSO intent ourselves

Connecting App Remote for the first time fails with `UserNotAuthorizedException` until
the user approves the app inside Spotify. Two things about that flow cost a long
debugging session and are worth writing down:

1. **The dashboard entry is what Spotify's app checks.** With the Android package
   unregistered, Spotify's own SSO activity rejects the request in ~250 ms with
   `AUTHENTICATION_SERVICE_UNAVAILABLE` — an error that looks like a network or SDK
   fault and is really "I have never heard of this package". The browser OAuth flow
   still worked at that point, because it validates only the client ID and redirect URI,
   which is what made the difference diagnosable.
2. **The web grant is not the App Remote grant.** Completing OAuth in the browser
   records a server-side grant, and App Remote still refused: it checks an authorisation
   held by the Spotify app itself. Only the in-app SSO screen creates that.

`SpotifyNativeAuth` therefore sends `com.spotify.sso.action.START_AUTH_FLOW` directly
instead of going through `AuthorizationClient`, which swallows Spotify's own ERROR extra
and reports its generic code. It keeps the SDK's signature check (the same six hashes,
compared the way the SDK computes them — SHA-1 over `Signature.toCharsString()`, not the
certificate fingerprint `apksigner` prints) so we still refuse to hand the user to an
impostor.

## Setup required before Spotify works

1. Install Spotify on the Thor and log into the Premium account.
2. Create an app at <https://developer.spotify.com/dashboard> with:
   - redirect URI: `dualmusic://auth`
   - Android package: `com.dualmusic.thor`
   - SHA-1 of the signing key — for debug builds from this machine:
     `AB:43:61:69:93:1C:86:4C:29:0D:2E:26:37:A4:DD:87:32:CA:D1:A0`
     (`~/.android/debug.keystore`; a build on another machine needs that machine's
     fingerprint added too)
3. Put the Client ID in `local.properties` as `spotify.clientId=<id>` (or export
   `SPOTIFY_CLIENT_ID`). It is never committed; a build without it simply runs with
   Spotify switched off.

The Android package entry is not optional and not part of the create-app form: add it
under **Settings → Android Packages**, and check the row is still listed afterwards.
Without it every consent attempt fails with `AUTHENTICATION_SERVICE_UNAVAILABLE`.

Check it with the probe:

```sh
adb shell am start -n com.dualmusic.thor/.SpotifyProbeActivity     # connect + dump tree
adb shell am start -n com.dualmusic.thor/.SpotifyProbeActivity -e native true   # consent
adb logcat -s SpotifyProbe SpotifyRemote SpotifyNativeAuth SpotifyBrowser
```

## Display layout — what the hardware dictates

Measured with `dumpsys display` on the Thor:

| Display | Size | Flags | Role |
|---|---|---|---|
| `0` | 1080x1920 (~6.9") | default display, no `FLAG_PRESENTATION` | hosts the Activity |
| `4` | 1080x1240 | `FLAG_PRESENTATION`, own touchscreen | hosts the Presentation |

`Presentation.show()` on display 0 throws `InvalidDisplayException`: that display does
not carry `FLAG_PRESENTATION`. So the *window host* of each panel is fixed by the system
— Activity on 0, Presentation on 4. What we choose is which *content* each host draws,
and that is what the swap button flips (`DisplayRouter.Plan.controlsOnPresentation`),
stored in SharedPreferences. Default: controls on the small panel, now playing on the big.

Touch does reach a Presentation on display 4 — verified by tapping its swap button and
watching the panels exchange.

## Permission

`MediaSessionManager.getActiveSessions()` requires an *enabled notification listener* as
proof of authorisation, which is why `MediaNotificationListener` exists — it reads no
notifications at all. Grant it in-app (the banner opens the per-app settings screen) or:

```sh
adb shell cmd notification allow_listener com.dualmusic.thor/com.dualmusic.thor.MediaNotificationListener
```

## Design notes

- **Nothing decorative on screen.** No source badge, no album chip, no year chip, no
  "lyrics on" chip, no coloured tiles: each was a container around a word. The cover is
  shown whole (`fitCenter`) over a blurred copy of itself, so the artwork is never
  cropped and the card still takes the record's colour. Thumbnails in the browse list
  are real Spotify cover art via `ImagesApi`, and rows whose item has no image simply
  have no thumbnail rather than an empty grey square.
- **Two densities in one list.** The top level of the browse tree is a two-column grid of
  glossy tiles; deeper levels are single-column white rows. Same `GridView`, switching
  `numColumns` and the item layout, so scroll position and recycling behave normally.
- **Degradation is layout, never apology.** No artwork turns the card into a flat mint
  gradient (no placeholder glyph); no lyrics removes the whole panel and the artwork card
  takes the full width (no "no lyrics" message); no album or year drops that chip and the
  rest slide left; no duration removes the progress bar and the clock shows elapsed only;
  an unknown title falls back to the source app's name rather than the word "Unknown".
- **Edge to edge.** Both windows hide the system bars, so the mint ground runs to the
  panel edges as the canvas draws it.

- **Queue, volume and the gamepad are the session API's, not Spotify's.** The queue
  button appears only when the player publishes one (`MediaController.getQueue()`), and
  tapping a row is `skipToQueueItem`, so it works for any player — including Apple
  Music, which gives us nothing else. Spotify publishes an empty queue
  (`queueTitle=, size=0` in `dumpsys media_session`), so with Spotify the button simply
  is not there.
- **The dock holds only what the far panel cannot.** The cover, the title and the artist
  are already on the other screen, so the control panel keeps the clock, the seek bar and
  the transport, and nothing else: no volume slider, no status line, no swap or Apple
  Music buttons. It had grown to nearly half the small panel, which is the half the list
  needed. The volume keys own the volume — a locally rendered session has none of its own
  (`volumeType=1, max=0`), so only a session playing elsewhere is intercepted — and the
  gamepad Y swaps the panels.
- **One density in the list.** Two column-widths and two row layouts were a design idea,
  not a reading aid, on a panel this size: every level is now one column of 62dp rows
  with a 44dp cover.
- **Shuffle and repeat are not in the platform API at all.** Not on `PlaybackState`, not
  on `MediaController`, not on `TransportControls` — they exist only in
  `MediaSessionCompat`, and from a token obtained through `getActiveSessions()` the
  compat layer can send `setRepeatMode` but never read the mode back. A switch that
  cannot show its own state is worse than no switch, so there is none.
- **Lyrics survive a restart.** Each lookup is written to `cacheDir/lyrics` as its kind
  and its raw LRC, so the same song costs LRCLIB nothing twice. Misses are cached too,
  but expire after a week: a song missing today may be added next month.

- **Position is interpolated, never polled.** `PlaybackState` gives a position at an
  instant plus a speed; `MediaHub.Track.positionNowMs()` extrapolates and a 250 ms
  ticker repaints only the progress row.
- **One state layer for every source.** Spotify publishes a normal MediaSession, so what
  is playing always reaches the screens through `MediaHub`; `SpotifyRemote` deliberately
  does not duplicate that state and only handles browsing and starting playback.
- **Session choice:** follow whatever is playing, stay on it while it plays, and let an
  explicit user pick (the "next app" button, shown only with 2+ sessions) win over both.
- **Advertised actions:** `PlaybackState.getActions()` disables unsupported buttons, but
  an `actions` bitmask of exactly 0 is treated as "unknown, allow" — a player reporting
  nothing is far likelier to be lazy than genuinely uncontrollable.
- **Single-screen fallback:** with no presentation-capable display, both panels stack in
  the Activity. Same path if `show()` fails or the panel is unplugged.

## Verified on the device

Against a live Spotify session (Softcore / The Neighbourhood):

- Artwork, title, artist, album, duration and interpolated position on the big panel;
  title, artist and transport on the small one.
- Play/pause from our button drives the session: `state=3 -> 2 -> 3`, and the icon flips
  from the controller callback, not from a local guess.
- Session label and per-session status line (`controls #4 / art #0 · [Spotify]`).
- Both panels render on their displays, no crash; clean empty state when nothing plays.
- Swap works in both directions, driven by touch on the secondary display.

The library and the controls, on the same device:

- The volume slider moves the music stream, and reads back what the system reports.
- `BUTTON_A` toggles playback (`state=2 -> 3`), `R2`/`L2` seek by ten seconds
  (`67.8s -> 85.8s -> 76.9s`), `L1`/`R1` skip; the D-pad is left to the list.
- Lyrics land in `cache/lyrics` as `synced` plus the LRC, one file per song.
- The root lists 40 rows — Liked Songs, 38 playlists, Made by Spotify — with their real
  covers over https, and tapping a track in Liked Songs starts it (`spotify:track:…`
  through `playUri`, since a Web API row is not a node App Remote can resolve).
- The queue button appears once Spotify publishes a queue and stays hidden when it does
  not. Opening it from reading mode drops reading mode first: they draw in the same
  area, and both at once was a glitch, not a layout.
- Your own playlist opens over the Web API; a followed one (403) falls back to App
  Remote and loads its thirty rows from there.

And end to end with Spotify:

- Consent completes, App Remote connects, `canPlayOnDemand=true` (Premium).
- The browse tree loads: 15 personalised sections, `Creato per …` opens 9 Daily Mixes
  with their subtitles, Back walks up the tree.
- Tapping *Daily Mix 2* (`spotify:playlist:37i9dQZF1E38R1Li6kaVik`) starts playback, and
  the big panel picks it up through MediaSession with artwork, album and progress — the
  browse layer never touches the display layer.

## Next

1. Local MP3 source: MediaStore + ID3 + our own `MediaSession`. It is also the only way
   to get a queue this app controls, since Spotify publishes none.
2. Colour from the artwork: tint the shell with the cover's own hue instead of the fixed
   mint, which is what the design notes already promise.
3. Ambient mode on the big panel once nothing has played for a while, with a pixel shift
   — two panels stay lit for hours on a handheld.

## Toolchain on this machine

No Android Studio; everything standalone:

- SDK `~/android-sdk` (platform 34, build-tools 34.0.0, platform-tools)
- JDK 21 `~/jdk21` (system JDK 25 is too new for AGP 8.7)
- Gradle 8.14.3 `~/gradle-8.14.3`

```sh
./build.sh                                          # build + install
adb exec-out screencap -d 4630946441858561667 -p > big.png    # display 0
adb exec-out screencap -d 4630946482288158084 -p > small.png  # display 4
adb shell input -d 4 tap X Y                                  # tap on display 4
```

`screencap -d` wants the *physical* display id, not the logical one.

## Security notes

The app holds two things worth protecting — a Spotify refresh token and notification
listener access — so:

- **No client secret anywhere.** The Web API side is Authorization Code + PKCE, the
  flow meant for apps that cannot keep a secret. The client ID is not one; it is kept
  out of the sources only so a clone talks to its own Spotify registration.
- **The redirect is not trusted.** `dualmusic://auth` is a custom scheme, which any app
  on the device may also register or send. PKCE stops a stolen code being redeemed by
  someone else, but not a *foreign* code being pushed at us, so the authorisation
  request carries an unguessable `state` and a redirect that does not return it is
  dropped. Verifier and state are one-shot.
- **Backup is off** (`allowBackup="false"`): the refresh token lives in
  SharedPreferences, which is private to the app on the device, and cloud backup is the
  one thing that would carry it off the device.
- **Diagnostics do not ship.** The two probe activities have to be exported to be
  launchable from adb, so they live in `src/debug` and are absent from a release build.
- **The notification listener reads no notifications.** It exists only because holding
  that access is what unlocks `MediaSessionManager.getActiveSessions()`; it overrides
  neither `onNotificationPosted` nor `onNotificationRemoved`.
- **Spotify is verified before the handover.** `SpotifyNativeAuth.verifySpotify()`
  checks the installed Spotify's signature against the SDK's own list before sending it
  an auth intent, so the consent screen cannot be an impostor's.

Nothing here works around another app's access control: Apple Music refuses third-party
browse clients, and this app takes that answer and stays a remote control for it.

## Third-party code

- **Spotify App Remote SDK 0.8.0** — Apache License 2.0, © Spotify AB, from
  [spotify/android-sdk](https://github.com/spotify/android-sdk). The AAR is not kept in
  this repository; `build.sh` downloads the official release into `app/libs` and checks
  its SHA-256.
- **Gson**, Apache 2.0. **androidx.browser**, Apache 2.0.
- The queue icon in `res/drawable/ic_queue.xml` uses Material Symbols path data,
  Apache 2.0, © Google; everything else on screen is a shape drawable of our own.
- Lyrics come from [LRCLIB](https://lrclib.net), a free open database, over its public
  API with an identifying User-Agent, and are cached so a track is asked for once.

## License

MIT — see [LICENSE](LICENSE). This is a personal project and is not affiliated with,
endorsed by, or connected to Spotify or Apple.
