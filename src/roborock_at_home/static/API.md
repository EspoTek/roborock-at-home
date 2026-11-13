# Roborock at Home: HTTP API

Everything the web page does goes through this API, so anything you can do by
hand an agent or a script can do too. This file is served by the app itself at
`GET /api/docs`.

- Base URL: wherever the app runs, e.g. `http://192.168.1.50:8770`.
- JSON in, JSON out. Times are Unix seconds. Map coordinates are pixels of the
  robot's map image (origin top-left, y down); one pixel is 50 mm.
- No request ever waits on a robot to *read* something: the app keeps each
  robot's state in memory and answers from it. Only `command` talks to a robot.

## Authentication

| Who | How | Can do |
|---|---|---|
| A person, in the web page | `POST /api/login` with their name and password sets a session cookie | everything |
| A script or agent | `Authorization: Bearer <token>` | what the token's scope allows |

Tokens are made on the Settings page (or `roborock-at-home token`), shown once,
and have a scope:

- `read`: every `GET` below.
- `control`: `read`, plus robot commands and schedules.

Managing people, tokens, passwords, the Roborock account, the time zone and
remote access needs the session cookie: a token cannot change who has access.

Every person who can sign in has their own name (usually an email address,
compared without regard to case) and password, and the same full access.
A fresh install has one, `admin`.

Cookie-authenticated requests other than `GET` must send the header
`X-RAH: 1` (the page does; it stops other sites from posting on your behalf).
Bearer requests do not need it.

Errors always look like this, with a matching HTTP status:

```json
{"error": {"code": "robot_unreachable", "message": "Downstairs did not answer."}}
```

| Status | `code` |
|---|---|
| 400 | `bad_request` |
| 401 | `unauthenticated` |
| 403 | `forbidden` (token scope too small, or session-only endpoint) |
| 404 | `not_found` |
| 409 | `rejected`, `unsupported`, `demo` (not available in the demo), `not_linked` |
| 429 | `too_many_attempts` (failed logins; `Retry-After` header) |
| 503 | `robot_unreachable` |

```sh
export RAH=http://192.168.1.50:8770 TOKEN=rah_...
curl -s -H "Authorization: Bearer $TOKEN" $RAH/api/state
```

## State

### `GET /api/state`

The whole picture in one small response. Supports `If-None-Match`.

```json
{
  "rev": 412,
  "app": {
    "version": "0.2.0",
    "demo": false,
    "tz": "Australia/Melbourne",
    "account": {"linked": true, "email": "y***@example.com", "problem": null},
    "tunnel": {"mode": "off", "status": "stopped", "url": null, "error": null}
  },
  "robots": [ { ...robot... } ],
  "schedules": [ { ...schedule... } ]
}
```

- In the demo, `app.account` is `{"linked": true, "email": null, "problem": null}`.
- `app.tz` is `null` until set (the page sets it from the browser on first
  login). Schedules cannot be created before then.
- `app.tunnel.mode`: `off`, `quick` or `token`. `status`: `stopped`,
  `installing`, `starting`, `running`, `error`.

### A robot

```json
{
  "id": "b3f1c2d4e5",
  "name": "Downstairs",
  "model": "roborock.vacuum.a65",
  "model_name": "S7 Max Ultra",
  "activity": "cleaning",
  "state_label": "Cleaning rooms",
  "online": true,
  "link": "local",
  "battery": 64,
  "charging": false,
  "error": null,
  "attention": null,
  "fan": "balanced",
  "fan_options": ["quiet", "balanced", "turbo", "max"],
  "water": "standard",
  "water_options": ["off", "mild", "standard", "intense"],
  "clean": {"area_m2": 18.4, "seconds": 1260},
  "rooms": [{"id": 16, "name": "Kitchen"}, {"id": 17, "name": "Hall"}],
  "map": {
    "rev": "9f2c1a7e",
    "w": 373,
    "h": 567,
    "mm_per_px": 50,
    "rooms": {"16": {"cx": 120.5, "cy": 88.0, "x0": 80, "y0": 40, "x1": 170, "y1": 131}},
    "charger": {"x": 106.0, "y": 255.3},
    "no_go": [[10, 10, 40, 10, 40, 30, 10, 30]],
    "walls": [[50, 60, 90, 60]]
  },
  "pos": {"x": 201.4, "y": 310.2, "angle": -160, "room": 16, "ts": 1794563000, "live": true},
  "seen": 1794563000
}
```

