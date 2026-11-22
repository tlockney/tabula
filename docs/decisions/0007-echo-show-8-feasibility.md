# 0007: Echo Show 8 (2nd gen) feasibility

Status: superseded by [0026](0026-echo-show-8-owner-unlocked-target.md) (owner decision 2026-11-22: the device stays in v1, owner-unlocked and running LineageOS 18.1)
Date: 2026-11-21
Packet: P0.6

Supersedes the Echo Show sentence in ADR 0001's "Additional targets" paragraph: plan section 4 keeps the Echo Show 8 (2nd gen) in v1 subject to this ADR, rather than dropping it.

## Context

Plan section 3 lists two Echo Show 8 (2nd gen) units as v1 targets, gated on this packet. Tabula is a sideloaded Android app, so the device must allow ADB through its own settings, keep a sideloaded app in the foreground, and ship a WebView at or above the frontend's legacy floor (Chromium 67, ADR 0001). Exploits, rooting, bootloader unlocking and firmware downgrades are not allowed (P0.6 step 2): any of them is a no-go.

## Research findings

Public sources searched on 2026-11-21. Results from the original product's site or documentation were skipped (AGENTS.md clean-room rules). The only sources specific to the Echo Show 8 **2nd gen** describe exploits; none describes a settings-only path to ADB.

| Finding | Source (date) |
| --- | --- |
| Echo Show 8 (2nd gen): released June 2021, MediaTek MT8183, 8" 1280×800 screen. The 1st gen (2019, codename `crown`) is MT8163, also 1280×800. | [Wikipedia: Amazon Echo Show](https://en.wikipedia.org/wiki/Amazon_Echo_Show) (undated; release date cited to PCMag) |
| As of January 2026, the public jailbreak (the "fastbrick" fastboot exploit for MT8163, then root) supported only the Echo Show 8 1st gen and the Echo Show 5. Its developer said the Echo Show 8 2nd gen was not supported because its SoC is not vulnerable to that exploit. | [Hackaday](https://hackaday.com/2026/01/02/jailbreaking-the-amazon-echo-show), 2026-01-02, article and developer comment |
| Aftermath lists three supported models (Echo Show 5 1st gen `checkers`, Echo Show 5 2nd gen `cronos`, Echo Show 8 1st gen `crown`) and says "later models may never be cracked"; sideloading on Fire OS there requires the exploit. | [Aftermath](https://aftermath.site/amazon-echo-show-jailbreak-lineageos/), 2026-03-12 |
| Notebookcheck says the exploit works only on first-generation Echo Show 5 and 8 (its article predates the `cronos` release). | [Notebookcheck](https://www.notebookcheck.net/Amazon-Echo-Show-jailbreak-unlocks-the-full-Android-tablet-within.1163453.0.html), 2025-11-15 (updated 2026-10-05) |
| **Since October 2026 a separate exploit unlocks the Echo Show 8 2nd gen (codename `athena`)**: a preloader/META-mode exploit over USB, followed by bootloader unlock, root and TWRP, with an unofficial LineageOS 18.1. It does not enable ADB through the device's settings; it is an exploit and a bootloader unlock. | XDA thread "[UNLOCK][ROOT][TWRP][UNBRICK] Amazon Echo Show 8 2nd Gen / 2021 (athena)", `xdaforums.com/t/…athena.4804958`, opened 2026-10-13, release saiyaku-athena-v1.1.1 edited 2026-10-22; [EchoLocal prerequisites](https://echolocal.link/start/before/) (undated; read 2026-11-21) lists "Echo Show 8 (2nd gen, athena)" among models with XDA unlock guides ending in TWRP, linking that thread. The thread itself could not be fetched from the research environment; its method and dates are as reported in the PR #41 review. |
| Home Assistant guides for the Echo Show 8 cover the 1st gen only, and all of them need the exploit (Amonet, TWRP, patched boot). One author answers "No" to a 2nd-gen question (2026-07-11). | [Derek Seaman](https://www.derekseaman.com/2025/11/home-assistant-hacking-your-echo-show-5-and-8.html), 2025-11-22 (updated 2026-10-04); [localsmarthomeguide](https://localsmarthomeguide.com/articles/echo-show-8-to-home-assistant-smart-display/), checked 2026-08-31 |
| On the original 2017 Echo Show, a developer-options screen exists but offers no ADB or "unknown sources" option. | [AFTVnews](https://www.aftvnews.com/amazons-echo-show-runs-android-and-has-developer-options/), 2017-06-28 |
| Echo Show 5 1st gen (exploited): pressing home goes to the installed launcher; Amazon services keep running in the background. Not evidence for an unexploited 2nd gen 8. | [How-To Geek](https://www.howtogeek.com/how-i-turned-my-echo-show-into-a-home-assistant-control-panel/), 2025-11-02 |

Not readable from the research environment (fetch refused): the XDA threads "Amazon Echo show 8 2nd", "Usb debugging on echo show 8 gen 1", "Echo Show 8 3rd gen unlock ADB via pogo-pin" and "sideloading on echo show 15 (2nd generation)", plus droidwin's Echo Show sideloading article. Hardware-access paths (pogo pins, UART and eMMC) concern the 3rd gen (`rhodes`, per Derek Seaman's 2026-07-11 reply); the 2nd gen's known path is the USB preloader exploit above. None of them is an allowed means.

## Decision

**Provisional no-go, pending the device check.** No public source shows ADB being enabled on the Echo Show 8 2nd gen through its own settings. Every known working path to a sideloaded app on any Echo Show, including the October 2026 `athena` exploit for this exact model, is an exploit plus bootloader unlock, which P0.6 step 2 and plan section 3 rule out. Absence of evidence is not proof, and the hardware verifier owns two units, so criterion 1 is settled on the device:

| Criterion | Status |
| --- | --- |
| 1. ADB can be enabled by the allowed means | Research: no evidence it can. **Device check decides.** |
| 2. Sideloaded app installs, stays resumed after 5 idle minutes, relaunches | Untested; runs only if 1 passes |
| 3. WebView is Chromium 67 or later | Untested; runs only if 1 passes |

The device check (hardware verifier, read-only, no exploits):

1. In the device's own settings, look for an "About" or device-information screen and any developer options. Tapping the build or serial entry repeatedly is the usual Android way to reveal developer options. Record what is there.
2. If a USB debugging or ADB switch exists, turn it on, connect the device to a computer with `adb`, and run `adb devices -l`. Then continue with P0.6 steps 3 and 4.
3. If no such switch exists, record the firmware version shown in settings and stop: the result is no-go.

The final go/no-go is written here, replacing "proposed", in the pull request that pastes the device output.

## Consequences

- On no-go (the expected outcome): `docs/PLAN.md` moves the Echo Show to Deferred in sections 3, 4, 7.0, 7.3 and 9; #33 (P2.7) is closed as not planned; the P2.1 boot-start observation on an Echo Show is dropped. The Fire HD 10 Plus tablets carry the milestone, as the plan section 9 risk table already allows.
- On go: plan section 3's resolution (1280×800, matching Wikipedia's spec table) is confirmed or corrected from `wm size`, and P2.7 stays.
- Until the device check, no packet assumes either outcome. P2.1's Echo Show observation stays conditional.

## Alternatives considered

- **Decide no-go from research alone:** the evidence is consistent, but no source covers the 2nd gen's own settings, the hardware is on hand, and the check takes minutes.
- **Use the 1st-gen exploit path or hardware access (pogo pins, UART):** excluded by P0.6 step 2 and the plan's no-exploit rule.
