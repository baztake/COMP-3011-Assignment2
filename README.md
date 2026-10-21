# COMP3011 Assignment 2 - Java Local Project

Multithreaded implementation of the Video Player app.

## Planning

### Questions

Based on Seminar 7, the first step is to ask the following questions:

1. Overlap opportunity: Is there useful work that can overlap?

- There's a few things that can overlap. First, decoding an upcoming frame can overlap applying effects to earlier
  frames, while also displaying the current completed frame. At the same time, we can process multiple frames
  simultaneously, while GUI events are handled and audio output continues.
- The main consideration here is that the effects on the same frame must be applied in the order defined in the
  commandline args, and completed frames must be displayed in their original sequence.

2. Workload characteristics: Is the workload mostly waiting for I/O, mostly computation, or mixed?

- The workload is mixed. We read the video file from disk, decode the frames, apply effects to the frames, and display
  the frame.
- As more effects are stacked, the main bottleneck will be the CPU, as the effects are applied in sequence to each
  frame. The I/O is mostly sequential and can be buffered.

3. Usable execution capacity: Is parallel execution capability actually available and usable?

- In a multicore machine, yes. Separate frames can be processed in parallel, and the GUI and audio output can run on
  separate threads.
- The main challenge is managing the synchronisation and communication between the different threads.
- Based on this, we must also measure how effectively the available CPU capacity is used.

4. Introduced costs: What switching, resource, contention and coordination costs are introduced?

- Adding multiple threads introduces scheduling and context switching overheads. Frame copies for worker threads and
  effect stacking increase memory usage. Workers may also compete for CPU time, and the GUI thread may be blocked if the
  worker threads are not managed properly.
- This requires careful coordination of the workers, which adds additional overhead.

5. Justified complexity: Does the benefit justify the added complexity?

- At the moment, the single-threaded app can cause delays in playback and GUI responsiveness, especially when multiple
  effects are applied.
- A multithreaded implementation can improve responsiveness and allow for smoother playback, making the added complexity
  worthwhile.
- The final implementation should demonstrate a significant improvement in performance and responsiveness, justifying
  the added complexity of multithreading.

### High-Level Design

These questions and answers give us a good starting point for designing the multithreaded implementation.

The next step is to identify exactly what tasks the app is performing:

- Read and decode the next frame
- Copy decoded frame data
- Apply effects to a frame
- Convert a processed frame into a display image
- Present a completed frame
- Handle buttons, keyboard events, resizing
- Write prepared audio samples

With this, we can identify what tasks can be performed in parallel, and what tasks must be performed in sequence.

| Task                                           | Can overlap                                           | Constraints                                                                       |
|------------------------------------------------|-------------------------------------------------------|-----------------------------------------------------------------------------------|
| Read and decode the next frame                 | Processing earlier frames, presentation, audio output | Single thread owns grabber. Callers must not race                                 |
| Copy decoded frame data                        | ...                                                   | Copy completed before decoder reuses source buffer                                |
| Apply effects to a frame                       | ...                                                   | Single worker owns a frame and its processor chain. Effects applied in order      |
| Convert a processed frame into a display image | ...                                                   | All effects on that frame must be processed first. Published pixels are immutable |
| Present a completed frame                      | ...                                                   | Runs on JavaFX, in frame order, at correct time                                   |
| Handle buttons, keyboard events, resizing      | ...                                                   | Runs on JavaFX, shares thread with presentation.                                  |
| Write prepared audio samples                   | Video processing/presentation                         | Single worker owns sound devices, follows playback clock                          |

So for each frame, we have a sequential dependency of:
Decode → Copy → Effect1 → Effect2 → ... → EffectN → Convert → Present

However, we can process multiple frames in parallel, as long as we respect the order of presentation. The order of
presentation is important, as we may receive frames out of order from the effect processors.

### Low-Level Design

We now have an idea of what tasks can be performed in parallel, and what tasks must be performed in sequence. However,
there are still some important details to consider.

#### Components and Responsibility

We will use a pipeline with one decoder and a fixed pool processing different frames. Each worker applies the complete
selected effect chain to its frame. Effects on one frame remain sequential, different frames can execute in parallel.

The existing controller will continue to translate GUI events into model requests. The model will own playback state and
delegate background preparation rather than doing the expensive work itself.

| Component             | Responsibility                                                                                                          | Main operations                                                                                    |
|-----------------------|-------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `VideoPlayerModel`    | Receive controller requests, own visible playback state, poll results and present images on JavaFX                      | Existing play/pause/seek/stop methods                                                              |
| `PlaybackSession`     | Own one playback run from a particular start position, including decoder, processing pool, clock and audio output       | `start()`, `configure(...)`, `requestStop()`, `awaitTermination(...)`, completion/error inspection |
| `FrameProcessingPool` | Submit bounded frame work, maintain worker-private state, expose results in submission order and release cancelled work | Admission/submission, `pollReady()`, retirement, cancellation and termination                      |
| `PlaybackClock`       | Provide a common media-time origin and consistent pause/resume timing                                                   | `start(...)`, `setPaused(...)`, `isDue(...)`, deadline calculation                                 |
| `AudioOutput`         | Own the audio device, consume copied PCM samples and apply audio controls                                               | Enqueue samples, configure state, stop and inspect drain/termination state                         |

