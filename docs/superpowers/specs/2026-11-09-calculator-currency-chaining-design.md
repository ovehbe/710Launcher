# Calculator & Currency Result Adoption with Chaining

**Approved:** 2026-11-09  
**Target Version:** v1.9.9 (code 43)  
**Baseline:** v1.9.8 (code 42), commit b355bd2d6a99a47575e3b7b051dc2b1b2af8f01b

---

## User Request

> When I click enter when calculator is showing or when I click it, it should input the equals to the search field replacing what was there in the first place. Same with currency, put the result in the text field. Also it would be great if I can mix and match like 100 yen / 10 etc you got me right?

---

## Approved Design

### 1. Result Adoption (Replace Clipboard Copy)

**Current behavior:** Calculator/currency tap or Enter → copy result to clipboard, show toast, search stays open but query unchanged.

**New behavior:** Calculator/currency tap or Enter → replace entire query with result, cursor at end, search stays open, focus retained, no clipboard mutation, no browser/app launch.

#### Calculator Adoption
- Input: `"2+2"` → Shows: `"2+2 = 4"`
- Tap or Enter → Query becomes: `"4"` (cursor at end)
- Ready for further calculation: user types `"/2"` → shows `"4/2 = 2"`

**Result format:** Raw numeric result, reusable in expressions
- Whole numbers: `"4"` not `"4.0"`
- Decimals: `"0.33333333"` (trimmed trailing zeros)
- Negatives: `"-5"`
- No grouping separators

#### Currency Adoption
- Input: `"100 usd to try"` → Shows: `"100 USD = 3,250 TRY"`
- Tap or Enter → Query becomes: `"3250 try"` (cursor at end)
- Ready for further calculation: user types `" / 10"` → shows `"325 TRY = ..."`

**Result format:** Converted numeric amount + target ISO currency code
- Format: `"{amount} {code}"` (lowercase code: `"try"`, `"jpy"`, `"usd"`)
- Amount: ungrouped canonical (no thousands separators, no approximation marks)
- Whole numbers: `"3250 try"` not `"3250.0 try"`
- Decimals: `"8.93 eur"` (trimmed trailing zeros)
- Must be parseable by existing currency parser for chaining

**Why include currency code:**
- Preserves semantic meaning for further calculations
- `"3250 try / 10"` correctly converts 325 TRY, not 3250 USD / 10
- Without code, user loses currency context

**Guard against re-conversion loop:**
- Accepting `"3250 try"` must not immediately show a new conversion row
- Existing parser behavior: single currency without target → shows conversions to display currencies
- Solution: Parser already requires amount before currency; `"3250 try"` is valid, triggers conversion to defaults (expected behavior)
- User can type more operators to trigger calculator, or different currency to convert

### 2. Chaining Support - User's Exact Syntax

**User's example:** `"100 yen / 10"`

**Approved semantics:** Evaluate scalar arithmetic in source amount BEFORE conversion.

```
"100 yen / 10" →
  1. Recognize "yen" as currency name (map to JPY)
  2. Extract arithmetic: "100 / 10" (before "yen")
  3. Evaluate: 100 / 10 = 10
  4. Convert: 10 JPY → (default target or explicit target)
  5. Display: "10 JPY = 0.07 USD" (example rate)
  6. Enter → field: "0.07 usd"
```

**Alternative orderings (keep existing support):**
- `"100/10 jpy"` → arithmetic before currency (already works)
- `"100 jpy to usd"` → explicit target (already works)
- `"100 yen / 10 to usd"` → arithmetic + explicit target (new)

**Operator support:** `+`, `-`, `*`, `/`, `%`, `(`, `)` using existing `Calculator.evaluate()`

**Same-currency arithmetic:**
- `"100 usd / 10 usd"` → extract amounts, evaluate `100 / 10 = 10`, use first currency: `10 USD`
- `"100 jpy + 50 jpy"` → evaluate `100 + 50 = 150`, result: `150 JPY`
- Strip currency codes from operands, perform arithmetic, keep first currency

