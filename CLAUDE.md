<!--
Project instructions for Claude Code and other coding agents.
Human-facing docs: README.md (setup), ARCHITECTURE.md (decisions), CHANGELOG.md (history).
-->

@ARCHITECTURE.md
@README.md
@CHANGELOG.md

## Agent rules of engagement

- Talk to the user in their language (UA / PL / EN / …). Code, comments, identifiers, commit messages — English only.
- User-facing strings — never hardcoded. `values/strings.xml` (default English) + EVERY `values-<lang>/strings.xml` mirror (uk, ru, es, de, fr, it, pt-rBR, pl, tr, in, fa, ar) in the same commit — `lintRelease` fails on `MissingTranslation`. Plurals via `<plurals>` with each locale's CLDR forms (see ARCHITECTURE.md → Language policy).
- Architecture, hard rules, load-bearing decisions, and build/release conventions live in `ARCHITECTURE.md`. Read before suggesting non-trivial changes.
- Conventional Commits with package scope: `feat(timeline):`, `fix(media):`, `build(beta):`, etc.
- No new `.md` files without an explicit user request. README + CHANGELOG + ARCHITECTURE + SECURITY is the full set.
