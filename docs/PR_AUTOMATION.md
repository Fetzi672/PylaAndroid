# Pull request automation

Path-based labelling and label syncing for pull requests. Two workflows plus two
configuration files:

- `.github/labeler.yml` — maps changed paths to labels: areas `area:engine`,
  `area:input`, `area:vision`, `area:overlay`, `area:diagnostics`,
  `area:interpreter`, plus `tests`, `documentation` and `ci`.
- **PR labeler workflow** — runs on `pull_request_target` using only pull-request
  metadata. It never checks out or executes pull-request code, and it uses
  `actions/labeler` pinned to a reviewed immutable commit (v7). `sync-labels` is
  false, so labels removed from the config and labels applied by hand survive
  relabelling — manual and stale labels are preserved.
- `.github/labels.yml` — definitions for the **new** labels only; labels that already
  exist in the repository are retained unchanged.
- **Sync workflow** — `EndBug/label-sync` pinned to a reviewed commit (v2) with
  `delete-other-labels` false, so repository labels missing from the config are never
  deleted. It triggers on pushes to the default branch limited to the relevant
  config paths, and on `workflow_dispatch` restricted to the default ref. The config
  is fetched at `github.sha` without a checkout.

## What these labels mean

- **Labels are not approvals.** A path label says which areas a pull request touches.
  It says nothing about correctness, test coverage or review status. Review, CI and
  human approval decide mergeability.
- **Labels are not a gate.** The workflows write label metadata only: they add no
  required status check, block no merge, and never merge a pull request. `AGENTS.md`
  supplies review guidance, not enforcement. **Tests, lint and debug build** is the
  Android check; making it required needs separate branch protection (see `docs/CI.md`).
- **Set labels up before using them.** Every label referenced by `.github/labeler.yml`
  must already exist in the repository: `.github/labels.yml` adds the new ones and
  existing labels such as `documentation` are kept. Apply the definitions before the
  labeler first runs on a pull request; missing definitions can fail the action.
- **Activation requires merging the introducing pull request.** Until the workflows
  exist on the default branch, `pull_request_target` cannot use the new labeler and
  the sync workflow is not available for normal manual dispatch. A local invocation
  of the pinned actions can verify real API behaviour before merge, but is not a
  hosted workflow run. `AGENTS.md` does not enable automatic Codex reviews; configure
  the connector's repository review setting separately if automatic reviews are wanted.
- **Pins and minimum permissions.** Actions are pinned to immutable commit SHAs
  (labeler v7, label-sync v2); update pins deliberately, never to a floating tag.
  Each workflow declares only what it needs — `pull-requests: write` to apply labels,
  `issues: write` to create or update labels, `contents: read` otherwise. No workflow
  needs write access to repository contents, and none exposes secrets to
  pull-request context.
- **Branches stay yours.** The automation never pushes to contributor branches, never
  edits branch protection and never auto-merges. Authors keep ownership of their own
  branch throughout review.
