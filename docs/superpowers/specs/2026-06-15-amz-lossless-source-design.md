# AmzSource — amz.squid.wtf Amazon Music lossless source — Design

**Date:** 2026-06-15
**Status:** Design (approved in brainstorm; pending spec review)
**Author:** brainstorm session

## Summary

Add `amz.squid.wtf` (an Amazon Music downloader) as a new lossless source in
Stash, providing **download + streaming** of FLAC. It slots **after**
kennyy/squid and **before** the YouTube fallback in both the download
(`LosslessSourceRegistry`) and streaming (`StreamSourceRegistry`) chains.

Why it matters: amz is backed by **Amazon Music**, so it is *structurally
independent* of the Qobuz `app_id` that kennyy and squid both share. When the
Qobuz proxies degrade or miss a track, amz is a genuine independent lossless
fallback rather than another view of the same backend (which is why monochrome
— a kennyy wrapper — was rejected). It is the third leg the lossless-redundancy
roadmap has been after.

amz is the **same operator/CDN family as `qobuz.squid.wtf`** (`server:
edgedragon`, not Cloudflare), and uses the **same ALTCHA PBKDF2 captcha
scheme**, so Stash's existing PoW solver is reused.

## Goals / Non-goals

**Goals**
- Resolve a Stash track to an Amazon Music FLAC and (a) download it to the
  library and (b) stream it in online mode.
- Auto-solve the captcha headless (no login, no WebView, no user setup).
- Fail over cleanly (existing health-gate + rate-limiter) when amz misses, is
  down, or is region-gated.

**Non-goals (YAGNI for v1)**
- No multi-region UI (hardcode `country=US`, `tier=best`).
- No dedicated Settings screen / Connect screen (it "just works" like kennyy).
- No ingestion of amz's synced LRC lyrics into Stash's lyrics system (the
  metadata is available; wiring it is a separate future task).
- No DB schema changes.

## API contract (captured from HAR, 2026-06-15)

Host: `https://amz.squid.wtf`. Server header: `edgedragon`. Auth on every API
call: an `x-captcha-token` request header.

