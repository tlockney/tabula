# 0026: Echo Show 8 (2nd gen) stays a v1 target as an owner-unlocked LineageOS 18.1 device

Status: accepted
Date: 2026-11-22
Packet: P0.6

Supersedes ADR 0007's provisional no-go, for this device only. ADR 0007's research findings and the USB probe stand as the record of why no settings-only path exists; this ADR records the owner's decision on what to do about it.

## Context

ADR 0007 concluded that no public source shows ADB being enabled on the Echo Show 8 (2nd gen) through its own settings, and that every known working path to a sideloaded app on this model is an exploit plus bootloader unlock — which plan section 3's no-exploit rule ruled out. The USB probe on #32 confirmed the stock device: over USB it exposes a single MTP interface (1949:01c1, Lab126 "Alexa") and no ADB interface, so stock firmware offers no ADB at all.

The owner (Thomas) decided on 2026-11-22: the Echo Show 8 (2nd gen) stays a v1 target, as an unlocked device running the community LineageOS 18.1 (Android 11) image. He performs the MediaTek bootloader unlock and the LineageOS flash himself, by hand, from the public community guide. Agents never drive the exploit: no build-side context runs, scripts or automates it. Build-side work on the device starts only after the flash, from provisioning onward, over ADB. This is an explicit, owner-approved exception to the plan's no-exploit rule, for this device only.

## Verified public sources

Both XDA threads were read in full (2026-11-21). Neither is the original product's documentation; they are community hardware guides, which the clean-room rules allow.

- **Unlock thread** ("[UNLOCK][ROOT][TWRP][UNBRICK] Amazon Echo Show 8 2nd Gen / 2021 (athena)", xdaforums.com/t/…athena.4804958, current release saiyaku-athena-v1.1.1): an exploit-based MediaTek bootloader unlock for the 2nd gen (`athena`) only, driven over USB serial from a host (preloader/META mode, Python + pyserial). It flashes the preloader, the bootloader (LK), the recovery and other partitions, leaving TWRP installed. The thread carries an explicit brick warning; an unbrick procedure exists but only while the preloader is alive, and touching the bootloader or other critical partitions afterwards is a permanent brick with no recovery. The unlock requires the device to be on a Fire OS build from the FTVDB supported list ("If it's not listed, IT IS NOT SUPPORTED"); replies in the thread tell users of older builds to update first, and one asks how to reach a specific build on a stock, locked device when Amazon is not offering it over OTA. After the unlock, Fire OS versions can be freely installed, updated or downgraded through TWRP. The thread documents no way back to a locked, stock boot chain.
- **ROM thread** ("[ROM][UNOFFICIAL][11][athena] LineageOS 18.1 for the Amazon Echo Show 8 (2021)", xdaforums.com/t/…athena.4805400): an unofficial LineageOS 18.1 (Android 11) for `athena` only, flashed from TWRP after the unlock. Stated properties: SELinux is permissive ("Please DO NOT store any sensitive data on this build!"), deep sleep is intentionally disabled, the mute button also acts as the power button, the system reports a 100% battery, and ROM updates arrive as ZIPs flashed through the recovery. The ROM instructions reference unlock release 1.1.0 while the unlock thread's current release is 1.1.1.
- **FTVDB** (ftvdb.com/echo/firmware/com.amazon.athena.android.os/, read 2026-11-21): 29 public full-firmware records for this model, Dec 2023 through Nov 2026, confirming the firmware line and build numbering the unlock's supported-build requirement refers to.
- **Issue #32 USB probe**: stock device exposes only MTP (1949:01c1), no ADB interface; pasted output in the issue comment.

**Not verified from the sources.** The threads do not state, in so many words, that the device must stay off Wi-Fi until it is unlocked, or that Amazon OTAs stop applying once the image is on. The first follows from what they do state (the unlock needs a build on the supported list, and a stock, locked device updates itself by Amazon OTA, so an unexpected update could land it on an unsupported build); the second follows from replacing Fire OS with LineageOS. Both are recorded here as the owner's stated precautions, consistent with the sources, not as source-verified sentences.

## Decision

**Go, by the owner's path.** The Echo Show 8 (2nd gen) remains a v1 target, with these bounds:

1. **The owner performs the unlock and flash.** Thomas does the bootloader unlock and the LineageOS flash himself, by hand, from the public community guide. No agent runs, scripts or automates the exploit; build-side briefs and scripts never contain unlock steps. Build-side work on the device starts only once it boots LineageOS, from provisioning onward, over ADB. Plan section 3's no-exploit rule stands everywhere else, unchanged.
2. **The target is LineageOS 18.1 (Android 11, API 30), not Fire OS.** The Fire OS constraints in plan section 3 (Amazon WebView, mic revocation, no device owner) do not apply to this device. The community image's own properties apply instead, and the probe in P2.7 verifies them on the real device: WebView version (Android 11's WebView is a different one from the HD 10 Plus's, to be measured), SELinux mode (expected permissive per the ROM thread), available sensors, and screen geometry.
3. **Risks accepted.** One-way: the boot chain stays unlocked; the sources document returning to stock Fire OS as an unlocked device through TWRP, but no way back to a locked state. No Amazon OTAs: system updates come only from the community image's release channel, flashed through the recovery. Permissive SELinux: the ROM thread's own warning. Brick risk during the unlock belongs to the owner's manual step; the community unbrick procedure requires a live preloader. The device is therefore treated as less trusted hardware: nothing sensitive beyond the paired HA credential lives on it, which matches the plan's existing threat model (a non-admin credential that HA can revoke in one click).

## Consequences

- ADR 0007 is superseded for this device and marked so; its research remains the record of why the settings-only path is closed.
- Plan sections 3, 4 and 9 are amended by the same pull request: the no-exploit rule gains this one owner-approved exception, and the target table moves the Echo Show from "gated on P0.6" to this decision.
- P2.7 re-scopes its bring-up to the owner-flashed device: a read-only device probe is its first step (the ADR 0001 item 6 commands plus screen, sensor and SELinux checks), then provisioning per DEVICE.md. P2.7 cannot start until the owner has flashed the device; #33 tracks that wait.
- The platform widens from one Android version (Fire OS 7 / Android 9 / API 28) to also Android 11 / API 30 with its own WebView version, measured at the probe. minSdk 24 and ADR 0008's targetSdk are compatible with API 30; the frontend floor question (Chromium 67, ADR 0001 item 6) is answered by the probe's WebView readout.
- The P2.1 boot-start observation now runs on the LineageOS device, not on stock firmware.
- Phase 7's voice mic spike, when it happens, applies to the LineageOS device; Fire OS mic revocation does not exist there.
- DEVICE.md notes the boundary: the owner unlocks and flashes first; the probe and provisioning follow, over ADB, and never re-flash or re-unlock anything.

## Alternatives considered

- **No-go, move the Echo Show to Deferred (ADR 0007's provisional outcome):** the Fire tablets could carry the milestone alone, but the owner chose to use the two units he already owns, and the unlock path is public and documented.
- **Agents drive or script the unlock:** rejected. The exploit is one-way with real brick risk; keeping every agent entirely out of it preserves the no-exploit rule where it matters (no build-side context ever touches exploit tooling) and leaves the irreversible step with the person who owns the hardware.
- **Unlock but stay on stock Fire OS (TWRP + root, no LineageOS):** rejected. The Alexa home screen still owns the foreground, the WebView stays Amazon's un-updated one, and the sideload still requires the exploit — strictly worse for Tabula than the community Android 11 image.
- **Wait for a settings-only ADB path:** no public evidence one exists (ADR 0007), and the USB probe shows no ADB interface to enable.
