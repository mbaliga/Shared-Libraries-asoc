# Device checklist templates

Templates for the owner's on-device validation, one per platform (porting program foundation item F11). Seeded from
asystemofmodels' own checklists (`ubuntu-touch/docs/DEVICE_CHECKLIST_UT.md`, `desktop/docs/DEVICE_CHECKLIST_LINUX.md`,
`desktop/packaging/{macos,windows}/docs/DEVICE_CHECKLIST_*.md`).

| File | Row ids | Covers |
|---|---|---|
| `DEVICE_CHECKLIST_UT.md` | `DV-UT-nn` | Ubuntu Touch: confinement, freeze, network quiescence, click review |
| `DEVICE_CHECKLIST_LINUX.md` | `DV-LX-nn` | Linux desktop, Steam Deck, Redmagic-Edge |
| `DEVICE_CHECKLIST_IOS.md` | `DV-IOS-nn` | iPhone: background grace, Keychain, backup, privacy |
| `DEVICE_CHECKLIST_IPADOS.md` | `DV-IPAD-nn` | iPad Pro M4: windowing, keyboard, pointer, Pencil |
| `DEVICE_CHECKLIST_MACOS.md` | `DV-MAC-nn` | macOS: Gatekeeper, Keychain/Secure Enclave, menu bar |
| `DEVICE_CHECKLIST_WINDOWS.md` | `DV-WIN-nn` | Windows: SmartScreen, MSI, firewall, TPM, DPI |
| `DEVICE_CHECKLIST_ANDROID_DESKTOP.md` | `DV-AD-nn` | Android in resizable/freeform windows with mouse and keyboard |

Every row starts as `NEEDS-DEVICE-VALIDATION`. **Nothing in these files was run on any device**: the builder had no phone, tablet,
Mac, Windows PC or Android SDK. Hosted-runner, emulator, simulator and VM results are different evidence labels and never close a
row. Each file ends with a "Common rows" block (idle network, key tier, colour-never-alone, plain files, crash recovery,
uninstall) that checks directives I-1, I-2, I-3, I-4 and I-5.

To use one: copy it into the app repo's `docs/`, replace the `<app>`, `<id>` and `<ver>` placeholders, delete rows that do not
apply (keep the ids of the rows you keep), append app-specific rows with the next free id. Record results as `platform-evidence/1`
records (see `evidence-schema/README.md`) or as pasted output next to the row. The 7 files are gated by `PlatformEvidenceTest`,
which fails if a row is not open, an id repeats or skips, or a file is missing.
