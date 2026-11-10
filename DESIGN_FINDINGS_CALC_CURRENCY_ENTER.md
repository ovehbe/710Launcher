# Calculator/Currency/Enter Integration - Design Findings & Status

**Commit**: `d1dc9cf` on branch `cursor/calc-currency-adoption-v1.9.9-90f7`

## Test Results Summary

**Focused Test Suite: 128/136 GREEN (94%)**

### ✅ PASSING (128 tests)
- **CalculatorCurrencyMatrixTest**: 128/128 GREEN - ALL matrix cases passing!
- **CalculatorTest**: 30/30 GREEN - API contracts preserved
- **CalculatorCurrencyAdoptionTest**: 30/30 GREEN - Enter adoption working

### ⚠️ PARTIAL (15 tests, 8 failing)
- **CurrencyConverterTest**: 58/64 GREEN (6 failing)
- **EnterKeyChainingTest**: 2/3 GREEN (1 failing) 
- **CurrencyAliasQuickTest**: 7/8 GREEN (1 failing)

## Implementation Summary

### 1. Unified Tokenization (QueryTokenizer.kt)
**Created new file** providing ONE parse path for both calculator and currency:
- Recognizes currency tokens (ISO/alias/symbol) BEFORE letter-digit mapping
- Prevents "yen" → ")2," garbage by detecting currencies first
- Supports negative amounts (-, −, "- 4185 yen")
- Handles reversed order (jpy 100, yen 100), symbols ($€£¥), zero amounts

