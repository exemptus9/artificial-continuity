# Intent Integrity 0.2.0 — Android developer preview

Search first. Scroll by choice.

This standalone product lives on an isolated development branch in the Continuity family repository. No existing Continuity app files or production deployments are changed.

## Two builds
- Core: native search launcher, hardened in-app browser, conservative route gate, timed page reveals, session checkpoint, explicit Later shelf, validated JSON backup/restore. INTERNET is the only manifest permission. No accessibility service.
- Guard: everything in Core plus an OPTIONAL metadata-only AccessibilityService. The user selects packages and gives prominent consent. It covers selected native apps on entry, routes deliberate searches to the protected browser, and offers a 60-second ALL-content native pass. This is NOT surgical rewriting of another app, and it cannot guarantee zero first-frame exposure or silence background audio.

## Build
JDK 17, Gradle 8.13, Android SDK 36 / Build Tools 36.0.0.

```sh
cd android
gradle :app:assembleCoreDebug :app:assembleGuardDebug :app:bundleCoreRelease :app:bundleGuardRelease
```

CI provides signed DEBUG APKs for personal testing and UNSIGNED AABs for owner-controlled release signing. Debug signing is not production signing and may change between builds. Export saved links before reinstalling an incompatible debug build. Do not publish the fixture APK; it is for tests only.

## Honest boundaries
- The APK build, Java policy tests, emulator tests, physical phone tests, authenticated platform tests and store review are separate acceptance gates.
- Website logins/uploads may not work in WebView. Unsupported external destinations require an explicit exit confirmation. No SSL bypass, native JS bridge, hidden tracking or remote rule downloads.
- Instagram is username-to-profile navigation, not an advertised general-topic search engine.
- Search results are still platform-ranked; selected communities/profiles can contain recommendations. This app does not abolish algorithms.
- Native guard does not inspect screen text, messages, passwords, screenshots or accessibility node trees. Consent and app choices start empty/off.
- No medical, revenue, store-approval or universal compatibility guarantee.

## Store release gates
Use your own signing keys and developer account. Test on real Samsung/Pixel devices, every advertised platform and Android version, TalkBack, large fonts, rotation, background transitions and revocation. Prepare a public privacy policy and support address, Data safety answers, accessibility declaration and demonstration video (Guard only), screenshots, content rating, billing implementation if selling an upgrade, and any account-specific closed test. Neither build has been submitted to a store by this project.

Copyright 2026. No open-source license or third-party trademark endorsement is granted by this preview. Source is supplied to the project owner for development.
