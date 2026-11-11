document.addEventListener('DOMContentLoaded', () => {
    const button = document.getElementById('record-button');
    const label = document.getElementById('record-label');
    const status = document.getElementById('status');
    const timer = document.getElementById('timer');
    const errorEl = document.getElementById('error');
    const transcript = document.getElementById('transcript');

    // Define segment duration in milliseconds (8 seconds).
    const SEGMENT_MS = 8000;

    // Define state variables.
    let stream = null;
    let recorder = null;
    let segmentTimer = null;
    let startTime = null;
    let timerId = null;
    let recording = false;
    let segmentNumber = 0;
    let transcriptions = [];

    // Show an error message in the UI.
    const showError = (m) => {
        errorEl.textContent = m || 'An error occurred';
        errorEl.hidden = false;
    };

    // Update the timer display.
    function tick() {
        if (!startTime) return;
        const s = Math.floor((Date.now() - startTime) / 1000);
        timer.textContent = `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
    }

    // Create a new MediaRecorder for the current segment.
    function createRecorder() {
        // Use the WebM format with Opus codec for better compression and quality.
        const options = {};
        options.mimeType = 'audio/webm;codecs=opus';
        options.audioBitsPerSecond = 24000;

        const r = new MediaRecorder(stream, options);

        // Handle data availability events to upload recorded segments.
        r.ondataavailable = (event) => {
            if (!event.data || event.data.size === 0) {
                return;
            }

            // Increment the segment number for each new segment.
            const segment = segmentNumber++;

            console.log(
                `Segment ${segment}:`,
                event.data.type,
                `${(event.data.size / 1024).toFixed(1)} KB`
            );

            // Upload the recorded segment to the server for transcription.
            upload(event.data, segment).catch((err) => {
                console.error(`Segment ${segment} failed:`, err);
            });
        };

        // Handle errors during recording.
        r.onerror = (event) => {
            console.error('MediaRecorder error:', event);
            showError('Recording failed.');
        };

        return r;
    }

    // Start a new recording segment.
    function startSegment() {
        if (!recording) {
            return;
        }

        // Create a new MediaRecorder for the current segment.
        recorder = createRecorder();

        // When the recorder stops, start the next segment unless the user has stopped recording.
        recorder.onstop = () => {
            recorder = null;

            // Start the next segment unless the user stopped recording.
            if (recording) {
                startSegment();
            }
        };

        // Start recording the current segment.
        recorder.start();
        console.log(
            'Started segment recorder:',
            recorder.mimeType
        );

        // Set a timer to stop the current segment after the defined duration.
        segmentTimer = setTimeout(() => {
            if (!recording || !recorder) {
                return;
            }

            if (recorder.state === 'recording') {
                recorder.stop();
            }
        }, SEGMENT_MS);
    }

    // Start recording and transcribing.
    async function start() {
        // Reset the error message and hide it.
        errorEl.hidden = true;

        // Request microphone access from the user.
        try {
            stream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    channelCount: 1
                }
            });
        } catch (err) {
            showError(
                err && err.name === 'NotAllowedError'
                    ? 'Microphone access was blocked. Allow it in your browser settings and try again.'
                    : "Couldn't access your microphone. Check it's connected and try again."
            );
            return;
        }

        // Reset the transcript display and initialize state variables.
        transcript.textContent = '';
        recording = true;
        segmentNumber = 0;
        transcriptions = [];

        startTime = Date.now();
        timerId = setInterval(tick, 250);

        // Update the UI to reflect that recording has started.
        button.setAttribute('aria-pressed', 'true');
        label.textContent = 'Stop recording';
        status.textContent = 'Recording';

        // Start the first recording segment.
        startSegment();
    }

    // Stop recording and transcribing.
    function stop() {
        // Update state variables and clear timers.
        recording = false;

        if (timerId) {
            clearInterval(timerId);
            timerId = null;
        }

        if (segmentTimer) {
            clearTimeout(segmentTimer);
            segmentTimer = null;
        }

        // Update the UI to reflect that recording has stopped.
        button.setAttribute('aria-pressed', 'false');
        label.textContent = 'Start recording';
        status.textContent = 'Transcribing…';

        // Stop the current recorder if it's still recording.
        if (recorder && recorder.state === 'recording') {
            try {
                recorder.stop();
            } catch (e) {
                console.warn('Error stopping recorder:', e);
                recorder = null;
            }
        }

        // Stop all tracks of the media stream to release the microphone.
        if (stream) {
            try {
                stream.getTracks().forEach(track => track.stop());
            } catch (e) {
                console.warn('Error stopping tracks:', e);
            }

            stream = null;
        }
    }

    // Upload an audio chunk to the server for transcription.
    async function upload(blob, segmentNumber) {
        // Create a FormData object to send the audio file.
        const form = new FormData();
        form.append(
            'file',
            blob,
            `recording-${segmentNumber}.webm`
        );

        try {
            console.log(
                `Uploading segment ${segmentNumber}:`,
                blob.type,
                `${(blob.size / 1024).toFixed(1)} KB`
            );

            // Send the audio chunk to the server for transcription.
            const res = await fetch('/api/v1/transcribe', {
                method: 'POST',
                body: form
            });

            // Check if the server responded with an error.
            if (!res.ok) {
                const body = await res.text();

                console.error(
                    `Segment ${segmentNumber} failed:`,
                    res.status,
                    body
                );

                showError(`Server error (${res.status})`);
                return;
            }

            // Parse the JSON response from the server.
            const payload = await res.json();

            // Update the transcript display with the new transcription if available.
            if (payload && payload.transcription) {
                let transcription = payload.transcription.trim();

                // Convert the first letter to lowercase for segments after the first one to maintain sentence flow.
                if (segmentNumber > 0) {
                    transcription = transcription.replace(
                        /^([A-Z])/,
                        (match) => match.toLowerCase()
                    );
                }

                transcriptions[segmentNumber] = transcription;

                transcript.textContent = transcriptions
                    .filter(transcription => transcription)
                    .join(' ');
            }

        } catch (err) {
            // Handle network or other errors during the upload process.
            console.error(
                `Upload segment ${segmentNumber} failed:`,
                err
            );

            showError(
                err && err.message
                    ? err.message
                    : 'Upload failed'
            );
        }
    }

    // Wire up the button.
    button.addEventListener('click', () => { if (recording) stop(); else start(); });

    // Ensure recording is stopped if the page is unloaded.
    window.addEventListener('beforeunload', () => { if (recording) stop(); });
});
