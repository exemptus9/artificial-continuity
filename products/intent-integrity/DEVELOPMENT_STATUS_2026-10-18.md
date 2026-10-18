# Intent Integrity 0.2.0 — verified development checkpoint

Date: 2026-10-18. This is a developer preview, not a public/store-approved release.

## Primary Android evidence

APK source commit: `ee94f6ab0d73e0ade594892f5b2f4d2b81697bdc`.
Build/test run: https://github.com/exemptus9/artificial-continuity/actions/runs/39174560999

The build and emulator jobs succeeded. Core and Guard debug APKs and unsigned release AABs were produced; APK signatures and output hashes were checked. The final Android API36 emulator suite passed nine checks against a dedicated test app: Core launch, browser route shield, seven search-route checks on-device, disclosure, selected-app overlay, explicit native pass, system-settings access, expiry, and pause.

The initial native test used UI automation that suppressed accessibility services. The final run uses a separate test-only instrumentation APK with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES. Neither the fixture nor the probe is part of the consumer APK.

Final APK SHA256:
- Guard (46,255 bytes): `bd6228f612fc38068fbbe40301ed1fabfeb6d83e4a48196c78b048743146e866`
- Core (36,941 bytes): `cf589558dd8e3e9cdbbdd6021dbfc77ecf175c8e8f26661fdf8a80e49c946f09`

Java policy and monotonic lease tests passed 49 assertions. Debug signing is not the owner's durable release signing identity. The release AABs are unsigned.

## PC and portable delivery

A Chromium extension and responsive launcher are supplied in the conversation's `Intent-Integrity-0.2.0-Complete-Kit.zip`, alongside this Android source, exact build outputs, legacy source, guides and release drafts. PC source is in that delivery kit; it is NOT part of the Android source commit above. JavaScript policy tests passed 102 assertions; production-source Chromium fixture tests passed 21 assertions with explicit URL/storage test doubles. Installed-extension and live-site acceptance remain unverified.

## Honest native behavior

The optional guard covers an entire selected native app. Search opens Intent Integrity's own protected browser; it does not surgically preserve the original native search bar. A native pass deliberately allows all content. First-frame exposure and other-app audio are not guaranteed prevented. No apps are selected by default. Disclosure, user consent, Android permission, pause and revocation remain explicit. The service does not request node trees, screen text, screenshots or gesture capability.

## Release gates still open

Physical Samsung/other-OEM testing; older Android runtime testing; live authenticated platform compatibility; keyboard/TalkBack/rotation/notification and audio edge cases; pinned shortcuts on real launchers; installed PC extension and standalone offline deployment; final identity/license review; hosted privacy/support pages and Data safety assessment; production signing; applicable store testing and approval; billing/monetization. No public production app, payment collection, public web deployment or store submission is claimed.

Keep changes on this isolated branch until a reviewed integration/release decision. Existing Continuity production files were not altered by this feature development.
