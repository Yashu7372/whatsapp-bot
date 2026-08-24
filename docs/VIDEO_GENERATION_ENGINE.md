# Video Generation Engine

The video engine is intentionally small: every layer produces typed artifacts, a gate validates those artifacts, and only then does the workflow advance.

## Core rule

```text
stage -> artifacts -> gate -> accepted state -> next stage
```

A failed gate does not push invalid output downstream. The job remains at the last accepted state with the rejected artifacts and gate message available for diagnosis/retry.

## State machine

```text
INTAKE
  -> CONTENT_LOCKED
  -> AUDIO_LOCKED
  -> VISUAL_PLAN_LOCKED
  -> PRESENTER_GENERATED
  -> COMPOSITION_CHECKED
  -> RENDERED
  -> VERIFIED
```

`FAILED` is terminal. `FACELESS` mode intentionally skips presenter and lip-sync capabilities while still passing through the same state machine and gates.

## Pluggable capabilities

The engine knows capabilities, not vendors:

- `CONTENT`
- `AUDIO`
- `SPEECH_ALIGNMENT`
- `VISUAL_PLAN`
- `PRESENTER`
- `LIP_SYNC`
- `COMPOSITION`
- `RENDER`
- `VERIFY`

A provider implements only `GenerationAdapter`:

```java
public interface GenerationAdapter {
    GenerationCapability capability();
    String name();
    default int priority() { return 100; }
    default boolean supports(GenerationContext context) { return true; }
    StageResult generate(GenerationContext context);
}
```

Adding or changing a provider should not require editing `VideoGenerationEngine` or the state machine. Add another adapter bean for the capability and use `supports(...)` / `priority()` to select it.

## Current adapters

| Capability | Current implementation | Notes |
| --- | --- | --- |
| CONTENT | Existing `VideoScriptService` | Reuses the configured LangChain4j chat model; no LLM vendor is hard-coded in the engine. |
| AUDIO | Kokoro through the Python media worker | Returns measured narration duration. |
| SPEECH_ALIGNMENT | Optional adapter slot | Add word-level alignment without changing the pipeline. |
| VISUAL_PLAN | Structured shot list from the generated script | Can later be replaced by a richer planner. |
| PRESENTER | Adapter slot | Required for `PRESENTER` / `DIALOGUE`; job remains `BLOCKED` when no compatible adapter is installed. |
| LIP_SYNC | Optional adapter slot | Runs only when an adapter is present and the mode requires it. |
| COMPOSITION | Deterministic composition manifest | Combines only previously gated artifacts. |
| RENDER | Existing FFmpeg Python worker | Current adapter explicitly supports `FACELESS`; presenter renderers should be separate adapters. |
| VERIFY | ffprobe + full FFmpeg decode check | Produces the QA report consumed by the final gate. |

The existing character/SadTalker/dialogue renderer remains available on the branch. It should be exposed through presenter/dialogue adapters rather than being embedded into the generic engine.

## Audio is the master timeline

The script duration is only a target. After narration is generated, the worker measures the actual audio duration with `ffprobe`. `AUDIO_LOCKED` requires that measured duration. Rendering then reuses the same narration file and trims the final MP4 to the measured duration instead of generating another TTS track.

This prevents script, scenes, captions, presenter motion and final video duration from drifting apart.

## Gates

The default gate validates the minimum artifacts needed to safely enter each state:

```text
CONTENT_LOCKED        requires SCRIPT
AUDIO_LOCKED          requires NARRATION_AUDIO + measured duration
VISUAL_PLAN_LOCKED    requires VISUAL_PLAN
PRESENTER_GENERATED   requires presenter output except FACELESS
COMPOSITION_CHECKED   requires COMPOSITION_PLAN
RENDERED              requires FINAL_VIDEO
VERIFIED              requires QA_REPORT with passed=true
```

More quality gates can be added as independent `GenerationGate` beans. For example, a presenter adapter can add identity, lip-sync or pilot-quality gates without changing the default pipeline.

## Durable jobs

`video_generation_jobs` stores:

- current accepted state
- job status (`READY`, `RUNNING`, `BLOCKED`, `COMPLETED`, `FAILED`)
- generation mode and platform
- options
- accumulated typed artifacts
- last gate result
- error details

This allows a job to resume from its last accepted layer instead of regenerating everything after a later failure.

## API

Create a generation:

```http
POST /api/v1/video-generations
```

Example body:

```json
{
  "topic": "3 mistakes first-time Dubai property investors make",
  "mode": "FACELESS",
  "platform": "INSTAGRAM",
  "targetDurationSeconds": 30,
  "options": {
    "contentType": "REEL",
    "style": "ENGAGING",
    "voice": "af_heart",
    "templateCode": "PRODUCT_HOOK_V1",
    "brandName": "Example Brand",
    "callToAction": "Message us to learn more"
  }
}
```

Advance exactly one layer/gate:

```http
POST /api/v1/video-generations/{id}/advance
```

Run through all currently available layers until completed or blocked:

```http
POST /api/v1/video-generations/{id}/run
```

Inspect state/artifacts/gate result:

```http
GET /api/v1/video-generations/{id}
```

Retry from the last accepted state:

```http
POST /api/v1/video-generations/{id}/retry
```

## Design boundaries

- Spring Boot owns orchestration, tenant isolation, state, gates and retries.
- Python owns media-heavy execution such as TTS, FFmpeg, presenter/lip-sync workers and technical media QA.
- The engine does not know Kokoro, SadTalker, Gemini, HeyGen or another provider name.
- Existing specialized render paths remain usable while they are progressively exposed as adapters.
- Do not add a framework/DAG dependency for this pipeline unless the workflow genuinely requires runtime graph construction. The ordered state machine is easier to debug and maintain.
