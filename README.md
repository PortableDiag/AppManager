# App Manager

A small, private **sideload manager for your own Android apps**. It shows each app's
**installed version** next to the **latest version** you've published, and gives you a
one-tap **Install / Update / Open / Remove** for every app in your catalog — no Play
Store, no accounts, no analytics.

> Package: `com.appmanager` · minSdk 26 (Android 8.0) · targetSdk 35

**App Manager updates itself:** it always checks its own GitHub repo
([`PortableDiag/AppManager`](https://github.com/PortableDiag/AppManager)), so it shows up in
its own list and updates like any other app. Turn on **Settings → Automatic updates**
(Daily / Weekly) to get a background check that notifies you when updates are waiting — and
flip **Auto-update this app** on any app's details page to have that one installed for you.
See [Automatic updates](#automatic-updates).

Built to manage the other apps in this workspace (Lull, and friends). Install App Manager
once, point it at your apps, and update them from your phone.

---

## What it does

- **One list, two versions per app.** Each card shows `Installed 1.3 → 1.4` when an update
  is waiting, `Up to date · 1.4` when it isn't, or `Not installed · Latest 1.4` for apps
  you haven't put on this device yet.
- **Tap an app for details** — description, release notes / changelog, source, versions,
  min/target Android, download size, **install / last-updated dates**, **last used**, a link
  to its page, a **Share APK** action, and the same install/remove actions. For GitHub
  sources the description comes from the repo and the changelog from the latest release;
  index entries can carry `description` / `changelog` / `url`. ("Last used" uses the optional
  Usage-access permission; the row links to the settings toggle until you grant it.)
- **Update all** — one action installs every pending update in turn (each still shows
  Android's per-app install confirmation, since App Manager isn't a privileged installer).
- **Automatic updates** — an optional background check that notifies you when updates are
  waiting, plus a per-app **Auto-update this app** switch that installs them for you. See
  [Automatic updates](#automatic-updates).
- **Install / Update** streams the APK through Android's `PackageInstaller` (the same
  session API the Play Store uses) with a live download + install progress bar.
- **Open** launches an installed app; **Remove** hands off to the system uninstall dialog.
- **Search, sort & filter** — search by name/package, sort by status / name / recently
  updated, and filter with chips (All · Updates · Installed · Not installed). Your sort and
  filter choices are remembered.
- **All seventeen house palettes** — Material You (wallpaper colours, the default), Ocean,
  Terminal, Trellis, Graphite, Blueprint, Futuristic, Neon, SynthWave, White Glow,
  Silkscreen, Sticky Notes, Amber Terminal, Phosphor Green, Phosphor P31, Zoro and Nebula.
  Settings → Color theme. Material You, Ocean and Terminal follow light / dark /
  follow-system; every other palette is one look. The terminal palettes set the type in
  monospace, and the status-bar icons follow the palette, not the phone's mode.
- **Pull to refresh** re-reads the catalog and re-checks what's installed.
- **A local API** (off by default) so a script or agent on the phone can do everything the
  screens do — see [The local API](#the-local-api).

It never phones home. The only network it touches is the repository URL **you** give it.

---

## Where it gets apps

App Manager builds its catalog from a list of **sources** plus a **local folder** —
whichever offers the higher `versionCode` for a package wins, so an app with no source
listed just falls back to a local APK.

### Adding sources fast

Three ways, no typing required:

- **Share a link in.** Browsing a repo on your phone? **Share → App Manager** adds it as a
  source and refreshes.
- **Copy + tap ＋.** Copy any repo/APK link, then tap **＋** on the main screen.
- **Settings → App sources.** Paste/edit the full list, one URL per line.

### Favorite devs — follow a GitHub user, get all their apps

In **Settings → Favorite GitHub devs** (one username per line), or by sharing / pasting a
`github.com/username` link, add a developer. On refresh, App Manager enumerates their
**public** repos, keeps the ones that publish an **APK release**, and adds each
automatically — no per-app setup.

It's cache-friendly: the repo list is fetched with a conditional request (an unchanged
list returns `304`, which is free and doesn't count against GitHub's rate limit), and a
repo's releases are only re-checked when that repo has changed. So steady-state refreshes
cost about one free request per followed dev.

> Public repos only (no token sent). A followed user and a single repo are told apart by
> shape: `github.com/user` = follow the dev; `github.com/user/repo` = that one app.

### What a source URL can be

Each source line is one of three kinds:

- **A GitHub repo page** — `github.com/you/app`. The **easiest**: the latest release's APK
  is found automatically via the GitHub API, so there's no asset-URL to construct and no
  fixed asset-name requirement. (Public repos; per-ABI split releases resolve to the
  universal APK when present.)
- **A direct `.apk` URL** — fetched, version-read from the file, and cached via
  `Last-Modified`/`ETag` (or unchanged file size) so it only re-downloads when it changes.
  A URL that *serves* an APK but doesn't end in `.apk` (e.g. `telegram.org/dl/android/apk`,
  or a MindSwarm download link) also works — it's detected by content-type / attachment
  filename / final redirected path.
- **Any other URL** — treated as a repo/index JSON listing several apps.

Details on the last two:

**A. Direct `.apk` URL — one app, no index to maintain.** Point each app at a stable APK
URL your phone can reach:

```
http://192.168.0.51/apks/lull.apk
http://192.168.0.51/apks/audioplayer.apk
```

On refresh the manager fetches each one, reads the version **out of the APK itself**, and
shows Update when it's newer than what's installed. It's cached with the server's
`Last-Modified`/`ETag`, so an unchanged file isn't re-downloaded — and because the fetched
file *is* the install source, tapping Update installs instantly. **You publish once per
build** (drop the new APK at that URL, overwriting the old one); the phone picks it up on
the next refresh. No per-device copying, no index file, no bumping a version number by
hand.

**B. Any other URL — a repo/index JSON listing several apps.** Handy when you'd rather keep
one file for many apps:

```json
{
  "apps": [
    { "package": "com.lull.player", "label": "Lull",
      "versionName": "1.4", "versionCode": 5, "apk": "lull-1.4.apk" }
  ]
}
```

- `apk` is an absolute URL, or a path **relative to the index URL**.
- `versionCode` must increase each release — that's how an update is told apart from
  what's installed. (`versionName` is just the label shown to you.)

A working example is in [`repo-example/index.json`](repo-example/index.json), and
[`make-repo.sh`](make-repo.sh) generates the index from a folder of APKs:

```bash
./make-repo.sh /path/to/your/apks > /path/to/your/apks/index.json
```

You can mix all three kinds freely — some GitHub repo pages, some direct `.apk` lines,
some index lines.

#### If your APK filename includes the version (`lull-1.4.apk`)

A direct `.apk` URL must be **stable**, so a filename that changes every release (a version,
date, or ABI in the name) can't be a direct URL — it would 404 on the next release. Handle
it by *not* linking the file directly:

- **On GitHub — just use the repo URL** (`github.com/owner/repo`) or follow the dev. The API
  resolves whatever the asset is named each release; the version-in-the-filename is
  irrelevant. This is the recommended path.
- **On a plain web server** — either point an **index JSON** at the versioned file and bump
  its `apk` field each release, or publish a **stable alias** (a `latest.apk` symlink /
  redirect to the newest file) and use that as the direct URL.

The only thing to avoid is a direct `…/releases/latest/download/lull-1.4.apk` URL — that
pins the old filename. Use the plain repo URL.

### Backup & restore (JSON)

**Settings → Backup & restore** exports everything — sources, favorite devs, local folder,
theme — to a JSON file you pick, and imports one back. Handy for moving your setup to
another device or keeping it in version control. See
[`settings-example.json`](settings-example.json) for the shape:

```json
{
  "version": 1,
  "sources": ["github.com/you/lull", "https://example.com/repo/index.json"],
  "favoriteDevs": ["you"],
  "localDir": "",
  "theme": "system",
  "palette": "Zoro"
}
```

`palette` is a name from the list above (`WhiteGlow`, `White Glow` and `white-glow` all
work). Files from before 1.7 with `"ocean"` or `"terminal"` still import.

### The local folder (no server needed)

App Manager scans a folder on the device for APKs. **The folder is configurable in
Settings → Local APK folder:**

- **Blank (default)** — the app's own private folder, which needs **no permission**:
  ```
  /sdcard/Android/data/com.appmanager/files/repo/
  ```
  The exact path is shown in Settings with a **Copy default path** button.
- **Any path you set** — e.g. `/sdcard/MyApks` or a folder your NAS syncs down. Reading a
  folder outside the app's own storage needs **all-files access** (Android 11+) or the
  read-storage permission (Android 10 and below); App Manager prompts for it the first
  time it scans a custom folder.

Drop signed APKs in the chosen folder — over USB (`adb push …`) or with any file manager —
and pull to refresh.

```bash
# default private folder
adb push app/build/outputs/apk/release/app-release.apk \
  /sdcard/Android/data/com.appmanager/files/repo/

# or your own configured folder
adb push app/build/outputs/apk/release/app-release.apk /sdcard/MyApks/
```

---

## Automatic updates

Off by default. Two layers, and you need the first for the second to do anything.

### 1. The background check — Settings → Automatic updates

**Off · Daily · Weekly.** When on, App Manager re-reads your sources in the background and
posts a notification listing what's waiting, with an **Update all** action. That's all it
does on its own: it tells you, you tap.

### 2. Per-app auto-install — the switch on an app's details page

**Auto-update this app** (off by default, per app) tells the background check to actually
install that app's updates instead of only mentioning them. What happens then depends on
whether App Manager is the app's installer:

| Situation | Result |
|---|---|
| App Manager installed the app, Android 12+ | Installs **silently** — nothing to tap |
| Anything else (installed by another store, sideloaded by hand, Android 11 or older) | Posts an **"update ready"** notification; one tap runs the normal install prompt |

Turning the switch on schedules a **daily** check if automatic updates were Off, and says so
— the switch is useless without one, so it stops being a no-op switch.

**On mobile data, opted-in apps are not auto-installed** — an APK is a big download, so they
fall back to notifying you and install once you're on Wi-Fi. The check itself still runs
anywhere.

**Failures are reported.** If an update can't install — a signature mismatch, no space, a bad
APK — you get a notification naming the app and the reason, rather than it quietly never
happening. Backing out of an install prompt yourself isn't treated as a failure.

> Silent installs need `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (a normal-level permission,
> granted at install with no prompt) and only ever apply to apps App Manager installed
> itself. It can't silently update anything else — Android doesn't allow it, and neither can
> any non-privileged installer.

Your per-app choices are included in **Backup & restore** as `autoUpdateApps`.

---

## The local API

**App Manager can be driven by a script or an agent** — everything the screens do, over HTTP
on this device. Overflow menu → **Local API…**: switch it on, and App Manager generates a key,
shows the address, and that is the whole setup. It is shaped like Lull's, so PhoneAPI can call
it as a provider; pairing agents and per-agent permissions live in PhoneAPI, not here.

```bash
KEY=<copied from the app>          # adb forward tcp:8797 tcp:8797, from a laptop
curl -s -H "X-API-Key: $KEY" 127.0.0.1:8797/api/docs             # the full reference
curl -s -H "X-API-Key: $KEY" 127.0.0.1:8797/api/updates
curl -s -H "X-API-Key: $KEY" -X POST 127.0.0.1:8797/api/apps/com.lull.player/install
curl -s -H "X-API-Key: $KEY" 127.0.0.1:8797/api/apps/com.lull.player/install   # poll it
curl -s -H "X-API-Key: $KEY" -X PATCH 127.0.0.1:8797/api/settings -d '{"palette":"Zoro"}'
```

| | |
|---|---|
| **Catalog** | every app with filter / search / sort as on the list screen, reload from every source, one app's details, **its APK** |
| **Installs** | install or update one app, Update all, poll each install (`downloading` → `installing` → `awaiting_confirmation` → `installed` / `failed` / `cancelled`) |
| **Apps** | remove, open, share, and the per-app auto-update switch |
| **Sources** | list, add (a `github.com/<user>` link follows the dev, as sharing one does), replace, remove; the same for followed devs |
| **Settings** | palette, light/dark, background checks, local folder, sort, filter; export, import, import the official config; list the palettes |

**The rules it is built to.**

- **Off until you turn it on**, in its own foreground service with a notification naming the
  address (and a Stop action). It comes back after a reboot only if it was on.
- **Bound to `127.0.0.1`** unless you switch on LAN access. Default port **8797**.
- **The key is generated, never typed**, compared in constant time, and never exported with
  your settings. Anything but `GET /api/health` without it is a `401`. *New key* invalidates
  the old one at once.
- **Nothing the UI can't do.** An install from the API goes through the same installer as the
  Install button; a source added here appears in the list while you watch.
- **Honest about the screen.** Android won't let an app that is off screen start an activity.
  With App Manager open, an install, remove or open shows its dialog as usual. In the
  background, an install runs like an auto-update (silent on Android 12+ for apps App Manager
  installed, otherwise a one-tap notification), and remove / open / share post a
  tap-to-finish notification — the response says which (`launched`, `notified`).
- **`GET /api/docs` serves the whole reference**, so it cannot drift from the build answering.

---

## Building

Same toolchain as the other apps here (Gradle wrapper, Java 17, SDK at
`/home/null/android-sdk`).

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk (signed)
```

Release signing reads `keystore.properties` (already present, pointing at
`appmanager-release.jks`). To use your own key, copy `keystore.properties.example` and
fill it in, or regenerate:

```bash
keytool -genkeypair -v -keystore appmanager-release.jks -alias appmanager \
  -keyalg RSA -keysize 2048 -validity 10000
```

> Keep the keystore stable: to update App Manager over an earlier install, both APKs must
> be signed with the **same** key. The same rule applies to every app it installs — this
> tool just delivers the APK; Android still enforces matching signatures on update.

---

## First-run permission

Installing apps needs **"Install unknown apps"** for App Manager. The first time you tap
Install/Update it sends you to that system toggle; enable it and tap again. This is the
standard Android sideload prompt — no root, no special privileges.

---

## How it fits together

| Piece | Role |
|---|---|
| `AppRepository` | Merges the remote index + local APK folder, then checks each package against what's installed via `PackageManager`. |
| `Installer` | Downloads the APK and drives a `PackageInstaller` session; also fires the uninstall intent. |
| `InstallReceiver` | Catches the session callback — launches the confirm dialog, then reports success/failure (including failed background installs). |
| `MainActivity` | The list, refresh, progress, and permission flow. |
| `DetailsActivity` | The per-app page, Share APK, and the per-app auto-update switch. |
| `SettingsActivity` | Repository URL, local-folder path, palette and light/dark, backup/restore. |
| `Palette` / `res/values*/palettes.xml` | The house palettes — generated by `tools/gen-palettes.py` from ProjectForge's `palettes.toml`; don't edit by hand. |
| `api/` | The local API: socket server, router, docs, and the backend that calls the same code as the screens. JVM-tested (`./gradlew :app:testDebugUnitTest`). |
| `UpdateScheduler` / `UpdateWorker` | Schedules the periodic check and runs it: reload the catalog, auto-install opted-in apps, notify about the rest. |
| `Notifier` | The updates-waiting, update-ready, and update-failed notifications. |

No ads, no analytics, no tracking. The only background work is the update check you asked
for in Settings, and the local API if you switched it on — with both off, App Manager does
nothing until you tap.
