package com.appmanager.api

/**
 * The API's own reference, served at `GET /api/docs`.
 *
 * It lives in the app rather than only in the README because the thing most likely to read it is
 * an agent that has the key and the port and nothing else. A reference it can fetch is a reference
 * that cannot drift from the build it is talking to.
 */
object ApiDocs {

    /** The one-line index at `GET /api`, for a caller that just wants to see what exists. */
    val SUMMARY = listOf(
        "GET    /api/health",
        "GET    /api/instance",
        "GET    /api/docs",
        "GET    /api/apps?filter=&sort=&q=&reload=",
        "POST   /api/apps/reload",
        "GET    /api/apps/{package}",
        "GET    /api/apps/{package}/apk",
        "POST   /api/apps/{package}/install",
        "GET    /api/apps/{package}/install",
        "POST   /api/apps/{package}/uninstall",
        "POST   /api/apps/{package}/open",
        "POST   /api/apps/{package}/share",
        "GET    /api/apps/{package}/auto-update",
        "PUT    /api/apps/{package}/auto-update",
        "GET    /api/updates?reload=",
        "POST   /api/updates/install",
        "GET    /api/installs",
        "GET    /api/sources",
        "POST   /api/sources",
        "PUT    /api/sources",
        "DELETE /api/sources?url=",
        "GET    /api/devs",
        "POST   /api/devs",
        "PUT    /api/devs",
        "DELETE /api/devs?user=",
        "GET    /api/settings",
        "PATCH  /api/settings",
        "GET    /api/settings/export",
        "POST   /api/settings/import",
        "POST   /api/settings/import-official",
        "GET    /api/palettes"
    )

