# Changelog

All notable changes to this project are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project adheres to [Semantic Versioning](https://semver.org/).

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
