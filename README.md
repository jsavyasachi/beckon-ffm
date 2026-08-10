# beckon-ffm

[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.savya/beckon-ffm.svg)](https://clojars.org/net.clojars.savya/beckon-ffm)
[![cljdoc](https://cljdoc.org/badge/net.clojars.savya/beckon-ffm)](https://cljdoc.org/d/net.clojars.savya/beckon-ffm/CURRENT)
[![test](https://github.com/jsavyasachi/beckon-ffm/actions/workflows/test.yml/badge.svg)](https://github.com/jsavyasachi/beckon-ffm/actions/workflows/test.yml)

Experimental signal backends for [beckon](https://github.com/jsavyasachi/beckon)
use only the Java Foreign Function & Memory API (JDK 22+). Use them as an
alternative to beckon's default `sun.misc.Signal` backend:

- **Linux** - `signalfd(2)`
- **macOS / BSD** - `kqueue(2)` with `EVFILT_SIGNAL`

`sun.misc.Signal` is an internal JDK API. The JDK can remove it. This library
tests a supported replacement. It is **experimental** and ships separately
because it requires JDK 22+. The beckon core jar targets JDK 8.

## Stack

<a href="https://clojure.org"><img src="https://img.shields.io/badge/Clojure-5881D8?style=flat&logo=clojure&logoColor=fff" alt="Clojure" /></a>
<a href="https://openjdk.org/jeps/454"><img src="https://img.shields.io/badge/Java%20FFM-JDK%2022%2B-ED8B00?style=flat&logo=openjdk&logoColor=fff" alt="Java FFM" /></a>
<a href="https://clojure.org/guides/deps_and_cli"><img src="https://img.shields.io/badge/deps.edn-5881D8?style=flat&logo=clojure&logoColor=fff" alt="deps.edn" /></a>
<a href="https://clojure.org/guides/tools_build"><img src="https://img.shields.io/badge/tools.build-5881D8?style=flat&logo=clojure&logoColor=fff" alt="tools.build" /></a>

## Installation

Use [`beckon`](https://github.com/jsavyasachi/beckon) by default. Add
`beckon-ffm` only when you want the experimental Foreign Function & Memory
backend and can run on JDK 22+.

Add both artifacts, then opt in with a system property.

```clojure
net.clojars.savya/beckon {:mvn/version "0.4.1"}
net.clojars.savya/beckon-ffm {:mvn/version "0.1.7"}
```

Leiningen:

```clojure
[net.clojars.savya/beckon "0.4.1"]
[net.clojars.savya/beckon-ffm "0.1.7"]
```

Run the JVM with:

```
-Dbeckon.signal.backend=ffm --enable-native-access=ALL-UNNAMED
```

The platform selects the native mechanism automatically. The beckon API does
not change. See the
[beckon README](https://github.com/jsavyasachi/beckon).

## Capabilities and limits

The two implementations differ:

- **Linux (`signalfd`)** reliably handles beckon's own `raise!`. It does not
  reliably handle signals from *outside* the process (e.g. `kill -HUP`). A JVM
  starts threads before beckon loads. `signalfd` only captures a signal blocked
  in every thread. beckon cannot arrange this after the JVM starts.
- **macOS/BSD (`kqueue`)** sets each managed signal to `SIG_IGN`. This is a
  process-wide disposition. It also observes external signals.

The Linux limitation and JEP 472 native-access restrictions mean this is not a
drop-in replacement. `--enable-native-access` is denied by default from JDK 26.
`sun.misc.Signal` remains beckon's default.

## Compatibility

Requires JDK 22 or later (Foreign Function & Memory API, JEP 454). Linux and
macOS/BSD only. CI compiles and tests on JDK 22.

## Development

Use JDK 22. Compile the two Java FFM backends before running the test suite:

```sh
clojure -T:build compile-java
clojure -M:test
clojure -T:build jar
```

## License

Copyright © 2026 Savyasachi.

A companion to [beckon](https://github.com/jsavyasachi/beckon) (originally by Jean Niklas L'orange).
Distributed under the [Eclipse Public License 1.0](https://www.eclipse.org/legal/epl-v10.html).
