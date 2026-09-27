# UX feedback captured — 2026-09-26

## Repeated form entry

**Observed problem (user-originated):** Many related input boxes make entering an intention burdensome, even when an AI already has enough context to draft the entries.

**Desired behavior (user-originated):** Let the assistant expedite filling those fields.

**Implementation developed in v0.14:** One-box intake; selected-field form fill; request-bound AI reply preview. Accepted fields remain explicit, and original source text remains separate from interpretation.

**Principle:** Do not make the user retranscribe context the system can preserve or carry across interfaces.

## Embedded-browser context loss

**Observed problem (user-originated):** Opening the workspace from ChatGPT puts it inside ChatGPT's browser, making it awkward to return to the conversation, edit text and collect links.

**Implementation developed in v0.14:** A clean app-address helper, best-effort user-activated Chrome opening, installation guidance, optional source URLs, and copy/share/download analysis handoffs. Profile-local data portability is disclosed before moving browsers.

**Principle:** A tool should not hide the source context needed to use it.

These notes establish a design provenance record, not an exclusive invention or legal ownership claim. No private project contents were included.
