# APK Builder

[![Build APK](https://github.com/E-najid/Apk-Builder-/actions/workflows/build.yml/badge.svg)](https://github.com/E-najid/Apk-Builder-/actions/workflows/build.yml)

**Write and build real Android apps — entirely from your phone.**

APK Builder is a free, open-source Android app (Kotlin + Jetpack Compose) that
lets you create, code, compile and install other Android apps without a
desktop, Android Studio, or any knowledge of CI systems. It doesn't compile
anything on-device. Instead it uses **your own GitHub account as an invisible,
zero-budget build backend**:

```
 ┌────────────────────┐  REST (HTTPS)   ┌──────────────────────────────┐
 │      Phone         │ ──────────────► │           GitHub             │
 │  ┌──────────────┐  │                 │                              │
 │  │ APK Builder  │  │  create repo    │  your repo (public, yours)   │
 │  │  · editor    │  │  push files     │   └─ .github/workflows/      │
 │  │  · build UI  │  │  poll runs      │        build.yml             │
 │  │  · install   │  │  ◄──────────────│   GitHub Actions runner      │
 │  └──────────────┘  │  artifact zip   │   (free, unlimited minutes   │
 └────────────────────┘                 │    on public repos)          │
                                        └──────────────────────────────┘
```

1. You write (or later: generate) app code inside the built-in editor.
2. On **Build**, the app pushes the code to a GitHub repo it creates for you —
   a repo that already contains an auto-generated GitHub Actions workflow.
3. GitHub Actions compiles the project and uploads the APK as an artifact.
4. The app polls the Actions API, shows real step-by-step progress, and lets
   you download / share / install the APK directly.

**Zero budget, by design:** no backend server, no paid APIs, no Play Store
listing. The only moving parts are the app on your phone and GitHub's free
tier (unlimited Actions minutes on **public** repositories, free OAuth device
flow). This app is itself built by the same mechanism — see
[`.github/workflows/build.yml`](.github/workflows/build.yml).

---

## Table of contents

- [Getting the app](#getting-the-app)
- [OAuth setup (important for contributors)](#oauth-setup)
- [Architecture](#architecture)
- [Why a native code editor (and not WebView + CodeMirror)](#why-a-native-code-editor)
- [AI coding agent (bring your own provider)](#ai-coding-agent-bring-your-own-provider)
- [GitHub API usage](#github-api-usage)
- [The project template](#the-project-template)
- [Workflow generation (v1 static, v2 dynamic)](#workflow-generation)
- [Project structure](#project-structure)
- [Building APK Builder itself](#building-apk-builder-itself)
- [Security notes](#security-notes)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

## Getting the app

The easiest way is to build it with itself:

1. Fork or push this repository to your GitHub account.
2. Open the **Actions** tab and run the **Build APK** workflow
   (it also runs automatically on every push to `main`).
3. Download the `app-debug` artifact from the finished run and install the APK
   on your phone.

Or build locally with Android Studio (see
[below](#building-apk-builder-itself)) — you'll need your own OAuth client ID.

## OAuth setup

**End users never see any of this.** The client ID ships inside the
distributed APK, so for them sign-in is just: tap "Continue with GitHub" →
enter a short code → done. This section is only for the maintainer building
the app, once.

APK Builder signs in with the **GitHub OAuth Device Flow**: you're shown a
short code, you enter it at `github.com/login/device`, and the app receives a
token. No client secret and no redirect URI are involved, which is exactly why
this flow fits an open-source mobile app with no backend.

Because the app requests only the scopes it needs:

- `public_repo` — create/modify **public** repositories (the zero-budget
  requirement means all app repos are public)
- `workflow` — push the generated `build.yml` file

…your private repositories stay invisible to it.

**If you build from source** you must register your own OAuth app (it takes
two minutes):

1. Go to <https://github.com/settings/applications/new> and create an OAuth
   App. Any name works; Homepage URL / callback URL can be anything (the
   callback is never used by the device flow).
2. In the app's settings, enable **Device Flow**.
3. Provide your client ID in whichever way fits:

   - **Commit it (simplest, recommended):** uncomment `GITHUB_CLIENT_ID=` in
     the repo's `gradle.properties` and paste your ID. Every build — CI or
     local — then has sign-in working out of the box. Client IDs are public
     identifiers, so committing one is safe; this is how the released APK
     should get its ID.
   - **CI-only via secret:** add a repository secret named
     `GITHUB_CLIENT_ID` (repo → Settings → Secrets and variables → Actions).
     The workflow bakes it into every APK it builds.
   - **Local builds without touching the repo:** build with
     `-PGITHUB_CLIENT_ID=Iv1.xxxxxxxxxxxxxxxx` or put
     `GITHUB_CLIENT_ID=Iv1.xxxxxxxxxxxxxxxx` into
     `~/.gradle/gradle.properties`.
   - **Last-resort fallback, at runtime:** if no client ID was baked in at
     all, the sign-in screen detects it and shows a one-time setup card where
     the user can paste their own client ID. It's stored in the app's private
     DataStore — still no server, no database. End users of a properly built
     release never see this screen.

Client IDs are public identifiers (they ship inside every OAuth app), so
none of this involves a secret. Distributors of prebuilt APKs embed their own
client ID; end users never need to care about any of this — they just see the
device-code screen.

## Architecture

The app is a standard single-module Compose app with a deliberately thin
layering:

```
ui/          Compose screens + ViewModels (MVVM, StateFlow)
domain/      pure logic: package-name validation, toolchain detection,
             step mapping, log summarization  (unit-tested, no Android deps)
data/        GitHub API client + repositories + template engine
  · GitHubApi          Retrofit interface (see table below)
  · DeviceFlowClient   raw OkHttp calls to github.com/login/*
  · GitRepository      multi-file commits via the Git Data API
  · ProjectCreator     repo + template + topics, in one orchestrated flow
  · BuildOrchestrator  commit → wait for run → dispatch fallback
  · LocalProjectStore  on-device cache of unsaved editor edits
util/        time/bytes formatting, zip, MediaStore, QR, intents
```

There is **no database and no backend**: the source of truth for projects is
the user's GitHub account, and everything else (unsaved edits, the OAuth
token) lives in app-private storage / DataStore.

### Key flows

**Create project.** The user fills in a form (name, icon, package name, SDK
levels, framework). The app: slugifies the name, creates a **public** repo via
`POST /user/repos` (born with an initial commit — GitHub's Git Data API can't
operate on zero-commit repos), renders the Kotlin template (see below) and
pushes **all 17 files in a single commit** using the Git Data API (blobs →
tree → commit → ref update — the same thing `git push` does, minus the git
binary). Finally it tags the repo with the `apk-builder` topic, which is how
the home screen recognizes projects later. To the user it's just a loading
spinner followed by the code editor.

**Import from zip.** Instead of the blank template, the user can upload a zip
of an existing project (≤ 50 MB). The zip is extracted in the app's private
cache — build outputs, `.git/`, `.gradle/`, `node_modules/` and keystores are
never pushed — and scanned locally (text only, nothing is ever executed) to
pre-fill the form: app name from `strings.xml`/manifest label, package from
`namespace`/`applicationId`, SDKs from the Gradle config, framework from
config files/extension counts, icon from `res/mipmap-*`. If the zip already
has a `build.yml`, the user chooses to keep or replace it. Unrecognizable
zips are rejected with a clear message instead of a confusing empty form.

**Delete project.** Long-press a card (or its ⋮ menu) → confirm →
`DELETE /repos/{owner}/{repo}`. Confirmation always required; the card only
disappears after GitHub confirms. Deleting needs the `delete_repo` scope —
if the token predates it, the app says so and offers a quick re-sign-in.

**Delete file.** Long-press a file in the editor tree → confirm →
`DELETE /repos/.../contents/{path}` (fetched sha first). If the deleted file
was open, the editor switches to another one. Files the cloud build can't
live without (`build.gradle(.kts)`, `AndroidManifest.xml`, `gradlew`,
`settings.gradle(.kts)`, the wrapper, the workflow) are protected with an
explanation instead of a delete button.

**Build.** Tapping *Build* commits any unsaved editor changes — or, if there
are none, creates an **empty commit** so the workflow's `push` trigger still
fires. The build screen then polls `GET /actions/runs?head_sha=<sha>` until a
run appears (with a `workflow_dispatch` fallback after 45 s in case the push
event was missed) and then polls the run and its job's **real step statuses**
every few seconds. Step names are mapped to friendly labels ("Compiling your
app" ← "Build APK") but the real name is always shown too — nothing is faked.
On success, the APK is fetched through the Artifacts API. On failure, the app
downloads the failed job's log and extracts a few human-readable error lines
(kotlinc `e:` lines, "Execution failed for task …", etc.) instead of dumping
raw logs.

**Sign-in.** Device flow (above). The token is stored in DataStore and attached
to every request by an OkHttp interceptor; it never leaves the device except
in calls to GitHub.

## Why a native code editor

The brief allowed two options: a WebView wrapping a JS editor such as
CodeMirror, or a native Kotlin editor. We chose **native**, for these reasons:

- **Reliability on mobile.** CodeMirror-in-WebView works, but on Android it
  brings a familiar bag of pain: IME composition glitches, scroll jank inside
  a nested scrolling app, viewport/keyboard resize races, and a JS↔Kotlin
  bridge for every file switch. A native editor is just composables — it
  inherits the app's dark theme, insets and accessibility behavior for free.
- **Zero extra payload.** No bundled HTML/JS assets, no WebView process
  (≈30–60 MB of RAM per editor instance), nothing to keep in sync with
  upstream CodeMirror releases.
- **Fully offline & auditable.** The editor is ~500 lines of Kotlin
  (`ui/editor/`), unit-testable in part (the tokenizer has JVM tests), and
  doesn't execute third-party JavaScript on the user's GitHub token's device.
- **The performance is fine for the job.** The syntax highlighter is a single
  regex pass implemented as a Compose `VisualTransformation`; line numbers are
  drawn only for the visible viewport; files this app edits are small. If
  someone eventually needs CodeMirror-class features (multi-cursor, search &
  replace, folding), swapping the `CodeEditorField` composable for a WebView
  is a contained change — the ViewModel doesn't care.

What the editor has today: multi-file project tree, Kotlin/Java + XML syntax
highlighting, line numbers, cursor position display, auto-indent on Enter,
unsaved-changes indicator with crash-safe local persistence, and smart
back-navigation ("save & leave / discard").

## Release signing (optional)

Home → 🔑: create a self-signed keystore on the phone or import one made
elsewhere (`.jks`/`.p12` + passwords + alias). The active keystore is pushed
to every app repo (`signing/`), and the generated workflow adds a release
job: `assembleRelease` + `apksigner` → **signed `app-release` artifact**,
which the build screen downloads automatically. Updates signed with the same
key install over each other. Note: GitHub's secrets API isn't available with
this OAuth token, so signing material lives in the (public) app repo — fine
for hobby apps, use a private repo for serious publishing. Keystore passwords
are encrypted at rest on the phone with a hardware-backed Android Keystore
key.

## AI coding agent (bring your own provider)

The editor has a built-in coding agent (✨ button): you describe a feature or
paste an error, the agent reads the project through the same file APIs and
writes code — **as editable drafts**, exactly like your own edits. Nothing is
pushed to GitHub until you press Save; the agent has no push access at all.

The user brings their own AI provider (OpenRouter, Groq, Google Gemini,
Cerebras or any custom OpenAI-compatible URL). Setup is provider → API key →
model ID; a "load models" button lists the provider's models so the ID can be
picked by tapping. Keys live only in app-private DataStore — no database, no
backend of ours, and requests go straight from the phone to the provider.

**Multi-model teamwork.** You can add 2–4 models and give each a job:

- **Coder** — the main model that runs the tool loop (read/write files),
  with answers **streamed live** into the chat (SSE).
- **Reviewer** — after the coder finishes, this model inspects the changed
  files once; if it finds concrete issues they go back to the coder for a fix
  round (a broken reviewer never loses the coder's work).
- **Fallback** — backups tried automatically, in order, when an earlier
  provider errors out or hits a rate limit (free tiers get exhausted — this
  keeps the agent alive). The chain is sticky: once a provider answers, it
  keeps being used.

**Skills.** Small instruction packs injected into every system prompt:
built-in ones (Compose idioms, small APK, Bengali UI, Kotlin style) can be
toggled, and users can write their own (e.g. "always use dynamic color").

**Build-failure loop.** When a GitHub Actions build fails, the failure digest
can be sent straight into the agent ("AI agent দিয়ে ঠিক করো" button) — it
reads the relevant files, fixes them as drafts, and you rebuild.

How the agent works:

- `data/ai/AiAgent.kt` runs an OpenAI-compatible tool-calling loop
  (`read_file` / `write_file`, max 10 steps per message).
- `data/ai/AgentOrchestrator.kt` adds the reviewer round and
  `FallbackChatApi` provider failover.
- The system prompt includes the project's file tree, package/SDK info, the
  currently open file + selection, and the enabled skills.
- Writes land in the same draft store as manual edits (LocalProjectStore), so
  unsaved agent changes survive crashes and are always user-reviewed.
- Cleartext HTTP is allowed **only** for `localhost`/`127.0.0.1`
  (`network_security_config.xml`); everything else stays HTTPS-only.

## GitHub API usage

Everything is called directly from the app with the user's OAuth token:

| Purpose | Endpoints |
| --- | --- |
| Sign-in (device flow) | `POST github.com/login/device/code`, `POST github.com/login/oauth/access_token` |
| Identity | `GET /user` |
| Home screen project list | `GET /user/repos?type=owner` (filtered client-side by the `apk-builder` topic) |
| Create project | `POST /user/repos`, `PUT /repos/{o}/{r}/topics` |
| Push code (multi-file, one commit) | `POST /repos/{o}/{r}/git/blobs` → `POST …/git/trees` → `POST …/git/commits` → `POST/PATCH …/git/refs` |
| List files / read file | `GET /repos/{o}/{r}/git/trees/{branch}?recursive=1`, `GET /repos/{o}/{r}/contents/{path}` |
| Trigger build | push event (or empty commit), fallback `POST …/actions/workflows/build.yml/dispatches` |
| Build status | `GET /repos/{o}/{r}/actions/runs`, `GET …/runs/{id}`, `GET …/runs/{id}/jobs` |
| Failure summary | `GET /repos/{o}/{r}/actions/jobs/{job_id}/logs` |
| APK | `GET …/runs/{id}/artifacts`, `GET …/actions/artifacts/{id}/zip` |

All repos created by the app are **public**, because GitHub Actions minutes
are free and unlimited only for public repositories — core to the
zero-budget requirement.

## The project template

`app/src/main/assets/templates/kotlin-app/` contains a complete, minimal
"Hello World" **Kotlin + Compose** app that gets pushed to every new project
repo (deliverable #2 of the original brief):

```
.github/workflows/build.yml    CI: JDK 17 (temurin) → ./gradlew assembleDebug → upload APK artifact
.gitignore                     standard Android ignores
README.md                      explains the repo to the user
settings.gradle.kts            rootProject.name = <app name>
build.gradle.kts               AGP 8.5.2 / Kotlin 2.0.21 (pinned, pre-verified combo)
gradle.properties              standard AndroidX flags
gradlew + gradle.bat           Gradle 8.8 wrapper scripts
gradle/wrapper/*               wrapper jar + properties (real jar, committed)
app/build.gradle.kts           namespace/applicationId, minSdk/targetSdk injected
app/src/main/AndroidManifest.xml
app/src/main/java/<package>/MainActivity.kt   Compose "Hello, <app name>!"
app/src/main/res/…             launcher icon (vector by default), strings, theme
```

`TemplateRenderer` (pure Kotlin, unit-tested) substitutes the placeholders —
app name, package name, package path, min/target SDK — and applies
context-sensitive escaping (a name like `Kate's "Cool" App` must survive a
Kotlin string literal *and* an XML resource, which have completely different
escaping rules; XML even requires `\'` for apostrophes). If the user picked a
custom icon, its center-cropped 432×432 PNG replaces the default vector
drawable in the same commit.

The version pinning is deliberate: AGP 8.5.2 + Gradle 8.8 + Kotlin 2.0.21 +
compileSdk 34 is a known-good combination on `ubuntu-latest` runners with JDK
17, so a fresh project's first build succeeds without surprises.

## Workflow generation

**v1 (this codebase):** every Kotlin project ships the static base workflow
shown above — exactly the one from the project brief. No YAML is ever
generated at runtime, so builds are predictable. The only "dynamic" behavior
today is a *client-side detection* pass (`ToolchainDetector`) that runs before
each build and, if it spots extra toolchains, shows an honest note on the
build screen ("this may take longer"). Detection follows the brief's rules —
config files (`Cargo.toml`, `CMakeLists.txt`, `pubspec.yaml`, `package.json`)
rather than bare extensions, with `.py` as the documented exception.

**v2 (designed for, not yet built):** feed the same `ToolchainDetector` result
into a `WorkflowGenerator` that injects the matching steps from this table:

| Detected signal | Extra workflow steps to inject |
| --- | --- |
| `Cargo.toml` / `*.rs` | Rust toolchain (`dtolnay/rust-toolchain`) with Android targets + `cargo-ndk` |
| `CMakeLists.txt`, `*.cpp`/`*.c`/`*.h` | Android NDK via `sdkmanager` |
| `*.py` or Chaquopy in `build.gradle` | `actions/setup-python` |
| `pubspec.yaml` | `subosito/flutter-action` + `flutter build apk` instead of Gradle |
| `package.json` w/ `react-native` | Node.js setup + RN-specific Gradle invocation |

Rules already accounted for in the architecture: regenerate `build.yml` only
when the detected toolchain set actually changes (the last set can be stored
per-project, e.g. in the marker file) to avoid noisy commits.

## Project structure

```
.
├── app/src/main/java/com/enajid/apkbuilder/
│   ├── ApkBuilderApp.kt            Application + DI container
│   ├── MainActivity.kt             single Activity, edge-to-edge
│   ├── data/                       GitHub client & repositories (see above)
│   ├── domain/                     pure, unit-tested logic
│   ├── ui/
│   │   ├── AppNavHost.kt           auth-gated navigation
│   │   ├── onboarding/             welcome + device-flow sign-in
│   │   ├── home/                   project list (from GitHub topics)
│   │   ├── createproject/          new-app form
│   │   ├── editor/                 code editor, file tree, syntax engine
│   │   ├── build/                  build status, APK ready, failure summary
│   │   ├── components/             shared loading/error/empty states
│   │   └── theme/                  Material 3 light/dark schemes
│   └── util/                       zip, MediaStore, QR, intents, formatting
├── app/src/main/assets/templates/kotlin-app/   ← the pushed template
├── app/src/test/                   pure-logic unit tests (JVM)
└── .github/workflows/build.yml     the app builds itself with its own recipe
```

## Building APK Builder itself

**On GitHub (recommended, zero setup):** push the repo, open the Actions tab,
let `Build APK` run, grab the `app-debug` artifact.

**In Android Studio:** open the project (AGP 8.5.2, Gradle 8.8, JDK 17,
compileSdk 34), set your `GITHUB_CLIENT_ID` gradle property
([OAuth setup](#oauth-setup)), and run. To run the unit tests:
`./gradlew test`.

## Security notes

- The OAuth token is stored only on the device (app-private DataStore) and is
  sent only to `github.com`/`api.github.com` over HTTPS.
- Scopes are least-privilege: `public_repo workflow`.
- Projects are public by design (free CI). The app says so explicitly in the
  UI. Don't put secrets in apps you build with it.
- Nothing about the user's account is sent anywhere except to GitHub.

## Roadmap

- **v1.x** — polish: find-in-file, file deletion/rename, run history on the
  home screen, release (signed) builds via repo secrets.
- **v2** — dynamic workflow generation (table above), Java/Flutter/React
  Native templates (the framework picker and `ProjectSpec` are already
  framework-parametric). ~~In-editor AI assistance~~ — shipped as the
  OmniRoute-powered coding agent (see above).
- **Distribution** — GitHub Releases for the app itself, built and signed by
  the same workflow.

## Contributing

Issues and PRs are welcome — this is a small, readable codebase by design.
Please keep the zero-budget constraint and the "no backend ever" rule in
mind when proposing features.

## License

[MIT](LICENSE) © E-najid
