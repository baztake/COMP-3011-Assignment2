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

4. Introduced costs: What switching, resource, contention, and coordination costs are introduced?

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

| Component             | Responsibility                                                                                                    | Main operations                                                                                    |
|-----------------------|-------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `VideoPlayerModel`    | Receive controller requests, own visible playback state, poll results and present images on JavaFX                | Existing play/pause/seek/stop methods                                                              |
| `PlaybackSession`     | Own one playback run from a particular start position, including decoder, processing pool, clock and audio output | `start()`, `configure(...)`, `requestStop()`, `awaitTermination(...)`, completion/error inspection |
| `FrameProcessingPool` | Submit bounded frame work, preserve result order and manage input cleanup                                         | `submit()`, `pollReady()`, `close()`, `requestStop()`, `awaitTermination(...)`                     |
| `PlaybackClock`       | Provide a common media-time origin and consistent pause/resume timing                                             | `start(...)`, `setPaused(...)`, `isDue(...)`, deadline calculation                                 |
| `AudioOutput`         | Own the audio device, consume copied PCM samples and apply audio controls                                         | Enqueue samples, configure state, stop and inspect drain/termination state                         |

#### Threading Model and Ownership

The following table will be used to define where synchronisation is required, and where ownership eliminates it.

| Resource/state                                                    | Owner                                                           | Communication/protection                                        |
|-------------------------------------------------------------------|-----------------------------------------------------------------|-----------------------------------------------------------------|
| Grabber, decoder position and borrowed decode buffers             | Decoder thread                                                  | Other threads never call its decode, seek or close methods      |
| A copied input frame                                              | Decoder before submission, one processing task after acceptance | Transfer on accepted submission; exactly one cleanup owner      |
| Effect chain, scratch arrays and conversion working state         | Individual processing worker                                    | Private worker state, never shared with another worker          |
| Presented image, logical seek position and visible playback state | JavaFX thread                                                   | Background work publishes results, GUI execute on JavaFX        |
| Audio device and partially written output chunk                   | Audio thread                                                    | Copied sample packets and published control state               |
| Playback clock                                                    | JavaFX and audio thread                                         | Small synchronized methods covering complete timing decisions   |
| Current generation ID                                             | Written by JavaFX, read by lifecycle work                       | volatile `long`, JavaFX increments it                           |
| Ordered result futures                                            | Decoder submits, JavaFX polls                                   | FIFO queue protected by the pool monitor                        |
| Result queue capacity                                             | Decoder submission and JavaFX polling                           | Pool monitor protects queue size and coordinates wait/notifyAll |
| Session startup/teardown                                          | Single lifecycle executor                                       | Ordered lifecycle operations, with no GUI-thread waits          |

The executor limits processing workers and the result queue limits accepted, unpolled submissions to N futures. This
includes queued, running, and completed tasks. During integration, JavaFX must poll only when its single preparedFrame
slot is empty, giving N unpolled submissions plus one pending presentation frame. The currently displayed image, a
copied input held by a waiting decoder, and audio buffers are additional allocations outside this bound.

#### Passing Frames Between Threads

The decoder copies each video frame before submitting it to the worker pool, because the grabber can reuse its frame
memory on the next grab. Each task includes the frame number, timestamp, and playback generation.

Each frame belongs to one worker while its effects are being applied. Once processing finishes, the worker passes the
completed image back for display and does not change it again. This avoids multiple threads modifying the same frame.

#### Frame Processing and Ordering

The worker pool processes different frames in parallel. Within each frame, effects still run sequentially in
command-line order, including repeated effects.

Each worker has its own effect processors to avoid sharing their mutable data. Random effects use the same seed for each
effect occurrence across workers, so the output does not depend on which worker processes a frame.

Frames can finish processing out of order, so their results are kept in submission order. The GUI only checks the next
expected frame and displays it when it is ready and its timestamp is due. It never waits for an unfinished task.

The result queue uses a configurable capacity, initially planned as twice the worker count. A full queue makes the
decoder wait on the pool monitor. Waiting releases the monitor so polling and shutdown can proceed. The condition is
checked in a loop after waking. Removing a completed or failed head frees a slot and notifies waiting submitters. A
frame finishing processing alone does not free its slot.

This preserves the required ordering while allowing independent work to overlap.

#### Playback Timing and Audio

A shared playback clock determines when frames should be displayed. Pausing freezes the clock, and resuming adjusts it
so playback continues from the same position.

