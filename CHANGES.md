# beckon-ffm changelog

## 0.1.1 (2026-06-14)

* Docs-only release: standardize the README to the canonical skeleton, add the
  cljdoc badge, and unify the status badges and CI workflow name.

## 0.1.0 (2026-06-06)

* Initial release. Foreign Function & Memory (FFM) signal backends for
  [beckon](https://github.com/jsavyasachi/beckon): Linux `signalfd` and
  macOS/BSD `kqueue`. Requires JDK 22+. Depends on beckon 0.4.0, which adds the
  OS-aware backend selector.
