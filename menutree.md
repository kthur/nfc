# NFC Commander & Emulator - Navigation Menu Tree Map

## 📑 Application Navigation & UX Architecture

```markdown
├── 1. Main Navigation Bar [main-bottom-navigation]
│   ├── [tab-0-scan] 카드 스캔 (NFC Scan & CUID Diagnostic)
│   │   ├── Status Hero Banner [hero-status-bar]
│   │   │   └── NFC Chip Readiness & Connection Badge
│   │   ├── Empty Scan State [scan-empty-view]
│   │   │   └── Radar Animation & Usage Instructions
│   │   └── Scanned Card List [scanned-card-list]
│   │       └── Modern Card Item [modern-card-item]
│   │           ├── Monospace Formatted UID Display [card-uid-text]
│   │           ├── CUID (Gen2) Compatibility Badge [cuid-status-badge]
│   │           ├── Tech List Chips [tech-list-chips]
│   │           └── Quick Action "UID 쓰기" Button [copy-uid-action]
│   │
│   ├── [tab-1-write] UID 쓰기 (UID Writer & Cloner)
│   │   ├── Step 1. Tag Type Selector [chipset-type-selector]
│   │   │   ├── CUID (Gen2) Chip [mode-cuid-gen2]
│   │   │   └── Gen1a (Magic) Chip [mode-gen1a-magic]
│   │   ├── Step 2. Target UID Input Form [target-uid-form]
│   │   │   ├── 8-char Hex Input Field [target-uid-input]
│   │   │   └── Quick Action Controls
│   │   │       ├── Random UID Generator [btn-random-uid]
│   │   │       ├── Recent Scanned UID Paste [btn-recent-uid]
│   │   │       └── Hex Auto-Formatter [btn-clean-hex]
│   │   ├── Step 3. Sector 0 Auth Key Form [auth-key-form]
│   │   │   ├── 12-char Hex Key Field [auth-key-input]
│   │   │   └── Preset Key Chips [preset-key-ffffffffffff, preset-key-000000000000]
│   │   └── Step 4. Guidance & Alignment Banner [write-guidance-banner]
│   │
│   └── [tab-2-hce] HCE 에뮬레이션 (Host Card Emulator)
│       ├── Virtual Smart Card Preview Graphic [virtual-card-preview]
│       │   ├── AID Display (F0010203040506) [card-aid-text]
│       │   ├── Active HCE Status Badge [hce-active-badge]
│       │   └── APDU Response Status (9000) [hce-status-9000]
│       ├── APDU Payload Form [apdu-payload-form]
│       │   ├── Response Payload Text Field [apdu-payload-input]
│       │   └── Quick Preset Chips [preset-hello-nfc, preset-student-id]
│       └── HCE Security Notice Card [hce-security-notice]
```

## 🛠️ Code Mapping Alignment Table

| Route / Component ID | Kotlin Compose Component / File | Purpose & Function |
| :--- | :--- | :--- |
| `main-bottom-navigation` | `MainActivity.kt` - `NavigationBar` | Main 3-tab navigation bar |
| `tab-0-scan` | `ScanTabContent` | NFC Tag scanner & non-destructive CUID tester |
| `tab-1-write` | `WriteTabContent` | Sector 0 Block 0 CUID (Gen2) / Gen1a UID cloner |
| `tab-2-hce` | `HceTabContent` | Host Card Emulation (APDU SELECT AID server) |
| `modern-card-item` | `ModernCardInfoItem` | Formatted card view with CUID compatibility badge |
| `cuid-status-badge` | `detectTagType()` in `MainActivity.kt` | Non-destructive CUID (Gen2) writeability tester |
