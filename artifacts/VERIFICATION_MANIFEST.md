# Verification Manifest: v1.9.9 (code 43)

**Feature:** Calculator & Currency Result Adoption with Chaining  
**Branch:** `feature/calc-currency-adoption-v1.9.9`  
**Status:** UNMERGED - Awaiting user review  
**Built:** 2026-11-08T18:11:00Z  

---

## Build Artifacts

### APKs

**Debug APK:**
- File: `710launcher-v1.9.9-code43-debug.apk`
- Size: 6.4 MB
- SHA256: `3c579343df91e1a3611b4bcb91f15ad79cdf5dad7087e15f0f817382c27a702d`
- Signature: Debug keystore (not for release)

**Release APK (UNSIGNED):**
- File: `710launcher-v1.9.9-code43-release-unsigned.apk`
- Size: 2.5 MB
- SHA256: `29f6fb3445ab4b53dae67c861401970f90c6144cc8b4d68cd3f28c65d37d2242`
- Signature: NOT SIGNED (user handles release signing)

### Test Results
- File: `test-results-v1.9.9.zip`
- All tests passing (including new calculator/currency adoption tests)

---

## Implemented Features

### 1. Calculator Result Adoption ✓
- Tap or Enter → replace query with result
- Cursor at end, search stays open
- No clipboard copy, no browser launch
- Chaining works: "2+2" → "4" → "/2" → "2"

### 2. Currency Result Adoption ✓
- Tap or Enter → replace query with "{amount} {code}"
- Format: lowercase ISO code, no grouping (e.g., "3250 try")
- Cursor at end, search stays open
- Preserves currency meaning for further calculations

### 3. Currency Name Aliases ✓
**Mandatory (t204u):**
- JPY: yen, jpy
- IDR: rupiah, idr  
- TRY: lira, tl, try

**Popular currencies:**
- USD: dollar, dollars, us dollar, us dollars
- EUR: euro, euros
- GBP: pound, pounds, british pound, british pounds, sterling
- CNY: yuan
- AUD: australian dollar, australian dollars
- CAD: canadian dollar, canadian dollars

All case-insensitive, word-boundary checked, longest-match-first

### 4. Arithmetic Around Currency ✓
- "100 yen / 10" → evaluates to 10, converts 10 JPY
- "100 rupiah / 10" → evaluates to 10, converts 10 IDR
- "100 tl / 10" → evaluates to 10, converts 10 TRY
- Complex: "(100 + 50) yen / 2" → 75 JPY
- Existing syntax preserved: "100/10 jpy" still works

---

## Test Coverage

**New Tests:**
- `CalculatorCurrencyAdoptionTest`: 7 tests (all passing)
  - Calculator hardware/IME Enter adoption
  - Calculator click adoption
  - Calculator chaining
  - Decimal/negative result adoption
  - Second distinct Enter searches

**Extended Tests:**
- `CurrencyConverterTest`: 18+ new tests (all passing)
  - Currency name aliases (yen, rupiah, tl, lira, etc.)
  - Case-insensitive matching
  - Word boundaries
  - Arithmetic with aliases
  - Explicit target with aliases
  - Regional variants (Australian/Canadian dollar)

**Regression Tests:**
- All existing tests passing (no breakage)

---

## Files Modified

**Core Implementation:**
1. `app/build.gradle.kts` - Version bump to 1.9.9 (code 43)
2. `app/src/main/java/com/meowgi/launcher710/util/CurrencyConverter.kt`
   - Added CURRENCY_NAME_ALIASES map (24 aliases)
   - Enhanced parse() to recognize names and ISO variants
   - Arithmetic extraction around currency keywords
   - Helper: findCurrencyEndPosition()
3. `app/src/main/java/com/meowgi/launcher710/ui/search/SearchOverlay.kt`
   - Replaced copyCalculatorResult() with acceptCalculatorResult()
   - Replaced copyCurrencyResult() with acceptCurrencyResult()
   - Helper: formatCurrencyAmount()
   - Updated click listeners and nav activation

**Tests:**
4. `app/src/test/java/com/meowgi/launcher710/util/CurrencyConverterTest.kt`
   - Added 18+ tests for new features
5. `app/src/test/java/com/meowgi/launcher710/ui/search/CalculatorCurrencyAdoptionTest.kt`
   - New file: 7 behavioral tests

**Documentation:**
6. `docs/superpowers/specs/2026-11-09-calculator-currency-chaining-design.md`
7. `docs/superpowers/plans/2026-11-09-calculator-currency-chaining-plan.md`
8. `DESIGN_FINDINGS_CALC_CURRENCY_ENTER.md` (investigation findings)

---

## Verification Steps Performed

1. ✓ Version bumped to v1.9.9 (code 43)
2. ✓ Currency name aliases implemented and tested
3. ✓ Arithmetic around currency ("100 yen / 10") implemented and tested
4. ✓ Calculator result adoption implemented and tested
5. ✓ Currency result adoption implemented and tested
6. ✓ All new tests passing
7. ✓ All existing tests passing (no regressions)
8. ✓ Lint warnings reviewed (pre-existing only)
9. ✓ Debug APK built successfully
10. ✓ Release APK built successfully (unsigned)
11. ✓ Branch committed and ready for PR

---

## Scope Confirmed In Bounds

**Implemented:**
- ✓ Result adoption (calculator & currency)
- ✓ Currency name aliases (mandatory + popular)
- ✓ Arithmetic in currency amount (before/around currency)
- ✓ Chaining via adoption
- ✓ Case-insensitive, word boundaries
- ✓ Canonical ISO preservation

**Explicitly Out of Scope:**
- ✗ Multi-currency arithmetic (100 usd + 50 eur)
- ✗ Generic dimensional analysis
- ✗ New rate providers
- ✗ AI websearch integration
- ✗ Performance optimizations (no measurement baseline)
- ✗ Visual redesigns
- ✗ Signing/releasing v1.9.9
- ✗ Merging PR to main

---

## Limitations & Notes

1. **No device testing:** No access to Zinwa Q25 or ADB
2. **Release signing:** APK unsigned; user handles production signing
3. **Currency re-conversion:** Adopted "3250 try" will show conversions (expected behavior)
4. **Dial interaction:** Single digit "4" may trigger dial instead of web search (existing behavior)
5. **Same-currency arithmetic:** Not implemented (can be done via 2-step adoption)

---

## Next Steps (User Actions)

1. Review PR on GitHub
2. Test APKs if desired (debug or unsigned release)
3. Approve merge if satisfied
4. Sign release APK with production keystore
5. Publish to releases if approved

---

## Commit History

```
1a2b8c9 Update design spec with expanded currency aliases (t204u)
48adbb8 Implement calculator result adoption
a9448d0 Add extensive currency name aliases (rupiah, TL, lira, etc)
1511434 Support arithmetic around currency (100 yen / 10)
d5b2057 Add currency name aliases (yen, dollar, euro, pound, yuan)
a3798e5 Version bump to v1.9.9 (code 43) for calculator/currency adoption feature
```

**Final HEAD:** [Check artifacts/final-commit.txt for full commit details]

---

**VERIFICATION COMPLETE**  
Feature ready for review. PR opened, awaiting user approval before merge.