### Captcha (ALTCHA PBKDF2 — same scheme as qobuz.squid.wtf)
- `GET /api/captcha/challenge` →
  `{"parameters":{"algorithm":"PBKDF2/SHA-256","cost":1000,"keyLength":32,"keyPrefix":"…","nonce":"…","salt":"…"},"signature":"…"}`
  **Note: NO `expiresAt` field** (qobuz.squid.wtf's challenge *does* have one).
- `POST /api/captcha/verify` body
  `{"payload":"<base64 of {challenge:{parameters,signature},solution:{counter,derivedKey,time}}>"}`
  → `{"token":"<token>"}`
- The returned `token` is sent as the `x-captcha-token` header on all
  subsequent calls.

### Catalog
- `POST /api/search` body `{"query":"<artist> <title>","country":"US","content_type":"TRACK","limit":25}`
  → `{"trackList":[{"asin","title","primaryArtistName","artistName","albumArtistName","album":{"title","image"}}]}`.
  **Search results carry no ISRC and no duration.**
- `POST /api/track` body `{"asin":"<ASIN>","tier":"best","country":"US"}`
  → `{"metadata":{"asin","title","artist","album","album_asin","cover","cover_cdn","year","date","track_number","disc_number","genre","composer","copyright","label","isrc","is_explicit","lyrics":{"synced":"…"}}}`.
- `GET /api/stream?asin=<ASIN>&country=US&tier=best`
  → `application/octet-stream` (the FLAC bytes, served directly from
  amz.squid.wtf; ~56 MB observed for a 4-min track = real hi-res FLAC).
- `GET /api/image?url=<amazon cover url>` → image proxy. **Not used** — the
  search/track responses already give direct public `m.media-amazon.com` URLs.

## Architecture & components

New package `data/download/.../lossless/amz/`, mirroring the `qobuz`/`squid`
layout. Each unit has one purpose and a small, testable interface.

| Component | Responsibility | Mirrors |
|---|---|---|
| `AmzCaptchaClient` | `GET /api/captcha/challenge` → `AltchaSolver.solve()` → `POST /api/captcha/verify` → returns token string. Single-flight (Mutex) so concurrent callers share one solve. | `NativeSquidCaptchaSolver` (reuses `AltchaSolver` unchanged) |
| `AmzCaptchaInterceptor` | OkHttp interceptor scoped to `amz.squid.wtf`; attaches `x-captcha-token`; on a stale-token response, re-mints once via `AmzCaptchaClient` and retries. Holds the token `@Volatile`. | `SquidWtfCaptchaInterceptor` |
| `AmzApiClient` | `search()`, `track(asin)`, `streamUrl(asin)`; uses a derived OkHttp client carrying `AmzCaptchaInterceptor` (shared pool, like `QobuzApiClient`). | `QobuzApiClient` |
| `AmzApiModels` | `@Serializable` DTOs for search/track responses + lenient `Json`. | `QobuzApiModels` |
| `AmzMatcher` | Scores search candidates against the `TrackQuery` (artist+title); picks the best ASIN; confirms via `/api/track` (ISRC match when available). | `MatchScorer` logic |
| `AmzSource : LosslessSource` | `id="amz"`, `displayName="Amazon Music (amz.squid.wtf)"`; the resolve flow; returns `SourceResult`. | `QobuzSource` |
| `AmzStreamResolver` | Streaming adapter: fetches the FLAC to an evictable cache file (via the shared authed-fetch seam) and returns a `file://` `StreamUrl` (cache-to-local, v1 default). | retired antra stream resolver (cache-to-local) |
| `AmzModule` (Hilt) | `@Binds @IntoSet` `AmzSource` into `Set<LosslessSource>`; provides client/interceptor. | `qobuz` DI |

**Wiring**
- Download: registered into `Set<LosslessSource>`; `"amz"` appended to
  `LosslessSourcePreferences.DEFAULT_PRIORITY` (currently `squid_qobuz`,
  `kennyy_qobuz`) as the last lossless entry — i.e. after both Qobuz proxies,
  before the YouTube fallback.
- Streaming: `AmzStreamResolver` added to `StreamSourceRegistry`'s **normal**
  branch, after `squid` and before `youtube` (NOT in the forceYt branch).
- Both paths gated by the existing `LosslessSourceHealthGate` and
  `AggregatorRateLimiter` (new conservative `configs["amz"]`).
- No DB changes; no Settings UI.

## Data flow

### Captcha mint (shared by all amz calls)
1. `AmzCaptchaInterceptor` sees an `amz.squid.wtf` request with no/stale token.
2. Under a **single-flight Mutex**, `AmzCaptchaClient`:
   `GET /api/captcha/challenge` → `AltchaSolver.solve(params)` →
   `POST /api/captcha/verify` → `{token}`.
3. Token cached `@Volatile`; attached as `x-captcha-token`. Concurrent callers
   await the in-flight solve rather than each solving.

### Download (`AmzSource.resolve(query)`)
1. `isEnabled()` (not circuit-broken) and `rateLimiter.acquire("amz")`; else null.
2. `AmzApiClient.search("<artist> <title>")` (NEVER by ISRC — see Requirement 2).
3. `AmzMatcher` scores `trackList` on artist+title, picks the top candidate.
4. `AmzApiClient.track(asin)` → confirm match: when `query.isrc != null`,
   require `metadata.isrc == query.isrc` for confidence ≥ 0.95; otherwise use the
   fuzzy artist+title score. Reject on duration mismatch if duration is exposed.
5. Return `SourceResult(sourceId = "amz", downloadUrl = streamUrl(asin),
   downloadHeaders = {}, format = AudioFormat(codec = "flac", bitrateKbps = 0,
   sampleRateHz = 0, bitsPerSample = 0), confidence, coverArtUrl = direct
   m.media-amazon cover, sourceTrackId = asin)`.
6. The amz download fetch must carry a *fresh* `x-captcha-token` at fetch time
   (see Requirement 3 — this is **new plumbing**, not the existing static-header
   path). Post-download, the existing `AudioDurationExtractor`/quality probe sets
   the authoritative bits/sample-rate and the duration backstop validates the
   file.

### Streaming (`AmzStreamResolver.resolve(track)`)
Reality check (confirmed against the code during spec review): `StreamUrl`
carries only `url + expiresAtMs + codec metadata + origin` (no header/auth
field), and `StreamingMediaSourceFactory` hardcodes a single
`DefaultHttpDataSource.Factory()` for all sources — kennyy/squid work only
because their auth is baked into a *signed CDN URL*. amz's per-request
`x-captcha-token` header model has no seam in that chain today.

- **Approach B — cache-to-local — is the v1 default.** `AmzStreamResolver`
  fetches the FLAC to an evictable cache file via the same authed-fetch seam the
  download path uses (Requirement 3), then returns a `StreamUrl` pointing at the
  local `file://` (the pattern the retired antra resolver used). This reuses the
  one new seam, needs **no** changes to `StreamUrl` or
  `StreamingMediaSourceFactory`, and is immune to mid-stream token expiry (the
  token is only needed during the one-shot fetch). amz has no per-play quota, so
  re-fetching costs only bandwidth/latency, not quota. Cost: a pre-play wait
  while the ~56 MB file downloads (acceptable; antra did the same).
- **Approach A — direct authed streaming — is a FUTURE optimization, out of
  scope for v1.** It would require new plumbing: a header field on `StreamUrl`
  (or a per-source data-source factory) and a Media3 `OkHttpDataSource.Factory`
  built on the interceptor-bearing client so `x-captcha-token` rides every
  range/seek request and a mid-stream 401 auto-re-mints. Only worth doing if the
  pre-play wait proves annoying AND `/api/stream` honors Range (see
  verify-on-device).

## Design requirements (folded-in critique)

1. **Verify-payload field-set must match amz exactly.** Do NOT reuse
   `NativeSquidCaptchaSolver`'s fixed-field payload builder (it includes
   `expiresAt`, which amz omits). `AmzCaptchaClient` must re-embed amz's
   `parameters` block **verbatim** (preserve the raw JSON substring) and only
   append the `solution`, or the server's HMAC `signature` check fails.
   `AltchaSolver.solve()` is reused unchanged (PoW needs only
   salt/nonce/cost/keyLength/keyPrefix).
