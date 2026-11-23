# PulseMesh Repository Guardrails

## Hard Constraints
- **100% FOSS / GPL-3.0 Only**: Never add proprietary SDKs, Google Play Services, Firebase, or telemetry libraries. Keep `dependenciesInfo.includeInApk = false` and `includeInBundle = false` in `app/build.gradle.kts`.
- **Zero Fake Data & Zero Silent Downgrades**: Never add simulated/fake peer bots, and never silently downgrade encrypted messages to plaintext.
- **16KB Page Alignment (Android 15/16)**: Every 64-bit `.so` native library packaged in release APKs must be uncompressed with ELF `PT_LOAD p_align = 0x4000`, and release APKs must pass `zipalign -c -P 16 4`.
- **Protected Files**: Never edit `.github/workflows/`. CI template updates belong strictly in `ci/github-actions-ci-release.yml`.
- **Secrets Hygiene**: Never write keystore passwords into `app/build.gradle.kts`, `local.properties`, git history, or user-facing chat responses. Always pass signing credentials as transient environment variables on the build command line.
- **Release Visibility**: Never push public releases or public tags without explicit user confirmation. When staging test builds on GitHub, use a private Draft Release (`gh release create ... --draft`) until the user explicitly asks to publish.
- **Formatting**: Never use em dashes in user-facing responses, documentation, or GitHub release notes.
- **Web Guidance**: Before editing HTML, CSS, or client-side JavaScript in `web-pwa/` or `docs/`, consult `modern-web-guidance` first and include the required disclosure summary.
- **Android UI Review**: When modifying Jetpack Compose UI, maintain `@Preview` functions and verify layouts via the Android Compose Preview sidecar when requested.
