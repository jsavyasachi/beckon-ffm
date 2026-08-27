# Changelog

All notable changes to this project are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project adheres to [Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- Added the opt-in Linux `beckon-signal-launcher`, which pre-blocks an explicit
  signal allowlist before starting the JVM so `signalfd` can reliably receive
  external signals. `USR2` and `CHLD` are rejected by default, and `-Xrs` is
  required for HotSpot-managed termination signals.
- Added Linux subprocess integration tests for launcher and no-launcher modes.

### Fixed

- Linux registration now installs `SIG_IGN` instead of transiently replacing a
  managed signal with `SIG_DFL`, preventing a registration race from bypassing
  beckon and JVM shutdown handling.

## [0.1.8] - 2026-08-17

### Fixed

- Native call return codes (`signal`, `kevent`, `kill`, `sigemptyset`,
  `sigaddset`, `pthread_sigmask`, `pthread_kill`, and the signalfd mask update)
  are checked and throw with the return value and captured errno instead of
  being ignored.

## [0.1.7] - 2026-07-16
### Fixed
- Both signal backends (kqueue on macOS, signalfd on Linux) restore the previously-installed signal disposition on `reset` instead of forcing `SIG_DFL`.

## [0.1.5] - 2026-07-16
### Changed
- Updated the beckon dependency to 0.4.2.

## [0.1.4] - 2026-07-12
### Changed
- Migrated the build, tests, CI, and release workflow to deps.edn and tools.build with JDK 22 Java FFM compilation.

## [0.1.3] - 2026-07-06
### Changed
- Updated the beckon dependency to 0.4.1.
- Bumped outdated dependencies.

## [0.1.2] - 2026-06-26
### Added
- Added tag-triggered Clojars release workflow and environment-credential deploy configuration.

### Changed
- Standardized documentation, badge labels, community health files, and license metadata.

## [0.1.1] - 2026-06-14
### Changed
- Standardized the README structure, cljdoc badge, status badges, and CI workflow name.

## [0.1.0] - 2026-06-06
### Added
- Initial release.
- Added Foreign Function & Memory signal backends for beckon: Linux `signalfd` and macOS/BSD `kqueue`.
- Added dependency on beckon 0.4.0 for the OS-aware backend selector.