2. **Search by text, never by ISRC.** amz `/api/search` indexes a text query;
   ISRC search returns nothing. amz must build its own `"<artist> <title>"`
   query and use ISRC only for `/api/track` confirmation — it must NOT call
   `TrackQuery.searchTerms()` (which emits the bare ISRC when present).
3. **Re-mint at fetch time, not resolve time — and this is NEW plumbing.** A
   token snapshotted into `SourceResult.downloadHeaders` at resolve can expire
   before a queued sync download runs. **The existing download path cannot do
   this:** `LosslessUrlDownloader` uses one shared `OkHttpClient` and applies
   `downloadHeaders` as static headers — there is no per-source interceptor.
   v1 must add the seam. Recommended approach: register `AmzCaptchaInterceptor`
   (host-scoped — a no-op for any host other than `amz.squid.wtf`) on the
   **shared** `OkHttpClient` via Hilt, so both `AmzApiClient`'s calls AND
   `LosslessUrlDownloader`'s fetch get the token attached and auto-re-minted on a
   stale-token response, with no header snapshot. (A host-scoped interceptor is
   safe on the shared client by construction — it never touches Spotify/YouTube/
   Last.fm requests. This is the seam the streaming cache-to-local fetch reuses
   too.) The plan must treat this as net-new wiring, not reuse.
4. **Single-flight captcha mint.** Guard minting with a `Mutex` so parallel
   resolves share one PoW solve instead of stampeding (PBKDF2 cost=1000 is real
   CPU).
