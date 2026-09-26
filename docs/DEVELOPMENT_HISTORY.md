# Development History

Artificial Continuity was developed iteratively before this GitHub repository was created. The original local Git history is preserved in the release artifact `continuity_v0_8_1_history.gitbundle`.

## Preserved lineage

| Version | Original local commit | Milestone |
|---|---|---|
| v0.1.0 | `d3ea3f9` | First runnable Continuity prototype |
| v0.2.0 | `cadbfb7` | Inspectable continuity state and kernel simulation |
| v0.3.0 | `4f13084` | Event history, replay, proposals, policy gates |
| v0.4.0 | `60ad898` | Modular repository, event reducer, policy engine, HIGP |
| v0.5.0 | `a1c3b65` | Cognitive branches, semantic diff, merge conflicts |
| v0.6.0 | `697849d` | Provider-neutral intelligence and context reconstruction |
| v0.7.0 | `2c3f79a` | Trust, consent, prospective memory, signed sync |
| v0.8.0 | `7be1dde` | Interactive Sync Lab and ephemeral demo mode |
| v0.8.1 | `a73a691` | Standalone Live Lab; app/schema version decoupling |

The local history also contains the native v0.8 development commits:

- `1b70845` — establish Git-native CI and release discipline
- `1cf4dc4` — add three-way reconciliation and two-device lab engine
- `7be1dde` — ship the interactive Sync Lab
- `a73a691` — version the standalone Live Lab

## Why the SHAs on GitHub may differ

The repository was created after these iterations already existed. Connector/API-based reconstruction cannot preserve the original author/committer timestamps, so reconstructed remote commits may receive different commit IDs even when their file trees and messages correspond to the preserved originals.

The portable Git bundle remains the canonical evidence of the original commit/tag graph.

## Doctrine

Continuity should preserve its own continuity.

From the GitHub-repository era forward, meaningful changes should be committed natively before release artifacts are produced.
