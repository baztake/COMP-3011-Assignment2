# Subtitle server

I reused my Assignment 1 Spring Boot server and removed the old recording website and endpoints. This version takes audio
from the player, sends it to Whisper, and returns subtitle timings. I used OpenAI Codex to help with the changes.

## Running the server

Use Java 25 and run the commands from the `SubtitleServer` folder. Set `OPENAI_API_KEY` in the environment and run the server with Maven.

```powershell
.\mvnw.cmd spring-boot:run
```

For the viva, build the JAR and use the launcher to read the key from the USB drive. The launcher keeps the key out of the terminal output and Java command line.

```powershell
.\mvnw.cmd package
.\start-server.ps1 -KeyFile 'E:\key.txt'
```

The server runs at `http://localhost:8080`.

## What the server does

- `POST /api/v1/subtitles` takes an audio upload in a multipart field called `file`, up to 24 MiB. The player sends
  WAV chunks. Whisper returns word and segment timings in seconds from the start of each chunk. The player uses
  these timings to format and display the captions.
- The server drops segments only when `no_speech_prob > 0.6` and `avg_logprob < -1.0` are both true.
- `GET /api/v1/global/usage` returns a value such as `{ "usage": 13.0 }`. This is the total number of audio seconds
  processed successfully. Cached requests don't increase it.

## Caching

I cache results using a hash of the audio and the transcription settings. This means the same upload can be reused
even if the file is renamed. I used one lock to avoid sending duplicate requests when two uploads arrive together.
This also means different uncached uploads have to wait their turn.

The cache and usage total are saved in `data/subtitles.json`. The server keeps up to 256 chunks and removes the oldest
entry when it needs space. Removing a cache entry doesn't change the usage total.

## Testing and logs

Run the tests with `.\mvnw.cmd verify`. The tests use a fake transcription service, so they don't need an API key
or make paid requests. Server logs are in `logs/subtitle-server.log` under the folder you launched it from.
