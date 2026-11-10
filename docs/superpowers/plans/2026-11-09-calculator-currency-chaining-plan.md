# Implementation Plan: Calculator & Currency Result Adoption with Chaining

**Version:** v1.9.9 (code 43)  
**Spec:** `docs/superpowers/specs/2026-11-09-calculator-currency-chaining-design.md`  
**Started:** 2026-11-09

---

## Implementation Units

### Unit 1: Version Bump
**Goal:** Update version to v1.9.9 (code 43)

**Files:**
- `app/build.gradle.kts`

**Changes:**
```kotlin
versionCode = 43
versionName = "1.9.9"
```

**Verification:** Build succeeds with new version

---

### Unit 2: Currency Name Aliases
**Goal:** Recognize currency names like "yen" and map to ISO codes

**Files:**
- `app/src/main/java/com/meowgi/launcher710/util/CurrencyConverter.kt`

**Changes:**
1. Add constant map at top of object:
```kotlin
private val CURRENCY_NAME_ALIASES = mapOf(
    "yen" to "JPY",
    "dollar" to "USD", "dollars" to "USD",
    "euro" to "EUR", "euros" to "EUR",
    "pound" to "GBP", "pounds" to "GBP",
    "yuan" to "CNY"
)
```

2. Modify `parse()` to check names before ISO codes:
```kotlin
// Search for currency names (longer matches first)
val nameMatches = mutableListOf<Pair<Int, String>>()
for ((name, code) in CURRENCY_NAME_ALIASES.entries.sortedByDescending { it.key.length }) {
    var i = 0
    while (i <= normalized.length - name.length) {
        if (normalized.substring(i, i + name.length) == name) {
            // Check word boundaries
            val beforeOk = i == 0 || !normalized[i - 1].isLetter()
            val afterOk = i + name.length >= normalized.length || !normalized[i + name.length].isLetter()
            if (beforeOk && afterOk) {
                nameMatches.add(i to code)
                i += name.length
                continue
            }
        }
        i++
    }
}

// Merge with existing 3-letter ISO code matches
val allCurrencyMatches = (currencyMatches + nameMatches).sortedBy { it.first }
```

**Tests:**
```kotlin
@Test
fun `parse recognizes yen currency name`() {
    val query = CurrencyConverter.parse("100 yen", LAYOUT_QWERTY, sampleRates.keys)
    assertNotNull(query)
    assertEquals(100.0, query!!.amount, 0.001)
    assertEquals("JPY", query.fromCurrency)
}

@Test
fun `parse recognizes yen case insensitive`() {
    val query = CurrencyConverter.parse("100 YEN", LAYOUT_QWERTY, sampleRates.keys)
    assertEquals("JPY", query!!.fromCurrency)
}

@Test
fun `parse recognizes dollar and euro`() {
    val query1 = CurrencyConverter.parse("100 dollars", LAYOUT_QWERTY, sampleRates.keys)
    assertEquals("USD", query1!!.fromCurrency)
    
    val query2 = CurrencyConverter.parse("50 euros to usd", LAYOUT_QWERTY, sampleRates.keys)
    assertEquals("EUR", query2!!.fromCurrency)
    assertEquals("USD", query2.toCurrency)
}
```

**Verification:** New tests pass, existing tests pass

---

### Unit 3: Arithmetic Around Currency
**Goal:** Parse "100 yen / 10" by extracting arithmetic around currency

**Files:**
- `app/src/main/java/com/meowgi/launcher710/util/CurrencyConverter.kt`

**Changes:**
Modify `parse()` to handle arithmetic after currency:

```kotlin
// After determining fromCurrency and toCurrency...

// Extract amount: everything before first currency, PLUS arithmetic after it (before second currency/keyword)
val firstCurrencyPos = /* position of fromCurrency match */
val firstCurrencyEnd = /* end position of fromCurrency match */

// Left part: before currency
val leftPart = normalized.substring(0, firstCurrencyPos).trim()

// Right part: after currency, before next currency/keyword or end
var rightPart = ""
if (toCurrency == null) {
    // No second currency, take everything after first currency
    rightPart = normalized.substring(firstCurrencyEnd).trim()
    // Remove "to" or "in" keywords if present
    rightPart = rightPart.removePrefix("to").removePrefix("in").trim()
} else {
    // Second currency present, take only up to keyword or second currency
    val secondCurrencyPos = /* position of second currency */
    val keywordPos = /* position of "to"/"in" keyword if between currencies */
    val rightEnd = minOf(secondCurrencyPos, keywordPos ?: secondCurrencyPos)
    rightPart = normalized.substring(firstCurrencyEnd, rightEnd).trim()
}

// Filter rightPart to only arithmetic operators and numbers (no letters)
val arithmeticPart = rightPart.takeWhile { 
    it in '0'..'9' || it in "+-*/%()., " 
}

// Combine left + arithmetic
val amountStr = if (arithmeticPart.isNotEmpty()) {
    "$leftPart $arithmeticPart".trim()
} else {
    leftPart
}

// Now proceed with existing mapping and evaluation
val mappedAmount = mapAmountString(amountStr, layout) ?: return null
val amount = evaluateAmount(mappedAmount) ?: return null
```

**Tests:**
```kotlin
@Test
fun `parse 100 yen slash 10`() {
    val query = CurrencyConverter.parse("100 yen / 10", LAYOUT_QWERTY, sampleRates.keys)
    assertNotNull(query)
    assertEquals(10.0, query!!.amount, 0.001)
    assertEquals("JPY", query.fromCurrency)
    assertNull(query.toCurrency)
}

@Test
fun `parse 100 YEN slash 10 case insensitive`() {
    val query = CurrencyConverter.parse("100 YEN / 10", LAYOUT_QWERTY, sampleRates.keys)
    assertEquals(10.0, query!!.amount, 0.001)
    assertEquals("JPY", query.fromCurrency)
}

@Test
fun `parse 100 yen slash 10 to usd explicit target`() {
    val query = CurrencyConverter.parse("100 yen / 10 to usd", LAYOUT_QWERTY, sampleRates.keys)
    assertNotNull(query)
    assertEquals(10.0, query!!.amount, 0.001)
    assertEquals("JPY", query.fromCurrency)
    assertEquals("USD", query.toCurrency)
}

@Test
fun `parse complex arithmetic with currency name`() {
    val query = CurrencyConverter.parse("(100 + 50) yen / 2", LAYOUT_QWERTY, sampleRates.keys)
    assertNotNull(query)
    assertEquals(75.0, query!!.amount, 0.001)
    assertEquals("JPY", query.fromCurrency)
}

@Test
fun `existing syntax still works`() {
    // "100/10 jpy" should still work
    val query = CurrencyConverter.parse("100/10 jpy", LAYOUT_QWERTY, sampleRates.keys)
    assertNotNull(query)
    assertEquals(10.0, query!!.amount, 0.001)
}
```

**Verification:** New tests pass, existing tests pass

---

### Unit 4: Same-Currency Arithmetic
**Goal:** Handle "100 usd / 10 usd" → evaluate to "10 usd"

**Files:**
- `app/src/main/java/com/meowgi/launcher710/util/CurrencyConverter.kt`

**Changes:**
This is complex and may not be needed for MVP. Let's defer to "reject multi-currency" and only support it if time permits.

**For now:** Document that same-currency operations can be done by:
1. Accept result: "100 usd" → "100 usd"
2. Type more: "100 usd / 10" → calculator evaluates "100 / 10" if currency parsing fails
3. Or: Adoption gives plain number, type arithmetic, re-convert

**Decision:** DEFER same-currency arithmetic for now. Focus on core adoption + yen chaining.

---

### Unit 5: Calculator Result Adoption
**Goal:** Replace clipboard copy with query adoption

**Files:**
- `app/src/main/java/com/meowgi/launcher710/ui/search/SearchOverlay.kt`

**Changes:**
1. Rename `copyCalculatorResult()` → `acceptCalculatorResult()`:
```kotlin
private fun acceptCalculatorResult() {
    val result = queryCalculatorResult ?: return
    onUpdateSearchText?.invoke(result)
}
```