#### Threading Model and Ownership

The following table will be used to define where synchronisation is required, and where ownership eliminates it.

| Resource/state                                                    | Owner                                                           | Communication/protection                                                  |
|-------------------------------------------------------------------|-----------------------------------------------------------------|---------------------------------------------------------------------------|
| Grabber, decoder position and borrowed decode buffers             | Decoder thread                                                  | Other threads never call its decode, seek or close methods                |
| A copied input frame                                              | Decoder before submission, one processing task after acceptance | Explicit ownership transfer and exactly-once release                      |
| Effect chain, scratch arrays and conversion working state         | Individual processing worker                                    | Private worker state, never shared with another worker                    |
| Presented image, logical seek position and visible playback state | JavaFX thread                                                   | Background work publishes results, GUI execute on JavaFX                  |
| Audio device and partially written output chunk                   | Audio thread                                                    | Copied sample packets and published control state                         |
| Playback clock                                                    | JavaFX and audio thread                                         | Small synchronized methods covering complete timing decisions             |
| Current generation ID                                             | Written by JavaFX, read by lifecycle work                       | volatile `long`, JavaFX increments it                                     |
| Ordered result tickets                                            | Decoder producer and JavaFX consumer                            | Thread-safe FIFO queue, short coordination for submission versus shutdown |
| Outstanding-frame capacity                                        | Decoder admission and retirement/cancellation paths             | Semaphore permits with exactly-once release                               |
| Session startup/teardown                                          | Single lifecycle executor                                       | Ordered lifecycle operations, with no GUI-thread waits                    |

The fixed executor limits the number of processing workers. The semaphore limits outstanding frames (queued jobs,
running jobs, completed results and the next frame waiting for presentation)

#### Passing Frames Between Threads

The decoder copies each video frame before submitting it to the worker pool, because the grabber can reuse its frame
memory on the next grab. Each task includes the frame number, timestamp and playback generation.

Each frame belongs to one worker while its effects are being applied. Once processing finishes, the worker passes the
completed image back for display and does not change it again. This avoids multiple threads modifying the same frame.

#### Frame Processing and Ordering

The worker pool processes different frames in parallel. Within each frame, effects still run sequentially in
command-line order, including repeated effects.

Each worker has its own effect processors to avoid sharing their mutable data. Random effects use the same seed for each
effect occurrence across workers, so the output does not depend on which worker processes a frame.

Frames can finish processing out of order, so their results are kept in submission order. The GUI only checks the next
expected frame and displays it when it is ready and its timestamp is due. It never waits for an unfinished task.

A semaphore limits the number of outstanding frames, initially set to twice the worker count. A slot is released when a
frame is displayed or discarded. This prevents decoding from getting too far ahead and using excessive memory.

This preserves the required ordering while allowing independent work to overlap.

#### Playback Timing and Audio

A shared playback clock determines when frames should be displayed. Pausing freezes the clock, and resuming adjusts it
so playback continues from the same position.

Audio runs on a separate thread using the same playback timing. Audio samples are copied before the decoder continues,
and only the audio thread accesses the audio device.

The JavaFX thread handles controls and displays completed frames. Decoding, effects, audio writes and waiting for
workers happen elsewhere, keeping the interface responsive.

#### Playback Controls

| Action            | Behaviour                                                                                                          |
|-------------------|--------------------------------------------------------------------------------------------------------------------|
| Pause             | Freeze playback timing and pause audio, allowing only the bounded amount of processing already submitted to finish |
| Resume            | Continue the clock and audio from the paused position                                                              |
| Seek              | Cancel the old session, clear its buffered output and start decoding at the requested position                     |
| Seek while paused | Prepare one preview frame and remain paused                                                                        |
| Stop or close     | Cancel tasks, discard buffered output and release resources                                                        |
| End of file       | Display the remaining prepared frames and finish queued audio before completing playback                           |

Each new playback session has a generation number. Results from an older generation are ignored, preventing an old frame
from appearing after a seek or stop.

Session cleanup runs outside the GUI thread. Cancellation interrupts blocked workers, and copied frames are released
whether their tasks finish or are cancelled. Any background failure is reported to the GUI and stops the affected
session.

#### Testing and Performance

Correctness will be checked before comparing performance.

- Compare frame order, effect order and output against the single-threaded version, including repeated and random
  effects.
- Force frames to finish out of order and check that display order stays correct.
- Test pause, resume, rapid seeking, seeking while paused, stopping with full buffers and reaching the end of the video.
- Check that errors and cancellation release resources without freezing the GUI.

Performance tests will use the same video, effects and machine for both versions. Several runs will compare processing
time, playback smoothness, GUI responsiveness and memory use, with audio both enabled and disabled.

Worker counts and buffer sizes will be adjusted using these results. More workers may improve effect processing, but
decoding, display timing and coordination still limit the overall improvement.

The final checks will include `mvn verify` and manual playback testing.

## Implementation Log

- First steps, starting with the effects. A few of them use static index counters, so I update them to pass in the
  instanceIndex variable during construction to prevent a race condition if two threads try to instantiate the object at
  once. This is documented in the Javadocs of every constructor that needs it.
- Following on from this, I updated the FrameEffect enum to use the new variable in construction, then update that usage
  in the VideoPlayerModel. Updated tests accordingly