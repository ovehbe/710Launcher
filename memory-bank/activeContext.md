# Active Context

## Current work focus
- **Stability and refresh:** Reduce need for launcher restart (e.g. after clearing notifications, switching grid/list, changing settings). Ticker and count should update when notifications change; view mode and similar settings should apply without full restart where possible.
- **Polish:** Touchpad highlight size, persistent hide of Frequent/All when hidden, notification applet icon quality, general reliability.

## Recent changes (summary)
- Header opacity fix: BBStatusBar, HeaderView, and tab bar shared bb_overlay drawable; alpha set on one overwrote others. Each now calls background?.mutate() before setting alpha so opacity sliders work correctly and persist across home/restart.
- Search/trackpad: search field GONE when overlay closed; no programmatic focus on pager when overlay dismissed; type-to-search from home with Alt/Shift for symbols.
- Default tab by page ID; hide All/Frequent; hidden apps; dock and tab bar customization (color, highlight opacity).
- Action bar center: configurable (hub / app / shortcut). Click highlights (ripple + no focus ring); refresh on resume so accent change applies.
- Notification applets (optional per-app icons + counts); settings export/import; header tap actions; icon picker search; icon shaping.

## Next steps (from project priorities)
1. Fix notification ticker/count not updating after clear until restart; ensure related UI refreshes on notification and setting changes.
2. Fix Frequent/All page visibility so hidden state is persistent (no random show/hide).
3. Improve notification applet icon display (quality/custom icons).
4. Consider analysis of “buggy nature” (restart requirements) before further fixes; then call/contact search, notification hub item selection (and trackpad selection).

## Active decisions and considerations
- **Branching:** Work on `dev`; tag v1.4.2; app version (versionCode/versionName) kept at 1.4.1 until merge to main and version bump.
- **Scope:** Do not change unrelated parts of the app; minimal, targeted changes; when in doubt, ask before touching other areas.
- **Memory bank:** This directory is the persistent context for the AI; update with “update memory bank” after significant changes.