- `activity`: `cleaning`, `returning`, `paused`, `docked`, `idle`, `error`,
  or `offline`. `state_label` is the robot's own wording.
- `online` is `false` once the robot has stopped answering (three failed reads
  in a row, or 90 seconds). Everything else then shows the last values it
  reported.
- `link`: `local` (talking over the LAN), `cloud`, or `none`.
- `battery`, `fan`, `water`, `clean`, `map`, `pos` are `null` until first
  known. `water_options` is empty on a robot that cannot mop.
- `error`: `null` or `{"code": 5, "label": "Main brush jammed"}`.
- `pos` is the **last known position**. `live` is `true` while the robot is
  reachable and the position came from its latest map; `false` once it is
  stale. `ts` is when it was read; `room` is the room it was in, or `null`.
- The path of the current job is not part of the robot object: see `/path`
  and the `path` event.
- `seen` is when the robot last answered. It moves only when something else
  about the robot changes too, so an idle robot sends nothing.

### `attention`: when a robot needs a person

`null`, or the one most important problem:

```json
{
  "kind": "error",
  "title": "Main brush jammed",
  "summary": "Stuck: main brush jammed. Last seen in Kitchen at 14:32, battery 41%.",
  "since": 1794563000,
  "room": 16,
  "battery": 41
}
```

| `kind` | When |
|---|---|
| `error` | the robot reports a fault |
| `offline` | it stopped answering while off the dock, or with a battery at 10% or less (title "Battery flat" in that case) |
| `low_battery` | 15% or less, not charging and not on its way back. That includes a robot sitting on a dock that is not charging it. |

`summary` is one ready-made sentence in the app's time zone. With `pos`, this
answers "where is it?" after it has died somewhere.

### `GET /api/robots`, `GET /api/robots/{id}`

The `robots` array, or one robot.

### `GET /api/events`

Server-Sent Events. On connect: one `state` event with the same body as
`/api/state`. After that only what changed is sent, and nothing at all while
nothing changes:

| Event | Data |
|---|---|
| `robot` | a patch: `id` plus only the top-level fields that changed, e.g. `{"id": "b3f1c2d4e5", "battery": 63}`. Merge it into the robot you hold. A robot you have not seen arrives whole. |
| `path` | `{"id": "b3f1c2d4e5", "rev": 88, "start": 1848, "points": [203.0, 310.4]}`: the points added since the last event. `start` is where they go in the flat `x0, y0, x1, y1, ...` array (so it counts numbers, two per point); if that is not the length of what you hold, fetch `/path`. `start: 0` replaces the path (a new job). |
| `schedules` | the `schedules` array |
| `app` | the `app` object |
| `history` | one history entry (newest) |
| `state` | the whole state again, when robots were removed (the account was unlinked) |

A comment line is sent every 25 seconds to keep proxies from closing the
stream. Every event carries `id: <rev>`.

## Maps

### `GET /api/robots/{id}/map.png`

The floor plan as a PNG, rooms coloured, with the robot's last known position
(dot), the dock (square) and the current path drawn in. Made for showing a
person or an image-reading agent. `?scale=1..8` (default 2).

### `GET /api/robots/{id}/map.bin?rev=9f2c1a7e`

What the page draws from: `w * h` bytes, row by row, one class per pixel:
`0` outside, `1` wall, `2` floor in no room, `3` space the robot saw but
never drove (beyond walls, under furniture), `32 + room id` a room. Cached
forever per `rev` (`rev` is `map.rev`; a different `rev` is a 404).

### `GET /api/robots/{id}/path`

```json
{"rev": 87, "points": [201.4, 310.2, 203.0, 310.4]}
```

The current job's path as `x0, y0, x1, y1, ...`. `rev` changes whenever the
path does. Supports `If-None-Match`.

## Commands

### `POST /api/robots/{id}/command`   (scope `control`)

```json
{"command": "clean_rooms", "rooms": [16, 17], "repeat": 2}
```

| `command` | Fields | Does |
|---|---|---|
| `start` | | clean the whole home |
| `pause` | | |
| `resume` | | |
| `stop` | | |
| `dock` | | go back to the dock |
| `locate` | | play the "I am here" sound |
| `clean_rooms` | `rooms` (ids), `repeat` 1-3, default 1 | clean just those rooms |
| `set_fan` | `value`, one of `fan_options` | suction level |
| `set_water` | `value`, one of `water_options` | mop water level |