2. Update `activateNavSelection()`:
```kotlin
NavTarget.Calculator -> acceptCalculatorResult()
```

3. Update calculator row click listener:
```kotlin
calculatorRow.setOnClickListener { acceptCalculatorResult() }
```

**Tests:**
```kotlin
@Test
fun calculatorEnterAdoptsResult() {
    launch()
    typeQuery("2+2")
    settle()
    
    // Calculator shows "2+2 = 4"
    assertEquals("2+2", field.text.toString())
    
    // Highlight calculator
    activityKey(KeyEvent.KEYCODE_DPAD_DOWN)
    assertEquals("calculator", overlay.navSelection?.toString())
    
    // Press Enter
    activityKey(KeyEvent.KEYCODE_ENTER)
    settle()
    
    // Field should now be "4"
    assertEquals("4", field.text.toString())
    assertEquals(1, field.selectionStart) // cursor at end
    assertEquals(1, field.selectionEnd)
    
    // Search should stay open
    assertEquals(View.VISIBLE, overlay.visibility)
    
    // No browser launch
    assertNull(shadowOf(activity).nextStartedActivity)
}

@Test
fun calculatorClickAdoptsResult() {
    launch()
    typeQuery("2+2")
    settle()
    
    // Click calculator row
    overlay.findViewById<LinearLayout>(R.id.calculatorRow).performClick()
    settle()
    
    // Field should now be "4"
    assertEquals("4", field.text.toString())
}

@Test
fun calculatorAdoptionEnablesChaining() {
    launch()
    typeQuery("2+2")
    settle()
    
    // Adopt result
    highlightCalculator()
    activityKey(KeyEvent.KEYCODE_ENTER)
    settle()
    
    assertEquals("4", field.text.toString())
    
    // Type more
    typeQuery("/2")
    settle()
    
    // Should show "4/2 = 2"
    // Verify calculator row visible with new result
    // (This is indirect - calculator evaluation happens automatically via TextWatcher)
}
```

**Verification:** Tests pass

---

### Unit 6: Currency Result Adoption
**Goal:** Replace clipboard copy with query adoption, format as "{amount} {code}"

**Files:**
- `app/src/main/java/com/meowgi/launcher710/ui/search/SearchOverlay.kt`

**Changes:**
1. Add helper to format currency amount:
```kotlin
private fun formatCurrencyAmount(amount: Double): String {
    return if (amount == amount.toLong().toDouble()) {
        amount.toLong().toString()
    } else {
        "%.8f".format(amount).trimEnd('0').trimEnd('.')
    }
}
```

2. Rename `copyCurrencyResult(index)` → `acceptCurrencyResult(index)`:
```kotlin
private fun acceptCurrencyResult(index: Int) {
    val result = queryCurrencyResults.getOrNull(index) ?: return
    // Format: "{amount} {code}" (lowercase code)
    val amount = formatCurrencyAmount(result.toAmount)
    val code = result.toCurrency.lowercase()
    onUpdateSearchText?.invoke("$amount $code")
}
```

3. Update `activateNavSelection()`:
```kotlin
is NavTarget.Currency -> acceptCurrencyResult(target.index)
```

4. Update currency row click listeners (in `updateCurrencyRows()`):
```kotlin
row.setOnClickListener { acceptCurrencyResult(index) }
```

