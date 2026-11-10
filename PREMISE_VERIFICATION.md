# Premise Verification: Same-Currency Adoption Context

## Premise to Test

**Stated Premise (from reviewer overclaim):**
> "A currency-match guard plus a different-currency query after dismissal proves old adoption context cannot affect a fresh same-currency query."

## Contradiction Identified

**Reviewer Test (lines 199-228):**
- Adopt TRY (from "100 usd to try")
- Dismiss overlay
- Fresh query: "50 eur" (EUR → TRY/USD)

**Reviewer Conclusion (line 392):**
> "Dismiss → fresh same-currency query uses default order (not influenced by old adoption)"

**Problem:** The test queries EUR (different currency), NOT TRY (same currency). This does NOT prove that a fresh "50 try" query works correctly after adopting TRY.

## Required Counterfactual Tests

### Test 1: Baseline - Fresh Untouched Overlay
Query: "50 try"
Expected: Default currency order for TRY conversions (EUR, USD based on displayCurrencies)

### Test 2: Adopt Same Currency, Clear Field, Query Again
1. Query: "100 usd to try" → adopt TRY result
2. Clear field completely
3. Query: "50 try"
Expected: SAME results/order as Test 1 (baseline)

### Test 3: Adopt Same Currency, Dismiss, Reopen, Query
1. Query: "100 usd to try" → adopt TRY result
2. Dismiss overlay (onBackPressed)
3. Reopen overlay
4. Query: "50 try"  
Expected: SAME results/order as Test 1 (baseline)

## Test Configuration
- Currencies: TRY, EUR, USD (same order)
- Rates: Fixed via FakeCurrencyRatesCache
- Compare: Result order, result values, first-click adoption behavior

## Hypothesis
If adoptedCurrencyCode is NOT cleared on dismiss/clear, and the currency-match guard is insufficient, then:
- Test 2 may show TRY→TRY (identity) first instead of TRY→EUR first
- Test 3 may show same issue
- Fresh "50 try" would behave differently than untouched overlay

## Implementation Test Results

### Initial Test Run (Before Fix)
- Test 1 (Baseline): ✅ PASS - Fresh "50 try" shows EUR first, USD second
- Test 2 (Clear field): ❌ FAIL - After clear, "50 try" shows TRY first (identity conversion)
- Test 3 (Dismiss/reopen): ❌ FAIL - After dismiss/reopen, "50 try" shows TRY first

**Conclusion:** Premise FALSE - adoptedCurrencyCode persists incorrectly

### Root Cause
`adoptedCurrencyCode` was never cleared:
- Set in `acceptCurrencyResult()` when adopting
- Never cleared in `resetSession()` (dismiss/reopen)
- Never cleared when typing fresh query after clear

### Minimal Fix Applied
1. Clear `adoptedCurrencyCode` in `resetSession()` - fixes dismiss/reopen case
2. Track `adoptedFromQuery` to remember the adopted result text
3. In `evaluateCurrency()`, clear adoption if query doesn't start with adopted result - fixes clear field case
4. Preserves chaining: "3250 try / 10" still works (starts with "3250 try")

### Final Test Run (After Fix)
- Test 1 (Baseline): ✅ PASS
- Test 2 (Clear field): ✅ PASS  
- Test 3 (Dismiss/reopen): ✅ PASS
- Existing chaining test: ✅ PASS
- Existing "preserves unit" test: ✅ PASS

## Production Code Changes
- `SearchOverlay.kt`: 3 minimal changes
  1. Add `adoptedFromQuery` field to track adopted result
  2. Clear both in `resetSession()`
  3. Clear both in `evaluateCurrency()` if not a continuation
