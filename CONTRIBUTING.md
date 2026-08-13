# Contributing to beckon-ffm

Contributions to improve `beckon-ffm` are welcome. You can send bug reports,
fixes, and focused feature contributions.

## Before you start

- For any change beyond a trivial fix, **open an issue first**. We can agree on
  the approach before you spend time on the change.
- Check existing issues and pull requests to avoid duplicate work.

## Development

This is a Clojure library. You need a JDK and [Leiningen](https://leiningen.org/).
Projects that use `deps.edn` use the Clojure CLI. See the README.

```bash
lein test     # run the test suite
lein check    # AOT-compile; must be free of reflection warnings
```

A mergeable change must meet these requirements:

- **Tests first.** Add or update tests for the behavior you change. For a bug
  fix, add a regression test that fails before your fix and passes after.
- **Green build.** `lein test` must pass. `lein check` must report **zero**
  reflection warnings.
- **No scope creep.** Keep each pull request to one logical change.

## Commits and pull requests

- Follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:` …).
- Keep the subject in the imperative mood and below about 72 characters.
- Update `CHANGELOG.md` when your change is user-visible.
- Rebase on the latest `main` before opening the pull request.

## License

When you contribute, you agree to license your contributions under this
project's license. See `LICENSE` and the README.
