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
net.clojars.savya/beckon {:mvn/version "0.4.2"}
net.clojars.savya/beckon-ffm {:mvn/version "0.4.0"}
```

Leiningen:

```clojure
[net.clojars.savya/beckon "0.4.2"]
[net.clojars.savya/beckon-ffm "0.4.0"]
```

Run the JVM with:

```
-Dbeckon.signal.backend=ffm --enable-native-access=ALL-UNNAMED
```

The platform selects the native mechanism automatically. The beckon API does
not change. See the
[beckon README](https://github.com/jsavyasachi/beckon).

### Closing the native backend

The FFM backend owns a dispatcher thread, native descriptor, and shared native
memory arena. Close it explicitly when the JVM remains running across a REPL
reload or test suite, or before a supervised in-process restart:

```clojure
(require '[beckon-ffm :as beckon-ffm])
(beckon-ffm/close!)
```

`close!` cooperatively stops the dispatcher, restores all signal dispositions
that beckon changed, and releases the native resources. It is safe to call
more than once. A closed backend cannot handle subsequent signals; a fresh JVM
or freshly constructed backend should be used after shutdown.

### Reliable Linux external signals (opt-in)

For Linux service-manager or `kill` delivery, launch the JVM through the
provided pre-launch shim. It blocks the selected signals before the JVM starts,
so every JVM thread inherits the mask:

```sh
clojure -T:build compile-native-shim
target/beckon-signal-launcher --signals TERM,HUP -- \
  java -Xrs --enable-native-access=ALL-UNNAMED \
  -Dbeckon.signal.backend=ffm -jar app.jar
```

The allowlist is explicit and narrow. Supported names are `HUP`, `INT`,
`QUIT`, `TERM`, `USR1`, `USR2`, `CHLD`, `CONT`, `TSTP`, and `WINCH` (subject
to platform availability). `USR2` is reserved by HotSpot and `CHLD` affects
child-process handling; both are rejected by default. The launcher's
`--allow-unsafe-signals` override is intentionally explicit and emits a clear
warning from its failure-policy message; use it only after reviewing the
impact. The Java backend rejects registrations outside the launcher's
allowlist and verifies `/proc/self/status` `SigBlk` at startup.

`-Xrs` is required for `TERM`, `INT`, and `HUP`: it tells HotSpot not to install
its signal handlers for those signals or alter their handling. See HotSpot's
[`-Xrs` option documentation](https://docs.oracle.com/en/java/javase/22/docs/specs/man/java.html#-xrs)
and [signal chaining](https://docs.oracle.com/en/java/javase/22/vm/signal-chaining.html).

This mode is opt-in and does not change the default backend. Without the
shim, Linux `signalfd` remains reliable for beckon's own `raise!`, but external
process-directed signals are not guaranteed to reach the dispatcher because
the JVM may have created threads before beckon loads. The default backend and
existing non-shim behavior are otherwise unchanged.

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
clojure -T:build compile-native-shim
clojure -M:test
clojure -T:build jar
```

## License

Copyright © 2026 Savyasachi.

A companion to [beckon](https://github.com/jsavyasachi/beckon) (originally by Jean Niklas L'orange).
Distributed under the [Eclipse Public License 1.0](https://www.eclipse.org/legal/epl-v10.html).