Audio runs on a separate thread using the same playback timing. Audio samples are copied before the decoder continues,
and only the audio thread accesses the audio device.

The JavaFX thread handles controls and displays completed frames. Decoding, effects, audio writes, and waiting for
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

The pool has two shutdown paths. close () rejects new submissions but lets accepted tasks finish and keeps their results
available for polling. requestStop () interrupts workers, cancels accepted tasks, and discards queued results. Both wake
submitters waiting for space. A rejected or interrupted submission leaves input ownership with its caller.

FrameTask uses an atomic claim to select who closes each accepted input. If queued cancellation claims first, it closes
the input without running processors. If execution claims first, its final clean-up closes the input after processing
exits. This prevents double release and cancellation closing native input still in use. Failed close attempts are logged
without preventing remaining cancellation; logging does not guarantee that clean-up succeeded.

Worker interruption checks run before effects and before conversion. An effect already executing may delay stopping.
After close or requestStop has returned, a lifecycle thread can call awaitTermination to confirm worker exit. JavaFX
must not wait for termination. A cancelled future does not establish that its worker has exited or input clean-up has
finished.

#### Testing and Performance

Correctness will be checked before comparing performance.

- Compare frame order, effect order and output against the single-threaded version, including repeated and random
  effects.
- Force frames to finish out of order and check that display order stays correct.
- Test pause, resume, rapid seeking, seeking while paused, stopping with full buffers, and reaching the end of the
  video.
- Check that errors and cancellation release resources without freezing the GUI.

Performance tests will use the same video, effects, and machine for both versions. Several runs will compare processing
time, playback smoothness, GUI responsiveness, and memory use, with audio both enabled and disabled.

Worker counts and buffer sizes will be adjusted using these results. More workers may improve effect processing, but
decoding, display timing, and coordination still limit the overall improvement.

The final checks will include `mvn verify` and manual playback testing.

## Implementation Log

- First steps, starting with the effects. A few of them use static index counters, so I update them to pass in the
  instanceIndex variable during construction to prevent a race condition if two threads try to instantiate the object at
  once. This is documented in the Javadocs of every constructor that needs it.
- Following on from this, I updated the FrameEffect enum to use the new variable in construction, then update that usage
  in the VideoPlayerModel. Updated tests accordingly
- Based on my plan, I started working on the FrameProcessingPool as the first bit of concurrency in the application.
  This involves first creating a worker class that handles processing an individual frame. The worker class encapsulates
  the logic for applying the effects in order, then returning the processed frame for display by the JavaFX thread.
- An important concurrency consideration for these workers is that they must not share a mutable state, so it has been
  documented that each worker must be supplied with its own instances of the effect processors. This is to avoid any
  race conditions or unexpected behaviour when multiple workers are processing frames simultaneously.
- Implemented the first version of FrameProcessingPool using a fixed thread pool. Each executor thread gets its own
  FrameProcessingWorker through ThreadLocal storage, with the supplied factory responsible for creating separate,
  initialised processor instances.
- To preserve frame ordering, I queue futures when frames are submitted rather than when processing finishes. pollReady
  only checks the head of the queue, returning null if it is unfinished so later frames cannot overtake it.
- The submit, pollReady, and close methods are synchronised to protect the queue and coordinate submission with
  shutdown. Successfully submitted frames are closed in a finally block after processing, including when processing
  fails.
- Added a focused ordering test using Mockito and latches. The first task is held while the second progresses, then the
  test checks that polling returns null until the first is released and that results follow submission order.
- Next, I will limit outstanding frames so decoding cannot build up an unlimited backlog. Capacity will be released when
  a frame is displayed or discarded, rather than when processing finishes.
- Limited the result queue in the FrameProcessingPool to a fixed size. This doesn't include the single prepared frame
  stored by the JavaFX thread waiting for presentation.
- Submitting a frame now requires the pool to not be closed, and the size of the queue to be less than the capacity. If
  it isn't, the thread will sleep.
- Sleeping threads are awoken when a result is polled, which immediately checks the size again to prevent a race where
  two sleeping threads were woken up. We only plan for a single decoder thread at the moment, but this allows multiple.
- Added tests for sleeping on full queue and waking when space is freed to ensure concurrency-correctness.
- Next step is to add cancellation of frames in the queue, e.g. if we need to discard a frame before it is displayed, we
  don't want to waste resources processing it.