### 2. Display Validation (SearchOverlay.kt)
**Moved from toExpression to evaluateCalculator()** per API contract requirements:
- Plain numbers (123, -4185, w00→100) → no calculator row
- Negative currency amounts (-100 yen) → no calculator row  
- Short words without operators (w, wee, w00t, year) → no calculator row
- Leading/trailing operators (*100, 100*) → no calculator row
- Unbalanced/implicit parens (100(, 3(4)) → no calculator row
- **BUT**: Currency arithmetic (100 yen * 10) → SHOW calculator with "1000 JPY"

### 3. Calculator API Contract Preserved (Calculator.kt)
- `toExpression()` returns plain numbers (123), incomplete expressions (2+)
- `allowPlainNumber` parameter still works
- Display logic moved to SearchOverlay, not in toExpression

### 4. Currency Pre-Processing (CurrencyConverter.kt)
**Added before tokenization**:
- `insertSpacesBeforeCurrencies()`: "100usd" → "100 usd"
- `simpleExpandMagnitudes()`: "100k" → "100000", "1m" → "1000000", "5bn" → "5000000000"
- `simpleExpandNumberWords()`: "one million" → "1000000", "half a million" → "500000"
- Trailing text rejection: "10 jpy abc" → null

### 5. Fixed Bugs
- dialRow/calculatorRow tag confusion (added tag="calculatorRow")
- Currency aliases no longer garbage-mapped ("yen" safe from w=1,e=2,...)
- Negative amounts in currency queries now work
- Parenthesis validation prevents invalid expressions from showing

## Remaining Issues (8 failures)

### CurrencyConverterTest (6 failures)
Complex magnitude/number word edge cases not yet handled:

1. **"one m idr"** - should parse as 1m = 1,000,000 IDR
   - Issue: "one" → "1" but "1 m" not expanded
   
2. **"2m idr"** - BB trailing 'm' should mean million
   - Issue: Regex needs better context detection
   
3. **"1 million idr"** - mixed format (digit + word)
   - Current: "1 million" not in replacement list
   
4. **"100kusd"** - magnitude suffix with no space before currency
   - Issue: Need to handle "100k usd" after space insertion
   
5. **"5 billion"** - number word phrase
   - Current: Only handles "five billion", not "5 billion"
   
6. **"100 aud"** (explicit variants test)
   - Need to investigate - may be unrelated issue

### EnterKeyChainingTest (1 failure)
**Currency code preservation in arithmetic**:
- "100 yen / w0" + Enter should adopt **"10 JPY"** (with code), not plain "10"
- Issue: QueryTokenizer.toCalculatorExpression strips currency tokens
- Solution: Need to preserve currency code from expression in result

### CurrencyAliasQuickTest (1 failure)
- Need to check which specific alias test is failing

## Architecture Decisions

### Display Rules vs. API Contracts
**User requirement**: Keep existing APIs' contracts; put new matrix rules at the overlay/display decision.

✅ **Implemented**: 
- `Calculator.toExpression()` unchanged API - returns expressions for everything
- `SearchOverlay.evaluateCalculator()` decides what to display
- This separation allows API compatibility while enforcing UX rules

### Currency Arithmetic Priority
**User requirement**: If calculator result present, Enter ALWAYS adopts calculator value before currency rows.

✅ **Implemented in matrix**:
- "100 yen / w0" → calculator shows "10 JPY" (arithmetic result)
- Enter adopts calculator (10 JPY), not conversion rows
- ⚠️ **Still needs**: Currency code preservation in adopted value

### Expansion Strategy
**Decision**: Simple direct replacement vs. complex parser

✅ **Chose**: Simple regex replacement before tokenization
- `simpleExpandMagnitudes()` - direct "100k" → "100000"
- `simpleExpandNumberWords()` - direct "one million" → "1000000"  
- ❌ **Rejected**: Old expandMagnitudeSuffixes (adds "*1000" operators)
- **Reason**: QueryTokenizer expects clean numeric expressions

## Next Steps to Complete

### High Priority (EnterKeyChainingTest)
1. Modify QueryTokenizer.toCalculatorExpression() to preserve currency codes
   - When expression contains Currency token, append code to result
   - "100 yen / 10" → return "10 JPY" not "10"

### Medium Priority (CurrencyConverterTest)
2. Improve number word expansion:
   - Handle "1 million", "2 million", etc. (digit + word combos)
   - Handle "one m", "two m" (word + suffix combos)
   - Handle "5 billion" (digit + word phrases)

3. Fix magnitude suffix edge cases:
   - Better trailing 'm' detection for BB keyboard
   - Handle "100kusd" after space insertion ("100k usd")

### Low Priority
4. Investigate CurrencyAliasQuickTest failure
5. Check "100 aud" explicit variants test

## Files Modified

1. **QueryTokenizer.kt** (NEW) - Unified tokenization
2. **SearchOverlay.kt** - Display validation in evaluateCalculator()
3. **Calculator.kt** - Simplified, removed validation
4. **CurrencyConverter.kt** - Pre-processing functions added
5. **CalculatorCurrencyMatrixTest.kt** - Tag-based view finding

## Testing Strategy

Per user requirement: **CODING ONLY - no full suite, clean builds, or APK packaging**.

**Inner loop**: Run focused test set only
```bash
./gradlew testDebugUnitTest \
  --tests "CalculatorCurrencyMatrixTest" \
  --tests "CurrencyAliasQuickTest" \
  --tests "CurrencyConverterTest" \
  --tests "CalculatorTest" \
  --tests "EnterKeyChainingTest" \
  --tests "CalculatorCurrencyAdoptionTest"
```

**Separate agent** will run full suite and build 1.9.12 ZIP.

## Key Learnings

1. **Test infrastructure bugs are product bugs**: "w00" showing stale calculator row after backspace is a real UX issue, not just test flakiness.

2. **API contracts matter**: Changing toExpression() to reject plain numbers broke CalculatorTest. Display logic belongs in UI layer, not API layer.

3. **Order of operations**: Currency detection MUST happen before letter-digit mapping to prevent alias garbage.

4. **Unit test mocking**: android.util.Log calls break unit tests - they're not mocked in Robolectric.

5. **Regex complexity**: Direct replacement ("100k" → "100000") is simpler and safer than expression builders ("100k" → "100*1000") when feeding into a tokenizer.

---

**Current Status**: 94% of focused tests passing. Matrix test fully GREEN (128/128). Core functionality working. Edge cases remain.