**Tests:**
```kotlin
@Test
fun currencyEnterAdoptsResult() {
    launch()
    typeQuery("100 usd to try")
    settle()
    
    // Currency shows conversion
    assertEquals("100 usd to try", field.text.toString())
    
    // Highlight first currency row
    navigateToCurrencyRow(0)
    
    // Press Enter
    activityKey(KeyEvent.KEYCODE_ENTER)
    settle()
    
    // Field should now be "{amount} try" (using fixture rate)
    val fieldText = field.text.toString()
    assertTrue(fieldText.endsWith(" try"))
    assertTrue(fieldText.matches(Regex("\\d+(\\.\\d+)? try")))
    
    // Cursor at end
    assertEquals(fieldText.length, field.selectionStart)
    
    // Search stays open
    assertEquals(View.VISIBLE, overlay.visibility)
    
    // No browser launch
    assertNull(shadowOf(activity).nextStartedActivity)
}

@Test
fun currencyClickAdoptsResult() {
    launch()
    typeQuery("100 usd to eur")
    settle()
    
    // Click first currency row
    val firstRow = overlay.currencyRowsContainer.getChildAt(0) as LinearLayout
    firstRow.performClick()
    settle()
    
    // Field should have result
    val fieldText = field.text.toString()
    assertTrue(fieldText.endsWith(" eur"))
}

@Test
fun currencyAdoptionEnablesChaining() {
    launch()
    typeQuery("100 usd to try")
    settle()
    
    // Adopt result
    navigateToCurrencyRow(0)
    activityKey(KeyEvent.KEYCODE_ENTER)
    settle()
    
    val adoptedText = field.text.toString()
    assertTrue(adoptedText.endsWith(" try"))
    
    // Type arithmetic
    typeQuery(" / 10")
    settle()
    
    // Should trigger currency conversion again
    // Verify via currency rows visible or calculator active
    // (Exact behavior depends on parser)
}

@Test
fun currencyAdoptionFormatsCorrectly() {
    // Whole number: "3250 try" not "3250.0 try"
    // Decimal: "8.93 eur" not "8.9300000 eur"
    // Test via fixture rates
}
```

**Verification:** Tests pass

---

### Unit 7: Stale Result Guards
**Goal:** Don't adopt currency result if query changed

**Files:**
- `app/src/main/java/com/meowgi/launcher710/ui/search/SearchOverlay.kt`

**Changes:**
Guard in `acceptCurrencyResult`:
```kotlin
private fun acceptCurrencyResult(index: Int) {
    val result = queryCurrencyResults.getOrNull(index) ?: return
    
    // Guard: verify result is for current query
    // (This is implicit - if query changed, currency rows would be hidden)
    // Additional explicit check:
    if (queryCurrencyResults.isEmpty()) return
    
    val amount = formatCurrencyAmount(result.toAmount)
    val code = result.toCurrency.lowercase()
    onUpdateSearchText?.invoke("$amount $code")
}
```

Actually, the existing behavior already handles this:
- `updateCurrencyRows()` is called when new results arrive
- If query changed, old results are cleared
- Rows are hidden if `queryCurrencyResults.isEmpty()`
- Can't click/Enter a hidden row

So stale result guard is already handled by existing architecture.

**Verification:** Add test that query change clears currency rows

---

### Unit 8: Edge Case Tests
**Goal:** Verify guards work

**Tests:**
```kotlin
@Test
fun calculatorInvalidExpressionNoAdoption() {
    launch()
    typeQuery("2++2")
    settle()
    
    // Calculator row should be hidden (invalid expression)
    assertEquals(View.GONE, overlay.calculatorRow.visibility)
}

@Test
fun calculatorDivideByZeroNoAdoption() {
    launch()
    typeQuery("100/0")
    settle()
    
    // Calculator row should be hidden
    assertEquals(View.GONE, overlay.calculatorRow.visibility)
}

@Test
fun currencyMissingRatesNoRows() {
    // Mock ratesCache to return empty
    // Verify currency rows hidden
}

@Test
fun multiCurrencyExpressionRejected() {
    launch()
    typeQuery("100 usd + 50 eur")
    settle()
    
    // Currency rows should be hidden (ambiguous expression)
    assertEquals(View.GONE, overlay.currencyRowsContainer.visibility)
}
```

**Verification:** Edge case tests pass

---

### Unit 9: Enter Behavior Preservation
**Goal:** Verify next Enter still searches

