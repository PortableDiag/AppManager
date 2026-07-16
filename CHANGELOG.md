# Changelog

## 1.0 — first release
- Catalog view: installed version vs. latest available, per app.
- Install / Update via `PackageInstaller` with live download + install progress.
- Open and Remove (system uninstall) for installed apps.
- Catalog sources, merged by highest versionCode:
  - A list of source URLs, one per line, of three kinds: a **GitHub repo page**
    (`github.com/you/app` — latest release APK resolved automatically via the GitHub API),
    a direct **`.apk`** URL (fetched, version-read from the file, cached via
    Last-Modified/ETag so it only re-downloads when it changes), or any other URL treated
    as a **repo/index JSON**.
  - Fast ways to add a source: **Share** a link into App Manager from a browser, or copy a
    link and tap **＋** on the main screen — no typing.
- **ABI-aware asset choice:** for a GitHub release, a universal APK is preferred; if a
  release ships only per-ABI splits, the one matching this phone's `Build.SUPPORTED_ABIS`
  is chosen (arm64 over its v7a compat split, etc.).
- **Favorite GitHub devs:** add a username and every public repo of theirs that ships an
  APK release is added automatically. On-disk ETag caching keeps refreshes near-free
  against GitHub's rate limit (`github.com/user` follows a dev; `github.com/user/repo` is
  one app).
- **Backup & restore:** export all settings, sources, and favorite devs to a JSON file and
  import it back (Settings → Backup & restore).
- **App details page:** tap any app for a screen with its description, release notes /
  changelog, source, version (available vs installed), min/target Android, download size,
  a link to its page, and the same Install/Update/Open/Remove actions. GitHub sources fill
  description from the repo and the changelog from the latest release; index entries can
  supply `description` / `changelog` / `url` fields.
  - Also shows **install date**, **last-updated date**, and **last used** (last needs the
    optional Usage-access permission — the row links to the settings toggle until granted).
  - **Share APK** action in the details toolbar — exports the app's APK (installed, cached,
    or downloaded) and hands it to the system share sheet with a friendly `Label-version.apk`
    name.
- **Search, sort & filter the list:**
  - Toolbar **search** by name or package.
  - **Sort** by status (updates first), name, or recently updated.
  - **Filter chips** — All / Updates / Installed / Not installed.
  - Sort and filter choices are remembered.
- **Update all:** an overflow action (shown as "Update all (N)") that installs every pending
  update in turn. The install-result receiver now spans the activity lifetime so the batch
  keeps advancing through each app's confirm dialog.
  - A configurable local APK folder (defaults to `Android/data/com.appmanager/files/repo/`,
    which needs no storage permission; a custom path prompts for all-files access).
- Pull-to-refresh; re-checks installed state.
- Blue→green Material 3 theme, light / dark / follow-system.
- Signed release; `make-repo.sh` helper to generate a repository index from a folder of APKs.