**Multi-currency NOT supported:**
- `"100 usd + 50 eur"` → INVALID (reject, don't silently convert)
- `"100 jpy / 10 usd"` → INVALID (ambiguous, reject)

### 3. Currency Name Aliases

**Mandatory equivalence groups (t204u):**
- JPY: `yen`, `jpy` (case-insensitive)
- IDR: `rupiah`, `idr` (case-insensitive)
- TRY: `lira`, `tl`, `try` (Turkish lira, case-insensitive)

**Popular currencies with explicit names:**
- USD: `dollar`, `dollars`, `us dollar`, `us dollars`
- EUR: `euro`, `euros`
- GBP: `pound`, `pounds`, `british pound`, `british pounds`, `sterling`
- CNY: `yuan`
- AUD: `australian dollar`, `australian dollars`
- CAD: `canadian dollar`, `canadian dollars`

**Case-insensitive:** All aliases work regardless of case (`YEN`, `Yen`, `yen`)

**Longest-match-first:** `"australian dollar"` matches AUD before `"dollar"` matches USD

**Ambiguous names NOT added:**
- Generic `"peso"` → multiple countries (MXN, PHP, ARS, etc.)
- Generic `"franc"` → multiple (CHF, XAF, etc.)
- Generic `"rupee"` → multiple (INR, PKR, LKR, etc.)

**Note:** `"lira"` is unambiguous in practice (Turkish lira is most common in financial contexts)

**Existing 3-letter ISO codes remain primary.**

### 4. Enter Key Behavior

**Requirements:**
1. One hardware Enter press → accept result exactly once
2. Key-up must not launch browser/app
3. Next distinct Enter → NOT swallowed globally (must search as before)
4. If calculation showing with no other selection → Enter adopts calculation
5. If app/suggestion explicitly navigated → Enter opens that (existing behavior)
6. Google suggestions: fill on first Enter, search on next distinct Enter (preserve existing)

**Implementation:**
- Calculator/currency tap → call result adoption (same as Enter)
- Hardware Enter down+up → `activateNavSelection()` → adoption
- IME Enter action → same path
- Adoption does not consume "next" Enter; that's handled by existing distinct-press detection
- If no highlight and calculator/currency visible → highlight calculator/currency automatically before adoption? NO - require explicit navigation or implement smart default selection.

**Decision:** If calculator showing with no highlight, Enter should adopt calculator result (not search). Same for currency when single row. When multiple currency rows, require highlight.

### 5. Parser Changes

**CurrencyConverter.parse()** modifications:

#### A. Currency Name Recognition
Before searching for 3-letter ISO codes, search for known currency names:
- `"100 yen / 10"` → find `"yen"` at position X
- Map `"yen"` → `"JPY"`
- Treat as if user typed `"100 jpy / 10"`

#### B. Arithmetic Before Currency
Current: `amountEnd = first currency position`, `amountStr = query[0:amountEnd]`

New: Extract ALL characters before first currency (including operators after numbers)
- `"100 / 10 yen"` → `amountStr = "100 / 10"`, `currency = "YEN" → "JPY"`
- `"100 yen / 10"` → reorder: extract amount `"100"`, then operator `"/ 10"`, then currency `"yen"`

**Wait, this doesn't match the approved behavior.** User wants `"100 yen / 10"` to work, which means:
- Amount is `"100"`, NOT `"100 / 10"`
- The `"/ 10"` comes AFTER the currency

**Re-reading approved design:**
> Support exact 100 yen / 10 syntax: divide first, then convert using existing target currency.

This is ambiguous. Let me re-interpret:
- "divide first" = evaluate the division expression in the amount
- But the syntax is `"100 yen / 10"`, where `/10` is after the currency

Two interpretations:
1. `"100 yen / 10"` means amount=`"100 / 10"` (arithmetic scattered around currency keyword)
2. `"100 yen / 10"` means amount=`"100"`, then post-conversion divide by 10

Looking at the approved design again:
> Evaluate scalar arithmetic in source amount before conversion with existing target/default conversion behavior.

"Source amount" suggests the arithmetic is in the amount. And it says "before conversion", which means evaluate arithmetic, then convert.

And the example:
```
"100 yen / 10" →
  1. Recognize "yen" as currency name (map to JPY)
  2. Extract arithmetic: "100 / 10" (before "yen")
```

Wait, this says "100 / 10" (before "yen"), but the input is "100 yen / 10" where the "/ 10" is AFTER "yen".

I think there's a transcription issue in my documentation. Let me re-read the user's approval more carefully:

> Support exact 100 yen / 10 syntax: divide first, then convert using existing target currency.

"divide first" = evaluate division, "then convert" = after evaluating, convert the result.

So `"100 yen / 10"` should be parsed as:
- Find "yen" → currency
- Extract amount with arithmetic: "100" and "/ 10" need to be combined
- Evaluate: 100 / 10 = 10
- Convert: 10 JPY

The challenge is that the arithmetic is split: "100" before currency, "/ 10" after currency.

**Parser strategy:**
1. Find currency name/code in query
2. Extract everything before currency as `leftAmount`
3. Extract everything after currency (before next currency or keyword) as `rightArithmetic`
4. Combine: `amount = leftAmount + rightArithmetic`
5. Evaluate combined amount
6. Convert

Example: `"100 yen / 10"`
- Currency: `"yen"` at position 4-6
- Left: `"100 "` (trim to `"100"`)
- Right: `" / 10"` (trim to `"/ 10"`)
- Combined amount: `"100 / 10"` or `"100/ 10"` or `"100 / 10"`
- Evaluate: 10
- Convert: 10 JPY

This matches the approved behavior!

### 6. Guards and Edge Cases

**Stale async currency results:**
- Currency conversion runs in coroutine
- Guard: Check `lastQuery` matches when adopting result
- If query changed since conversion started, don't adopt

**Invalid expressions:**
- Calculator returns `null` on parse/eval error
- Don't show adoption option if no valid result

**Divide by zero:**
- Calculator returns `null` on division by zero
- Don't show adoption option

**Missing/error rates:**
- Currency parser returns `null` if rates unavailable
- Don't show currency rows

**Loading state:**
- Don't allow adoption while currency conversion in progress

### 7. Scope Limits

**In scope:**
- Result adoption (calculator & currency)
- Currency name aliases (small well-defined set)
- Arithmetic in currency amount (same-currency operations)
- Chaining via adoption (2-step: adopt result, type more, evaluate again)

**Out of scope:**
- Multi-currency arithmetic operations
- Dimensional analysis / unit conversions beyond currency
- New exchange rate providers
- Real-time rate fetching on every query
- Performance optimizations without measurement
- Visual styling changes
- AI websearch integration (explicitly deferred)
- Optional features requiring user settings

### 8. Verification Acceptance Criteria

**Calculator:**
- [ ] Tap calculator row → query replaced with result
- [ ] Hardware Enter with calculator highlighted → query replaced
- [ ] IME Enter with calculator highlighted → query replaced
- [ ] Result has cursor at end
- [ ] Search stays open
- [ ] No clipboard mutation
- [ ] No browser launch
- [ ] Can append arithmetic and recalculate

**Currency:**
- [ ] Tap currency row → query replaced with amount + code
- [ ] Hardware Enter with currency highlighted → query replaced
- [ ] IME Enter with currency highlighted → query replaced
- [ ] Result format: `"{amount} {code}"` (lowercase, no grouping)
- [ ] Search stays open
- [ ] Can append arithmetic and recalculate
- [ ] Adopted result doesn't immediately reconvert

**Chaining:**
- [ ] `"100 yen / 10"` → evaluates to 10, converts 10 JPY
- [ ] `"100 YEN / 10"` (case-insensitive) → same result
- [ ] `"100 / 10 jpy"` → still works (existing syntax)
- [ ] `"100 yen / 10 to usd"` → explicit target works
- [ ] `"100 usd / 10 usd"` → evaluates to 10 USD (same-currency)
- [ ] `"100 usd + 50 eur"` → INVALID (multi-currency rejected)
- [ ] Parentheses: `"(100 + 50) yen / 2"` → 75 JPY

**Enter behavior:**
- [ ] First Enter with highlight → adopts result
- [ ] Second distinct Enter → searches (launches browser)
- [ ] No Enter swallowed/dropped
- [ ] Suggestion highlight preserved (fill on first, search on second)
- [ ] App selection preserved (opens app, not calculation)

**Edge cases:**
- [ ] Stale currency result → not adopted
- [ ] Invalid expression → no adoption
- [ ] Divide by zero → no adoption
- [ ] Missing rates → no currency rows
- [ ] Non-finite results → handled gracefully

---

## Implementation Notes

### Files to Modify

**SearchOverlay.kt:**
- `copyCalculatorResult()` → `acceptCalculatorResult()`
- `copyCurrencyResult(index)` → `acceptCurrencyResult(index)`
- Update `activateNavSelection()` dispatch
- Update row click listeners
- Format currency result: `formatCurrencyForAdoption(result)`

**CurrencyConverter.kt:**
- Add `CURRENCY_NAME_ALIASES` map
- Modify `parse()` to recognize currency names
- Modify `parse()` to extract arithmetic around currency
- Add `parseSameCurrencyArithmetic()` for same-currency operations
- Validate multi-currency expressions (reject ambiguous)

**Tests:**
- `SearchOverlay` behavior tests (tap/Enter adoption)
- `CurrencyConverter` parser tests (name aliases, arithmetic positions)
- End-to-end chaining tests
- Edge case tests (stale/invalid/zero)

### Version Bump

- `versionName`: `1.9.9`
- `versionCode`: `43`
- Location: `app/build.gradle.kts`

---

## Self-Review: Scope & Ambiguities

### Clarified Ambiguities

1. **"100 yen / 10" parsing:**
   - Parse as: left="100", currency="yen", right="/ 10"
   - Combine: amount="100 / 10"
   - Evaluate: 10
   - Convert: 10 JPY
   - ✓ Approved

2. **Currency result format:**
   - Include target currency code: `"3250 try"`
   - ✓ Approved (preserves meaning for further calculations)

3. **Re-conversion after adoption:**
   - `"3250 try"` will trigger conversion to display currencies (existing behavior)
   - User can type more to trigger calculator or different currency
   - ✓ Expected behavior

4. **Same-currency arithmetic:**
   - `"100 usd / 10 usd"` → `10 usd`
   - Strip codes from operands, evaluate, keep first currency
   - ✓ Approved

5. **Multi-currency arithmetic:**
   - `"100 usd + 50 eur"` → INVALID
   - Reject explicitly, don't guess conversion
   - ✓ Approved

### Remaining Ambiguities

1. **Smart default selection:**
   - If calculator showing with no highlight, should Enter auto-adopt?
   - Current: Enter searches if no highlight
   - Proposed: Auto-highlight calculator if visible and no other selection
   - **Decision needed:** Add smart default or require explicit navigation?
   - **Implementation choice:** Require explicit navigation (minimal change)

2. **Multiple currency rows with no highlight:**
   - If "100 usd" shows 3 conversions, Enter with no highlight → adopt first?
   - Current: Enter searches
   - **Implementation choice:** Require highlight (consistent with current nav system)

3. **Currency code case in adopted result:**
   - Lowercase: `"3250 try"`
   - Uppercase: `"3250 TRY"`
   - Mixed: `"3250 Try"`
   - **Implementation choice:** Lowercase (matches user's typical typing, easier to read)

### Scope Boundaries Confirmed

**Definitely in scope:**
- ✓ Result adoption replacing clipboard copy
- ✓ Currency name aliases (yen, dollar, euro, pound, yuan)
- ✓ Arithmetic in currency amount (before/around currency keyword)
- ✓ Same-currency arithmetic

**Definitely out of scope:**
- ✗ Multi-currency arithmetic (100 usd + 50 eur)
- ✗ Generic dimensional analysis
- ✗ New rate providers
- ✗ Performance claims without measurement
- ✗ Visual redesigns
- ✗ AI websearch

**Edge cases confirmed in scope:**
- ✓ Stale result guards
- ✓ Invalid expression handling
- ✓ Divide by zero
- ✓ Missing rates
- ✓ Key repeat handling

---

## Risks & Mitigations

**Risk:** Adopted currency result immediately reconverts, creating confusion.
**Mitigation:** Format result to be parseable but distinct. User typing more operators triggers calculator first.

**Risk:** Arithmetic parsing becomes too complex, breaks existing queries.
**Mitigation:** Keep existing parser paths, add new branch for currency names. Test extensively.

**Risk:** Multi-currency expressions produce wrong results instead of rejecting.
**Mitigation:** Explicit validation, reject ambiguous cases with clear error (or just hide row).

**Risk:** Enter key behavior changes break existing muscle memory.
**Mitigation:** Only change behavior when calculator/currency highlighted. No highlight → existing search behavior.

**Risk:** Same-currency arithmetic semantics unclear to users.
**Mitigation:** Test obvious cases (100 usd / 10 usd → 10 usd). Document behavior.

---

## Success Criteria

1. All verification acceptance criteria pass
2. Existing tests pass (no regressions)
3. New tests added for all new behavior
4. Clean build with no new lint warnings
5. Manual verification on-device (if available)
6. PR created (unmerged)
7. APKs exported with verification manifest

---

## Out of Scope (Explicit)

- Signing/releasing v1.9.9
- Merging PR to main
- Device/ADB testing (no Q25 access)
- Visual proof via screenshots/recordings
- History rewrite/force push
- Optional AI websearch
- Performance optimization
- Broad refactors
- New dependencies
- Generic units framework