Returns `{"ok": true}` once the robot has accepted it. The new state follows
on `/api/events` and `/api/state`.

```sh
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"command":"clean_rooms","rooms":[16]}' $RAH/api/robots/b3f1c2d4e5/command
```

## Schedules

The app runs schedules itself, in `app.tz`, for as long as it is running. A
schedule that comes due while the app is stopped is skipped, not run late.

```json
{
  "id": "s_4f9a1c",
  "robot": "b3f1c2d4e5",
  "name": "Weekday kitchen",
  "enabled": true,
  "days": [0, 1, 2, 3, 4],
  "time": "09:30",
  "action": {"type": "clean_rooms", "rooms": [16], "repeat": 1, "fan": null, "water": null},
  "next_run": 1794600600,
  "last_run": {"ts": 1794514200, "ok": true, "message": "Started"}
}
```

- `days`: 0 = Monday ... 6 = Sunday; at least one.
- `action.type`: `clean_all` or `clean_rooms` (`rooms` required then).
  `fan` / `water`, when not `null`, are set just before starting.
- `next_run` is `null` when disabled. `last_run` is `null` until it has run.

| Request | Scope | |
|---|---|---|
| `GET /api/schedules` | `read` | the array |
| `POST /api/schedules` | `control` | create; body is a schedule without `id`, `next_run`, `last_run`; `name` optional. Returns it. |
| `PUT /api/schedules/{id}` | `control` | replace the same fields. Returns it. |
| `DELETE /api/schedules/{id}` | `control` | `{"ok": true}` |
| `POST /api/schedules/{id}/run` | `control` | run it now; `{"ok": true}` or the robot's error |

## History

### `GET /api/history?limit=50`

Newest first, at most 200 kept.

```json
[{"ts": 1794563000, "robot": "b3f1c2d4e5", "kind": "error", "text": "Main brush jammed in Kitchen"}]
```

`kind`: `started`, `finished`, `docked`, `error`, `cleared`, `offline`,
`online`, `schedule`, `command`.

## Service

| Request | Auth | |
|---|---|---|
| `GET /healthz` | none | `ok` |
| `GET /api/docs` | none | this file |
| `GET /api/session` | none | `{"authenticated": true, "kind": "session", "scope": "all", "name": "you@example.com"}` (`name` is `null` for a token; not signed in: `{"authenticated": false}`) |
| `GET /api/stats` | `read` | `{"rss_mb": 41.2, "uptime_s": 86400, "clients": 1, "robots": 2}` |

## Session-only endpoints

Used by the Settings page. A bearer token gets `403 forbidden`.

| Request | Body | |
|---|---|---|
| `POST /api/login` | `{"name": "you@example.com", "password": "..."}` | sets the cookie; `{"ok": true}`. A wrong name and a wrong password give the same 401. |
| `POST /api/logout` | | |
| `PUT /api/settings` | `{"tz": "Australia/Melbourne"}` | an IANA zone |
| `POST /api/password` | `{"current": "...", "new": "..."}` | your own password; at least 8 characters; signs out your other sessions |
| `GET /api/users` | | `[{"name": "you@example.com", "created": 1794563000, "you": true}]` |
| `POST /api/users` | `{"name": "them@example.com", "password": "..."}` | add a person (name 1-100 characters, unique; password at least 8) |
| `DELETE /api/users/{name}` | | remove a person and sign them out. Not yourself (`bad_request`). |
| `GET /api/tokens` | | `[{"id": "t_1a2b", "name": "Agent", "scope": "control", "created": 1794563000, "last_used": null}]` |
| `POST /api/tokens` | `{"name": "Agent", "scope": "control"}` | `{"id": "t_1a2b", "token": "rah_..."}`, shown once |
| `DELETE /api/tokens/{id}` | | |
| `POST /api/account/code` | `{"email": "..."}` | Roborock emails a code |
| `POST /api/account/login` | `{"email": "...", "code": "123456"}` | links the account; robots appear |
| `DELETE /api/account` | | unlinks; robots and their maps are forgotten |
| `PUT /api/tunnel` | `{"mode": "quick"}` or `{"mode": "token", "token": "..."}` or `{"mode": "off"}` | Cloudflare remote access; progress on `app.tunnel` |
