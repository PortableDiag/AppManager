# App Manager

A small, private **sideload manager for your own Android apps**. It shows each app's
**installed version** next to the **latest version** you've published, and gives you a
one-tap **Install / Update / Open / Remove** for every app in your catalog — no Play
Store, no accounts, no analytics.

> Package: `com.appmanager` · minSdk 26 (Android 8.0) · targetSdk 35

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
- **Install / Update** streams the APK through Android's `PackageInstaller` (the same
  session API the Play Store uses) with a live download + install progress bar.
- **Open** launches an installed app; **Remove** hands off to the system uninstall dialog.
- **Search, sort & filter** — search by name/package, sort by status / name / recently
  updated, and filter with chips (All · Updates · Installed · Not installed). Your sort and
  filter choices are remembered.
- **Pull to refresh** re-reads the catalog and re-checks what's installed.
- **Blue→green theme** shared with the other apps, light / dark / follow-system.

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
  `Last-Modified`/`ETag` so it only re-downloads when it changes.
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
  "theme": "system"
}
```

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
| `InstallReceiver` | Catches the session callback — launches the confirm dialog, then reports success/failure. |
| `MainActivity` | The list, refresh, progress, and permission flow. |
| `SettingsActivity` | Repository URL, local-folder path, theme. |

No ads, no analytics, no background services. It only does something when you tap.
