# beckon-ffm

[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.savya/beckon-ffm.svg)](https://clojars.org/net.clojars.savya/beckon-ffm)
[![ci](https://github.com/jsavyasachi/beckon-ffm/actions/workflows/ci.yml/badge.svg)](https://github.com/jsavyasachi/beckon-ffm/actions/workflows/ci.yml)

Experimental signal backends for [beckon](https://github.com/jsavyasachi/beckon)
built entirely on the Java Foreign Function & Memory API (JDK 22+), as an
alternative to beckon's default `sun.misc.Signal` backend:

- **Linux** - `signalfd(2)`
- **macOS / BSD** - `kqueue(2)` with `EVFILT_SIGNAL`

It exists because `sun.misc.Signal` is an internal JDK API that may eventually be
removed; this proves out the supported modern replacement. It is **experimental**
and shipped separately precisely because it needs JDK 22+, while beckon's core
jar targets JDK 8.

## Usage

Add both beckon and beckon-ffm, then opt in with a system property.

```clj
[net.clojars.savya/beckon "0.4.0"]
[net.clojars.savya/beckon-ffm "0.1.0"]
```

Run the JVM with:

```
-Dbeckon.signal.backend=ffm --enable-native-access=ALL-UNNAMED
```

The right native mechanism is selected automatically for the platform. The
beckon API is unchanged - see the beckon README.

## Capabilities and limits

The two implementations differ, which is instructive:

- **Linux (`signalfd`)** reliably handles beckon's own `raise!`, but not signals
  from *outside* the process (e.g. `kill -HUP`): a JVM starts threads before
  beckon loads, and `signalfd` only captures a signal blocked in every thread,
  which cannot be arranged retroactively.
- **macOS/BSD (`kqueue`)** sets each managed signal to `SIG_IGN` - a process-wide
  disposition - so it also observes external signals.

Because of the Linux limitation and JEP 472 native-access restrictions
(`--enable-native-access`, denied by default from JDK 26), this is not a drop-in
replacement; `sun.misc.Signal` remains beckon's default.

## Compatibility

Requires JDK 22 or later (Foreign Function & Memory API, JEP 454). Linux and
macOS/BSD only. Continuously tested on JDK 25 across Ubuntu and macOS.

## License

Copyright © 2026 Savyasachi.

A companion to beckon (originally by Jean Niklas L'orange). Distributed under the
Eclipse Public License, the same as Clojure.
