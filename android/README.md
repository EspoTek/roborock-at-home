# Roborock at Home for Android

The native Android app for Roborock at Home: the same robots, schedules, activity and settings as the
web page, talking to the same server through its HTTP API (`src/roborock_at_home/static/API.md`). It
is written in Kotlin with Jetpack Compose and has two modules:

- `core`: plain Kotlin on the JVM. The API client, the live state of the home and the floor-plan
  maths, with no Android in it.
- `app`: the Android app itself, which is only the user interface.

You need JDK 17 and the Android SDK with platform 36. Gradle finds the SDK through `local.properties`
(`sdk.dir=/path/to/Android/sdk`), which Android Studio writes for you. Every command below runs from
this `android` directory.

## A debug build

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app first asks for the server's address, as you would type it into a browser:
`192.168.1.50:8770`, with or without `http://`. Plain http is fine on the home network. To try it
against the demo server on your own computer, start the server with
`uv run roborock-at-home serve --demo --port 8770` from the repository root, which prints the
password for `demo`. An emulator reaches your computer at `10.0.2.2`, so its address there is
`10.0.2.2:8770`.

Debug builds also carry the component gallery, every control of the design system in each of its
states: `adb shell am start -n com.espotek.roborockathome/.GalleryActivity`.

## Tests and screenshots

```sh
./gradlew :core:test                # the client, the store and the map maths, on the JVM
./gradlew :app:testDebugUnitTest    # the app on Robolectric, with no emulator
```

`:core:test` includes tests against the real server in demo mode, which it starts from this
checkout with `uv`; `-PrahRepo=<path>` points it at another one.

`:app:testDebugUnitTest` also draws every screen it tests, light and dark, into `app/build/shots/`
(`signin-dark.png`, `shell-settings-light.png` and so on, at 412 x 915 dp unless the name says
otherwise). Each run empties that folder first, so keep nothing else in it. To draw them again when
nothing has changed since the last run, add `--rerun`. The
screens are drawn from ready-made states of a home in
`app/src/test/kotlin/com/espotek/roborockathome/fixtures/Fixtures.kt`, which are the demo server's
own answers, with the clock held at the moment they were recorded.

## A release build

A release is signed with your own key, which never goes into git. Make one once, if you do not have
one yet:

```sh
keytool -genkeypair -v -keystore roborock-at-home.jks -keyalg RSA -keysize 4096 -validity 10000 -alias roborock-at-home
```

Then put `keystore.properties` next to this README:

```properties
storeFile=/absolute/path/to/roborock-at-home.jks
storePassword=...
keyAlias=roborock-at-home
keyPassword=...
```

A relative `storeFile` is read from the `app` directory, so an absolute path is the safer choice.
Then build an APK to install directly, or an app bundle for Google Play:

```sh
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew :app:bundleRelease        # app/build/outputs/bundle/release/app-release.aab
```

Without `keystore.properties` a release build stops before it starts, so nothing goes out signed
with some other key.
