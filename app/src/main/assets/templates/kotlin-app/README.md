# {{APP_NAME}}

This Android app was created with **[APK Builder](https://github.com/E-najid/Apk-Builder-)** —
a free, open-source app that lets you write and build Android apps entirely from your phone.

Every change pushed to the `main` branch is compiled automatically by
[GitHub Actions](https://github.com/features/actions). You can download the finished
APK from the **Artifacts** section of the latest run on the Actions tab.

## Project layout

- `app/src/main/java/` — application source code (Kotlin + Jetpack Compose)
- `app/src/main/res/` — resources (strings, icons, themes)
- `.github/workflows/build.yml` — the CI workflow that builds the APK
