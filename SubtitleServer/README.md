# Subtitle server

The Assignment 3 server is an independent Spring Boot Maven project using Java 25. It is adapted from my Assignment 1
server with assistance from OpenAI Codex. The old recording website, mock provider, token statistics, uptime and shutdown
endpoints have been removed. The entry point is `comp3011.subtitleserver.SubtitleServerApplication`.

## Running

From this directory, set `OPENAI_API_KEY` in the process environment or IntelliJ run configuration, then run:

```powershell
.\mvnw.cmd spring-boot:run
```

The server listens on `127.0.0.1:8080`. Without a key, it can serve existing cached transcriptions and report usage, but
returns 503 for a cache miss. The key is never sent to the player or written to the cache. The video player currently
still uses its sample subtitle track; its background audio extraction and HTTP client are a separate integration step.

## API

`POST /api/v1/subtitles` accepts `multipart/form-data` with one part named `file` containing an original encoded audio
chunk. Preserve its actual extension: WAV, MP3, MP4, M4A, WebM, MPEG or MPGA. Each file must be nonempty and at most
24 MiB. The provider checks whether its contents are decodable; the server does not transcode or infer an encoding.

```powershell
curl.exe -F "file=@clip.wav" http://localhost:8080/api/v1/subtitles
```

Example response:

```json
{
  "duration": 2.5,
  "language": "english",
  "text": "Hello there.",
  "words": [
    { "word": "Hello", "start": 0.2, "end": 0.7 },
    { "word": "there.", "start": 0.7, "end": 1.1 }
  ],
  "segments": [
    { "start": 0.2, "end": 1.2, "text": " Hello there.", "no_speech_prob": 0.1, "avg_logprob": -0.2 }
  ]
}
```

All times are floating-point **seconds relative to the uploaded chunk**, not the current playback session. The player
adds the chunk's original media offset and converts to microseconds. Replays and seeks must reuse the same chunk
boundaries and audio encoding for content-based cache hits. Line wrapping and final one-to-seven-second subtitle cue
formatting remain player responsibilities; segments are timing data rather than ready-to-display captions.

The upstream request uses `whisper-1`, `response_format=verbose_json`, and both `timestamp_granularities[]=word` and
`timestamp_granularities[]=segment`, following the [official transcription API reference](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create).
Segments are suppressed only when **both** `no_speech_prob > 0.6` and `avg_logprob < -1.0`, as required by the assignment.
Words are retained when their midpoint belongs to an accepted segment, and combined text is rebuilt from accepted
segments. Silence can therefore return empty text and arrays while still reporting the original duration.

`GET /api/v1/global/usage` returns exactly:

```json
{ "usage": 13.0 }
```

This is cumulative provider-reported usage in seconds for successful transcription responses, falling back to the
response's audio duration when the provider omits usage. Cached requests do not add usage. Suppressed segments and
silence still count as processed audio. Failed or timed-out requests do not add unknown usage; a provider may have
processed audio before the connection failed, so this local total cannot reconcile those ambiguous requests.

Errors use JSON such as `{ "status": 503, "message": "Cannot reach transcription provider; retry later", "path": "/api/v1/subtitles" }`.

| Status | Meaning |
| --- | --- |
| 400 | Missing/empty audio, unreadable upload, or audio rejected by the provider |
| 413 | Upload exceeds the configured multipart limit |
| 415 | Unsupported filename extension or request content type |
| 502 | Provider failure, empty response, or malformed timing data |
| 503 | Missing credentials, provider quota/authentication failure, connectivity/timeout, or unavailable storage |

Failed responses are not cached. The player should keep playing and retry transient failures with a delay; the server
does not automatically repeat potentially paid requests. Connection and read timeouts default to 10 and 120 seconds.

## Cache, concurrency and storage

`SubtitleService` hashes the encoded audio bytes and fixed transcription/filter settings using SHA-256. Filenames do
not identify the media. It reads hashes as a stream instead of allocating a second full upload buffer. One lock processes
uncached chunks sequentially. Each waiting request checks the cache again after acquiring the lock, avoiding duplicate
successful transcriptions. This is simpler than managing separate locks for different chunks, but new chunks may wait
behind a slow provider request. Cached lookups bypass this lock; HTTP requests can still wait if all servlet threads are
busy. Request work belongs to the server's servlet threads, not JavaFX.

`SubtitleStore` protects its map and usage total with one monitor. It writes both to a temporary JSON file and replaces
the committed state together. The default `./data/subtitles.json` is relative to the server's working directory; set
`SUBTITLE_STORE_FILE` to an absolute path to share the same store between IntelliJ and Maven launches. Run only one
server process per store file. No original audio is retained beyond multipart request processing.

The cache retains at most 256 chunks, evicting the oldest inserted entries; eviction never subtracts usage. Change
`subtitles.cache-max-entries` in configuration if needed. Restarting the server reloads cached results and usage, and
cached chunks remain available when OpenAI cannot be reached. A corrupt store fails startup instead of resetting the
total silently. A failed write retains the result in memory and blocks further storage operations until saving succeeds.
Any other completed result is retained before retrying that save, so a storage failure does not discard paid work.
Abrupt process or machine failure between provider completion and a successful state write can still lose that update.

## Verification

```powershell
.\mvnw.cmd verify
```

Tests use real local HTTP multipart requests and a fake transcription provider. They cover request fields and auth,
word/segment filtering and threshold boundaries, concurrent deduplication, cache hits during provider outage, persistent
usage and eviction, invalid uploads, provider failures, timeouts, malformed responses and recovery. They never call
OpenAI or incur charges. Actual Whisper output still needs to be checked with a real audio chunk after setting a key.