- Updated the FrameProcessingWorker to check for interrupts between adding effects, throwing an exception if
  interrupted.
- Added requestStop to interrupt processing, cancel accepted tasks, and discard buffered results.
- Encapsulated processing in a FrameTask with an atomic input-ownership claim. Queued cancellation releases its copied
  frame, while running tasks retain ownership until processing exits, preventing double release or closing input still
  in use.
- Added awaitTermination to report worker termination
- Added a test to verify that requestStop cancels queued work and releases accepted input frames, and that
  awaitTermination confirms the workers have stopped.
- Updated the planning notes to match the implemented queue limit, shutdown methods, and frame-input ownership.
- Defined dependency versions, fixed logging
- Added the PlaybackClock to support synchronised presentation and audio timing. Supports pausing and resuming, and a
  test to verify functionality.
- Next, integrate the playback clock into the video player model to synchronise presentation and audio timing.
- Connected PlaybackClock to the model's frame scheduling, pause/resume and audio timing, removing the duplicated timing
  fields and resume calculation. Logical seek positions remain owned by the model.
- Added an explicit paused-preview flag so seeking while paused displays one frame without resuming playback.
- Replaced the model's private PreparedFrame with the shared result record used by processing workers. Preparation
  timestamps are supplied explicitly by producers.
- Added the initial PlaybackSession composition code. It stores selected effects and creates workers with fresh,
  initialised processor chains.
- Added a basic test checking selected processing and initialisation. The model still uses its existing construction
  path until session integration replaces it.
- Connected PlaybackSession worker creation to FrameProcessingPool. Each processing thread creates its own initialised
  processor chain on first use.
- Updated the session test to process a frame through the pool, checking the selected effect, frame information, and
  playback generation.
- Next, I will connect the decoder to the pool, copying decoded frames before submission.
- Added VideoDecoder to separate video decoding from the session and frame processing. It owns opening, seeking,
  reading, and closing the grabber on one thread.
- Video metadata comes from the first decoded frame, which is also returned for processing. Documented borrowed frame
  ownership and captured frame numbers and timestamps in immutable snapshots.
- Added a basic test covering configuration, seeking, first-frame metadata, end of video, and grabber cleanup.
- Next, I will run the decoder on a session-owned thread and submit copied frames to the bounded pool, including cleanup
  when submission is interrupted or rejected.
- Connected VideoDecoder to PlaybackSession on a named decoder thread. Each frame is copied before submission, and
  interrupted or rejected submissions release their copies.
- Named processing threads and documented frame ownership, critical regions, and termination behaviour.
- Updated the session processing test and added a focused full-queue stop test checking copy cleanup and decoder
  closure.
- Next, I will add session pause/resume admission control and the paused-preview behaviour before model integration.
- Added a decoder admission gate to PlaybackSession. An initially paused session prepares one preview, then waits for
  resume before reading another frame.
- Protected pause and preview state with the session monitor. Resume and stop wake the waiting decoder, native reads and
  pool submission stay outside the critical region. Already admitted work may finish after pausing.
- Updated session startup calls and added a focused test checking one paused preview, admission waiting, and resume.
- Next, I will separate audio sample copying from audio-device access before adding the audio thread.
- Extracted audio sample conversion into AudioSampleCopier, separating it from AudioPlayer's device access.
- Documented that each decoder must own its copier and the audio-output thread must own device access.
- Next, I will add audio output ownership on a dedicated thread with a bounded queue of copied, timestamped PCM packets.
- Added AudioOutput with a bounded FIFO of copied, timestamped PCM packets and a dedicated audio-device owner thread.
- Kept active packets in the queue until fully written, handling partial writes, and waking blocked producers on stop.
- Added focused tests for partial writes and cancellation.
- Next, integrate audio decoding and output with playback timing, controls, and lifecycle handling.
- Added mixed audio and video reading to VideoDecoder while preserving the existing video-only session path.
- Added a test for audio followed by the first video frame and end of media.
- Next, connect copied audio packets to AudioOutput and coordinate startup with the shared playback clock.
- Now gracefully handles end of audio files. The audio owner writes accepted packets and drains device audio before
  closing, while requestStop continues to discard queued work.
- Added a test for writing, draining, closing, and rejecting submissions after input finishes.
- Next, coordinate audio and video startup and bounded queues before connecting the session to the player.
- Added an independent AudioDecoder that reads audio samples, copies them before the next grab and preserves timestamps.