    val MARKDOWN = """
# App Manager local API

Drives App Manager on this device over HTTP: the catalog, installs and updates, removing and
opening apps, sources, followed devs and settings. Everything here is something the person holding
the phone can already do from the UI — the API is a second pair of hands, not a second set of
features.

- **Base URL:** `http://127.0.0.1:<port>/api` — the port (default 8797) is shown in the app under
  **Local API** in the overflow menu.
- **Off by default.** It runs only while you have it enabled, and it binds `127.0.0.1` unless you
  also turn on LAN access. From a laptop: `adb forward tcp:8797 tcp:8797`.
- **Auth:** every route except `GET /api/health` needs the key, as `X-API-Key: <key>` or
  `Authorization: Bearer <key>`. A wrong or missing key is a `401`.
- **Errors** are JSON: `{"error": "<what went wrong>", "hint": "<what to do instead>"}`, with a
  status that says whose fault it was (`400` your request, `404` no such thing, `409` not possible
  in the current state, `503` the app or the network did not answer).
- A `{package}` is an Android package name, e.g. `com.lull.player`.

## Instance

`GET /api/instance` — version, port, and the things that decide what will work:
`can_install` (App Manager is allowed to install apps), `visible` (an App Manager screen is up),
`notifications`, `storage_access` (needed only for a custom local folder), `apps`, `updates`.

## The catalog

`GET /api/apps` — every app the sources offer, merged with what is installed. The first call
loads the catalog if the app has not yet; after that it is cached, and the installed half is
always re-read live. Query: `filter` = `all|updates|installed|not_installed`,
`sort` = `status|name|recent`, `q` = search label or package, `reload=true` to fetch every source
again (the pull-to-refresh). `POST /api/apps/reload` does the same.

`GET /api/apps/{package}` — the details screen: versions, source, description, what's new,
homepage, install/update dates, and for a local APK its min/target SDK and size.

`GET /api/apps/{package}/apk` — the APK itself (the installed copy if there is one, else the
local file, else downloaded), streamed with a filename.

## Installing and updating

`POST /api/apps/{package}/install` — installs or updates, exactly as tapping Install / Update.
Answers `202` with the install's state; poll `GET /api/apps/{package}/install`:

| `state` | meaning |
|---|---|
| `queued`, `downloading` (`progress` 0–100), `installing` | in progress |
| `awaiting_confirmation` | Android wants a tap: the dialog is on screen if App Manager was, otherwise a notification |
| `installed` | done |
| `failed` (`message` says why), `cancelled` (the person declined) | over |

**When is an install silent?** When App Manager is not on screen the install runs in the
background, as an auto-update does: on Android 12+ it completes with no prompt for an app App
Manager itself installed; otherwise it becomes a one-tap notification. `409` if the app is up to
date, has no source, or App Manager has not been allowed to install apps.

`POST /api/updates/install` — Update all: queues every available update, one after another.
`GET /api/updates` lists them; `GET /api/installs` lists every install started since launch.

`GET|PUT /api/apps/{package}/auto-update` — `{"enabled": true}`: the per-app auto-update
switch. Turning one on also turns on daily background checks if they were off, as the details
screen does. `409` for an app that is not installed or has no source.

## Removing, opening, sharing

`POST /api/apps/{package}/uninstall`, `/open`, `/share` — each needs a screen. Android does not
let an app that is not on screen start an activity, so the answer says what happened:
`{"launched": true, "visible": true}` when the dialog or app came up, or
`{"launched": false, "notified": true}` when App Manager was in the background and a
tap-to-finish notification was posted instead. Removing an app always needs the person's
confirmation — that is Android's rule, not App Manager's. `/share` also returns the `apk` url.

## Sources and followed devs

`GET /api/sources` — the source URLs. `POST {"url": "…"}` adds one exactly as sharing a link
does: a GitHub repo, a direct `.apk`, or an index JSON; a `github.com/<user>` link follows that
dev instead (`"kind": "dev"`). `201` when added, `200` when it was already there.
`PUT {"sources": [...]}` replaces the list; `DELETE ?url=` removes one.

`GET /api/devs` — followed GitHub users (every public repo of theirs with an APK release).
`POST {"user": "…"}`, `PUT {"devs": [...]}`, `DELETE ?user=`.

A change to either reloads the list on screen if App Manager is open.

## Settings

`GET /api/settings`, `PATCH /api/settings` with any of:

| field | values |
|---|---|
| `palette` | a name from `GET /api/palettes`, by key, label or `white-glow` (`Zoro`, `WhiteGlow`, `Material You`…) |
| `theme` | `system`, `light`, `dark` — only Material You, Ocean and Terminal follow it |
| `auto_update` | `off`, `daily`, `weekly` — the background update check |
| `local_dir` | a folder to scan for APKs; `""` is App Manager's private folder |
| `sort`, `filter` | the list screen's sort and filter, as above |

An unknown field is a `400`, so a typo cannot silently do nothing.

`GET /api/settings/export` — the same JSON the Export button writes. `POST /api/settings/import`
with that JSON as the body applies it. `POST /api/settings/import-official` downloads and
applies the published official config.

`GET /api/palettes` — the house palettes in picker order, which follow light/dark, which are
monospace, and which is current.

## PhoneAPI

PhoneAPI (`com.phoneapi.app`, release build only) gets its own key through the
`com.phoneapi.action.GRANT_KEY` handshake, separate from the owner's key. The owner picks what it
may do: **See** (every `GET`) and **Change** (everything else); anything not allowed is a `403`.
Once linked, every answer to a request carrying `X-PhoneAPI-Challenge` (health, 401s and errors
included) carries `X-PhoneAPI-Proof: base64url_nopad(HMAC-SHA256(proof_key, challenge))`.
Unlink in Local API; uninstalling PhoneAPI drops its key.

## Examples

```bash
KEY=<copied from the app>
curl -s -H "X-API-Key: ${'$'}KEY" 127.0.0.1:8797/api/updates
curl -s -H "X-API-Key: ${'$'}KEY" -X POST 127.0.0.1:8797/api/apps/com.lull.player/install
curl -s -H "X-API-Key: ${'$'}KEY" 127.0.0.1:8797/api/apps/com.lull.player/install
curl -s -H "X-API-Key: ${'$'}KEY" -X POST 127.0.0.1:8797/api/sources -d '{"url":"https://github.com/PortableDiag/Lull"}'
curl -s -H "X-API-Key: ${'$'}KEY" -X PATCH 127.0.0.1:8797/api/settings -d '{"palette":"Zoro"}'
```
""".trimIndent()
}
