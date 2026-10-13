# Known issues

Concrete, current defects and limits in this **experimental** snapshot. See [FORK_STATUS.md](FORK_STATUS.md) for the overall status. This list is not exhaustive.

## 1. Trophy counter is wrong across the 0–1000 range

- **What happens:** the trophy counter wrongly admits `1014` in a range that should stop at `1000`.
- **Observed:** a value of `14` was seen at prestige 1.
- **Impact:** trophy-based decisions can rest on a wrong reading.
- **Workaround:** none; watch the on-screen trophy value.
- **Status:** confirmed defect, unfixed in this snapshot.

## 2. Solo Showdown EXIT is unreliable

- **What happens:** the bot does not always exit Solo Showdown.
- **Impact:** the bot can be left on the result screen instead of returning to the lobby.
- **Workaround:** exit manually.
- **Status:** confirmed, only partially reliable.

## 3. Movement is only partially reliable

- **What happens:** movement works some of the time but is not consistently reliable.
- **Impact:** the bot may fail to reach or follow its intended path.
- **Workaround:** none.
- **Status:** partial; under improvement.

## 4. Input stop on app closure is improved but not guaranteed

- **What happens:** closing the app stops input as expected in the improved path, but stopping is not guaranteed in every situation.
- **Impact:** input could briefly continue after the app is closed.
- **Workaround:** to stop immediately, turn off the accessibility service for PylaAndroid (see [FORK_STATUS.md](FORK_STATUS.md) → Safety).
- **Status:** improved, not guaranteed.

## 5. Only one device has been tested

- **What happens:** the snapshot has only been tested on a **Samsung Galaxy S26 Ultra**.
- **Not recorded:** the exact game app version and Android version.
- **Requirement:** the game must be set to **English**.
- **Impact:** behaviour on other devices, resolutions and versions is unknown.
- **Status:** coverage limit.

## 6. Coordinate scaling is not proven across all resolutions

- **What happens:** dynamic coordinate scaling is implemented but is not proven universal.
- **Impact:** coordinate templates and OCR bands remain **assumptions**, not universal layout guarantees; taps or reads may be wrong on untested resolutions.
- **Status:** unproven.

## Reporting a new issue

Use the **Bug report** issue form. Include the PylaAndroid version, device model, Android version, game mode and game language, reproduction steps, and expected vs actual behaviour. Screen resolution and a Pyla report ID are optional. Diagnostics details are in [DIAGNOSTICS.md](DIAGNOSTICS.md); sharing a report ID publicly may allow others to read it, so review before posting.
