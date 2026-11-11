# Test Suite Overview

This document explains why each test file exists, what it expects to assert, and which assurances each test provides about the system.

---

## Summary of test patterns used
- Unit tests: verify individual class behavior in isolation using Mockito and helper classes.
- Controller unit tests (`@WebMvcTest`): validate HTTP behavior, status codes and structured error responses, mocking downstream services.
- Integration/concurrency tests (`@SpringBootTest` + TestRestTemplate): exercise the real controller wiring under concurrent load using stubs for external services.
- MockRestServiceServer: used to simulate HTTP responses from the external STT provider when exercising the service that builds real HTTP calls.

---

## Tests and purpose

### service/UptimeServiceTest.java
- Why: verify uptime calculation, stability of stored start time, and thread-safety under concurrent access.
- Expected results: non-null timestamps; initial uptime ~0s; start time stable across calls; uptime increases after delay; under 200 concurrent calls the start time is identical and uptimes are non-negative.
- Assurance: ensures the service measures elapsed time correctly and is safe to call concurrently.

### service/SpeechToTextServiceTest.java
- Why: validate orchestration between TranscriptionService and GlobalStatsService.
- Expected results: service forwards file to TranscriptionService, returns its result, records input/output tokens via GlobalStatsService; propagates exceptions and avoids recording usage when upstream fails.
- Assurance: ensures usage accounting is accurate and not recorded when transcribing fails.

### service/ShutdownServiceTest.java
- Why: confirm graceful-shutdown control logic and concurrency guard.
- Expected results: first request triggers applicationContext.close(); subsequent requests throw ShutdownInProgressException.
- Assurance: only one shutdown proceeds and concurrent callers are protected.

### service/OpenAiTranscriptionServiceTest.java
- Why: exercise the code that builds and parses HTTP requests/responses to the STT provider.
- Expected results: successful JSON response is parsed into text and usage; missing usage fields default to zero; server errors produce HttpServerErrorException; null/empty response yields IllegalStateException.
- Assurance: validates request formation, response parsing and proper propagation of upstream errors.

### service/GlobalStatsServiceTest.java
- Why: validate token recording, null handling, accumulation and concurrency safety.
- Expected results: initial zeros; additions accumulate; null inputs are ignored; concurrent (250 calls) updates produce exact totals (no lost updates).
- Assurance: confirms atomic counters behave correctly under high contention.

### controller/TranscriptionControllerTest.java
- Why: unit-test controller HTTP behavior and failure modes while mocking the service.
- Expected results: valid file -> 200 with transcription JSON; empty file -> 200 with empty transcription; missing/wrong file part -> 400; downstream exception -> 500 with structured error body (status, error, path, message).
- Assurance: controller input validation, error translation and JSON response contract are correct.

### controller/TranscriptionControllerConcurrencyTest.java
- Why: integration-style stress test to ensure controller+stack can concurrently handle many upload requests.
- Expected results: 250 concurrent requests (stubbed transcription service with 50ms latency) all return 200 OK; elapsed time is much less than sequential worst-case.
- Assurance: verifies thread pool / request handling and that the app does not serialize work or crash under high concurrency.

### controller/GlobalStatsControllerTest.java
- Why: unit-test the global stats controller output and HTTP method constraints.
- Expected results: GET /api/v1/global/stats returns the service totals (mocked) and unsupported methods return 405.
- Assurance: response payload contract and method restrictions are enforced.

### controller/GlobalStatsControllerConcurrencyTest.java
- Why: integration concurrency test ensuring aggregated global stats are correctly updated under many simultaneous transcribe requests.
- Expected results: after 100 concurrent transcribe requests (stub returns input=3, output=7 per call), the difference in totals equals requestCount * per-call tokens; all transcribe calls return 200.
- Assurance: validates end-to-end token accounting under concurrency (no lost updates across controller → service → global stats path).

### controller/AdministratorControllerTest.java
- Why: check admin endpoints behaviour (uptime, shutdown) in the web layer.
- Expected results: GET uptime returns valid timestamps and non-negative uptime; POST shutdown returns 202 and calls ShutdownService.requestShutdown() once.
- Assurance: admin endpoints expose accurate uptime and correctly invoke shutdown logic.

### controller/AdministratorControllerConcurrencyTest.java
- Why: concurrency test for shutdown endpoint to ensure only one accepted shutdown request while others receive a conflict.
- Expected results: with 250 concurrent POST requests to /api/v1/admin/shutdown exactly one response is 202 Accepted, the rest are 409 Conflict.
- Assurance: verifies shutdown concurrency guard (atomic boolean) prevents multiple shutdowns and returns expected HTTP codes for racing clients.

---

## Cross-cutting assurances provided by the test suite
- Concurrency safety: multiple tests exercise thread-safety of GlobalStatsService, UptimeService, and shutdown flow.
- Error handling: controllers and services are tested for upstream failures and return/propagate appropriate exceptions and HTTP responses.
- Integration boundaries: external HTTP calls to the STT provider are mocked/stubbed (MockRestServiceServer, test @Primary beans), preventing flaky network dependence and validating request/response glue.
- API contracts: controllers are validated for status codes and response body shape (JSON fields) so clients have stable expectations.