5. **Do not assume 24-bit FLAC.** `tier:"best"` returns whatever that track's
   max is on Amazon (may be 16-bit FLAC, or lossy for Atmos/AAC-only titles).
   Set `AudioFormat(codec = "flac", bitrateKbps = 0, sampleRateHz = 0,
   bitsPerSample = 0)` and let the post-download quality probe set authoritative
   values; if `/api/track` states
   a format/tier, use it to pre-reject lossy before downloading so the
   `minQuality=LOSSLESS` gate isn't wasted on a 56 MB fetch.
6. **Cancellation hygiene.** `AmzSource.resolve` / `AmzStreamResolver.resolve`
   re-throw `CancellationException` before any `catch (Exception)` (so worker
   cancellation doesn't surface as a false failure).

## Error handling & failover

- Any amz failure (no token, search miss, no confident match, network error,
  HTTP error) → `resolve` returns null → registry advances to the next source
  (kennyy/squid already tried first; YouTube next). amz never surfaces a hard
  error to the user.
- 429 → `rateLimiter.reportRateLimited("amz")`; other failures →
  `reportFailure`; success → `reportSuccess`. Repeated failures trip the
  circuit breaker; the `LosslessSourceHealthGate` also skips amz during a
  content-degradation cooldown.
- Captcha solve failure → treated as a transient miss (null), not a permanent
  disable; the next resolve retries.

## Rate limiting & health

- New `AggregatorRateLimiter` `configs["amz"]`: conservative defaults (e.g. ~1
  token / 2s, small burst). The captcha challenge/verify round-trips are auth
  overhead and should not themselves trip the catalog rate-limit (decided in
  the plan; default: do not count them).
- Reuses `LosslessSourceHealthGate` for degradation cooldown, identical to
  kennyy/squid/qobuz.

## Testing strategy

- `AltchaSolver`: already unit-tested; reused as-is (no new PoW tests).
- `AmzCaptchaClient`: MockWebServer — challenge (with **no `expiresAt`**) →
  verify; **lock the verify payload bytes against the captured amz HAR vector**
  (the field-set/order that produced a 200 + token). Test single-flight (one
  solve under concurrent callers).
- `AmzCaptchaInterceptor`: re-mint-once-and-retry on a stale-token response;
  no infinite loop on repeated failure.
- `AmzApiClient` + `AmzApiModels`: parse real captured search/track JSON.
- `AmzMatcher`: ISRC-confirm path (high confidence), fuzzy fallback, and
  wrong-version rejection.
- `AmzSource`: gating (disabled/circuit-broken → null), 429 handling,
  cancellation re-throw, `SourceResult` shape.
- Registry wiring tests: amz appears after squid, before youtube, in both
  registries; absent from the forceYt branch.

## Verify-on-device (do early in implementation — they decide details)

1. **Does `/api/stream` honor HTTP Range?** Not v1-critical (v1 streaming is
   cache-to-local, Approach B). Only informs whether the future Approach A
   direct-streaming optimization is feasible.
2. **What does a stale/invalid `x-captcha-token` return?** (401 vs 403 vs 200+
   error body) — sets the interceptor's re-mint trigger.
3. **Does `/api/track` expose duration and/or the available format/tier?** —
   enables the duration backstop on match and pre-download lossy rejection
   (Requirement 5).

## Open questions / risks

- Token TTL is unknown; the re-mint-on-stale design tolerates any TTL, but a
  very short TTL would mean frequent PoW solves (cost=1000 PBKDF2 is ~hundreds
  of ms). Acceptable; single-flight bounds the cost.
- v1 streaming uses cache-to-local (Approach B), which reuses the download
  fetch seam and needs no `StreamUrl`/factory changes. The direct-streaming
  optimization (Approach A) is deferred and would require new `StreamUrl` +
  data-source-factory plumbing.
- Amazon US catalog gaps differ from Qobuz — expected and desirable (that is the
  independence), handled by failover.

## Out of scope (explicit)

Lyrics ingestion, multi-region setting, Settings UI/Connect screen, DB changes,
and any change to kennyy/squid behavior.