**Tests:**
```kotlin
@Test
fun calculatorFirstEnterAdoptsSecondEnterSearches() {
    launch()
    typeQuery("2+2")
    settle()
    
    // First Enter: adopt
    highlightCalculator()
    activityKey(KeyEvent.KEYCODE_ENTER)
    settle()
    
    assertEquals("4", field.text.toString())
    assertNull(shadowOf(activity).nextStartedActivity)
    
    // Second Enter: search
    activityKey(KeyEvent.KEYCODE_ENTER)
    settle()
    
    // Should have launched browser with query "4"
    val intent = shadowOf(activity).nextStartedActivity
    assertNotNull(intent)
    assertEquals(Intent.ACTION_WEB_SEARCH, intent.action)
    assertEquals("4", intent.getStringExtra("query"))
}

@Test
fun currencyFirstEnterAdoptsSecondEnterSearches() {
    launch()
    typeQuery("100 usd to try")
    settle()
    
    // First Enter: adopt
    navigateToCurrencyRow(0)
    activityKey(KeyEvent.KEYCODE_ENTER)
    settle()
    
    val adopted = field.text.toString()
    assertNull(shadowOf(activity).nextStartedActivity)
    
    // Second Enter: search
    activityKey(KeyEvent.KEYCODE_ENTER)
    settle()
    
    val intent = shadowOf(activity).nextStartedActivity
    assertNotNull(intent)
    assertEquals(Intent.ACTION_WEB_SEARCH, intent.action)
    assertEquals(adopted, intent.getStringExtra("query"))
}
```

**Verification:** Tests pass, no Enter swallowing

---

### Unit 10: Integration & Lint
**Goal:** Verify all tests pass, lint clean, build succeeds

**Commands:**
```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew assembleRelease
```

**Verification:** All pass

---

## Implementation Order

1. ✓ Version bump (Unit 1)
2. ✓ Currency name aliases (Unit 2) - standalone, easy to test
3. ✓ Arithmetic around currency (Unit 3) - depends on Unit 2
4. ✓ Calculator adoption (Unit 5) - independent of currency changes
5. ✓ Currency adoption (Unit 6) - independent of parser changes
6. ✓ Edge case tests (Unit 8) - verify guards
7. ✓ Enter behavior tests (Unit 9) - verify no regressions
8. ✓ Integration (Unit 10) - final verification

**Deferred:**
- Unit 4 (same-currency arithmetic) - complex, not critical for MVP
- Can be added in future version if needed

---

## Test Strategy

### 1. Write Failing Tests First
For each unit:
- Write tests that expect new behavior
- Run tests → verify they fail with expected error
- Implement behavior
- Run tests → verify they pass

### 2. Test Isolation
- Use Robolectric for Android components
- Mock `CurrencyRatesCache` with fixed rates (no live fetching)
- Use fixture data for consistent test results

### 3. Coverage
- Parser logic: unit tests in `CurrencyConverterTest`
- UI behavior: instrumentation tests in `SearchOverlay` tests
- End-to-end: full user flow tests
- Edge cases: explicit tests for guards

### 4. No Reflection Hacks
- Test real UI interactions (click, Enter)
- Don't just set fields and assert same fields
- Verify actual behavior seen by user

---

## Risk Mitigation

**Risk:** Parser changes break existing queries  
**Mitigation:** Run all existing `CurrencyConverterTest` tests, add regression tests

**Risk:** Result adoption interferes with existing navigation  
**Mitigation:** Keep existing nav selection logic, only change action on activation

**Risk:** Currency re-conversion loop  
**Mitigation:** Test adopted result format, verify it triggers expected behavior

**Risk:** Enter key swallowing  
**Mitigation:** Explicit tests for second Enter, verify browser launches

---

## Success Metrics

- [ ] All new tests pass
- [ ] All existing tests pass
- [ ] Lint clean (no new warnings)
- [ ] Debug APK builds
- [ ] Release APK builds
- [ ] Manual verification (if device available)

---

## Deliverables

1. Feature branch: `feature/calc-currency-adoption-v1.9.9`
2. Commits with clear messages
3. PR created (unmerged)
4. Test results (JUnit XML)
5. APK exports (debug & release unsigned)
6. Verification manifest
7. This implementation plan (updated with actuals)
