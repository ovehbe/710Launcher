# Voice Dictation Blockers Fixed - v1.9.7 Evidence

## Commit Information
- **Final HEAD**: 04b753bfe255a9b33da8178675a6cf3e97fa14d0
- **Version**: 1.9.7 (code 41)
- **Branch**: cursor/voice-dictation-e06b
- **Build Date**: 2026-11-07

## BLOCKER A - Geometry Overlap: FIXED ✓

### Issue
Text area overlapped with voice progress spinner by ~12dp. EditText had 12dp paddingEnd, spinner occupied 8-24dp from trailing edge, causing text at 984 to overlap spinner at 972-988.

### Fix Applied
- Increased `android:paddingEnd` from 12dp to 28dp in `activity_launcher.xml`
- Text now reaches 968, spinner starts at 972 (4dp gap)
- No overlap, stable bounds on show/hide

### Test Evidence
Added `VoiceProgressGeometryTest.kt` with 3 tests that FAIL on old code, PASS after fix:
- `testVoiceProgressDoesNotOverlapWithTextArea` - verifies no intersection between text area and spinner bounds
- `testVoiceProgressShowHideDoesNotShiftQueryBounds` - verifies stable bounds on show/hide
- `testLongQueryDoesNotOverlapSpinner` - verifies long queries don't overlap

**Test Output Before Fix:**
```
Text area [16, 984] must not overlap with spinner [972, 988].
Text right edge at 984, spinner left edge at 972.
Overlap: 12dp
```

**Test Output After Fix:**
```
textBounds=Rect(16, 0 - 968, 100), spinnerBounds=Rect(972, 42 - 988, 58)
No overlap, 4dp gap
```

### Code Changes
- `app/src/main/res/layout/activity_launcher.xml` line 134: `android:paddingEnd="28dp"`

## BLOCKER B - Stuck Circle: FIXED ✓

### Issue
`handleVoiceFinalResult()` no longer hides progress indicator because code assumes `onEndOfSpeech` always precedes `onResults`. Android doesn't guarantee this callback sequence.

### Fix Applied
- Restored unconditional progress hiding in `handleVoiceFinalResult()`
- Added session-safe terminal cleanup that works regardless of callback order
- Progress hidden by both `onEndOfSpeech` (normal path) and `onResults` (when onEndOfSpeech doesn't fire first)

### Test Evidence
Added `finalResultWithoutEndOfSpeechHidesProgress` test that:
- Simulates partial results → final results WITHOUT onEndOfSpeech
- Verifies progress is hidden by terminal result cleanup
- Verifies text is still inserted correctly

### Code Changes
- `app/src/main/java/com/meowgi/launcher710/ui/search/SearchOverlay.kt` line 1873:
  ```kotlin
  // Hide progress indicator unconditionally (terminal result cleanup)
  // onEndOfSpeech normally hides it first, but Android doesn't guarantee that callback order
  voiceProgress.visibility = GONE
  ```

## MISSING Test - Stale Session Protection: ADDED ✓

### Test Added
`oldSessionEndOfSpeechDoesNotHideNewSession` verifies that:
- Old-session `onEndOfSpeech` callback doesn't hide NEW session indicator
- Uses actual `RecognitionListener` callback paths (captures first listener, starts second session, fires stale callback)
- Not using private session flags to fake success
- Verifies new session progress remains visible when stale end-of-speech arrives

### Test Evidence
- Starts session 1, captures listener
- Cancels session 1, starts session 2
- Fires stale `onEndOfSpeech` from session 1 listener
- Verifies session 2 progress remains VISIBLE (stale callback ignored)
- Session 2 completes normally with final result

## Test Suite Results

### Final Test Counts
- **Total**: 361 tests (was 356)
- **Passed**: 360 (was 355)
- **Skipped**: 1 (unchanged)
- **Failed**: 0
- **Errors**: 0

### New Tests Added (+5)
1. `VoiceProgressGeometryTest.testVoiceProgressDoesNotOverlapWithTextArea`
2. `VoiceProgressGeometryTest.testVoiceProgressShowHideDoesNotShiftQueryBounds`
3. `VoiceProgressGeometryTest.testLongQueryDoesNotOverlapSpinner`
4. `VoiceDictationCallbacksTest.finalResultWithoutEndOfSpeechHidesProgress`
5. `VoiceDictationCallbacksTest.oldSessionEndOfSpeechDoesNotHideNewSession`

### What Passed in Audit
✓ Real end-of-speech session-guard callback hides spinner while preserving late final
✓ Removed fullwidth row stops result list jump
✓ Both silence extras absent (EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS and EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS)
✓ 361 tests, 360 pass, 1 skip

## Build Artifacts

### APK Files
- **Release APK**: `app/build/outputs/apk/release/app-release.apk`
  - Size: 2.5M
  - SHA256: `4a2fd62263b57d4b4b8a82fbb965808d880948e667d1b240ab315bb13a0ff9e2`

- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
  - Size: 6.4M
  - SHA256: `531e6d1970c314b1acfa7169507c6cb84dd1f7795fbc75d8243c7b21058b5585`

### Test Results
- **Full JUnit XML**: `test-results-v1.9.7-04b753b.zip`
  - Size: 37K
  - Contains 34 XML test result files
  - All test suites included

## Checklist - All Blockers Addressed

- [x] **BLOCKER A**: Text/spinner geometry overlap fixed (28dp padding reserves space)
- [x] **BLOCKER A Tests**: Added 3 geometry tests that FAIL old code, PASS after fix
- [x] **BLOCKER B**: Stuck circle fixed (unconditional terminal cleanup)
- [x] **BLOCKER B Test**: Added test for onResults WITHOUT onEndOfSpeech
- [x] **MISSING Test**: Added stale session protection test via actual RecognitionListener
- [x] Version remains 1.9.7/code41
- [x] Full test suite passes on final tree (361/360/1)
- [x] Clean release + debug builds completed
- [x] Real full JUnit XML ZIP exported and verified
- [x] Exact HEAD commit SHA recorded
- [x] APK sizes and SHA256 hashes recorded
- [x] All changes are targeted minimal fixes only
- [x] Preserved hold0/intentional zeros/late IME/suggestion acceptance/privacy
- [x] No deleted or narrowed test coverage
- [x] No phone/ADB/install/merge/release/historyrewrite/forcepush
- [x] No PR37 edits or deferred AI/websearch features

## Summary

All three audit blockers have been fixed with targeted minimal changes:
1. Geometry overlap resolved by proper EditText padding (28dp)
2. Stuck circle resolved by unconditional terminal result cleanup
3. Missing stale session test added with actual callback paths

Test suite expanded from 356 to 361 tests, with all new tests passing and demonstrating that the fixes work correctly. Both release and debug APKs built successfully on final tree.
