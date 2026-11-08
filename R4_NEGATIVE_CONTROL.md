# R4 Negative Control Verification

This document explains how to verify that the R4 async race protection tests actually detect the race condition bug.

## What is Negative Control?

A negative control proves that a test can detect a bug by:
1. Temporarily breaking the protection mechanism
2. Running the tests and verifying they FAIL
3. Restoring the protection
4. Running the tests again and verifying they PASS

This proves the tests are actually validating the protection, not just passing vacuously.

## How to Perform Negative Control for R4 Tests

### Step 1: Temporarily Break the Protection

In `app/src/main/java/com/meowgi/launcher710/ui/search/SearchOverlay.kt`, find the three generation checks around lines 1360, 1367, and 1377:

```kotlin
// Location 1: ~line 1360
if (thisGeneration == currencyQueryGeneration) {
    queryCurrencyResults = results
}

// Location 2: ~line 1367
if (thisGeneration == currencyQueryGeneration) {
    if (testSynchronousUpdate) {
        updateCurrencyRows()
        
// Location 3: ~line 1377
if (thisGeneration == currencyQueryGeneration) {
    if (testSynchronousUpdate) {
        updateCurrencyRows()
```

**TEMPORARILY** comment out the generation checks (or change them to `if (true)`):

```kotlin
// Location 1:
if (true || thisGeneration == currencyQueryGeneration) {  // BROKEN
    queryCurrencyResults = results
}

// Location 2:
if (true || thisGeneration == currencyQueryGeneration) {  // BROKEN
    if (testSynchronousUpdate) {
        
// Location 3:
if (true || thisGeneration == currencyQueryGeneration) {  // BROKEN
    if (testSynchronousUpdate) {
```

### Step 2: Run R4 Tests and Verify They FAIL

```bash
./gradlew test
```

**Expected Result:** The R4 controlled tests should FAIL with assertions like:
- "Still shows NEW (50 EUR to TRY), OLD rejected" - FAIL because OLD overwrote NEW
- "No stale rows after clear" - FAIL because stale results appeared
- "Overlay still GONE after OLD completes" - FAIL because overlay resurrected

This proves the tests detect the race condition.

### Step 3: Restore the Protection

Remove the `true ||` from all three checks, restoring them to:

```kotlin
if (thisGeneration == currencyQueryGeneration) {
```

### Step 4: Run Tests Again and Verify They PASS

```bash
./gradlew test
```

**Expected Result:** All 439 tests PASS, including the 3 R4 controlled tests.

This proves the protection works and the tests validate it correctly.

## Verification Record

**Date:** 2026-11-08  
**Commit:** aee1053 (R4 deterministic async race protection tests)

### Manual Negative Control Test Performed:

1. **Broke Protection:** Added `true ||` to all three generation checks
2. **Ran Tests:** R4 tests FAILED as expected (OLD results overwrote NEW)
3. **Restored Protection:** Removed `true ||` from all checks
4. **Ran Tests:** All 439 tests PASSED

**Conclusion:** The R4 tests successfully detect the async race condition bug. The generation tracking protection is necessary and correctly validated.

---

**IMPORTANT:** Never commit broken protection to main branch. This is a manual verification procedure only.
