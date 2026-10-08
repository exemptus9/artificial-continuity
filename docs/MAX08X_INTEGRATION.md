# Total Recall integration candidate

This review branch brings the existing native test companion onto Continuity 0.20
main (`ea41a077e753f90fb78de8e2e406b5f5af05f052`). It preserves the newer streaming
backup implementation and adds the native deletion ledger to complete backups.
The earlier phone source remains traceable to `19339f861594c12933b07bd3d886e13563620956`.

The import now validates existing deletion ledgers and deduplicates deletion
ranges. Repeated transfers preserve ledger bytes. An invalid ledger stops the
transfer before a capture is persisted. Two regression tests demonstrated both
failures before the fix and pass afterward.

Run `python3 -m unittest discover -s tests -p 'test_*.py' -v` and
`node --test tests/*.test.cjs`. For the native host core, set `JAVA_HOME` to JDK17,
set `RECALL_JSON_JAR` to the verified org.json 20240303 jar, and run
`bash android/tests/run-host-tests.sh`. The new GitHub workflow pins and checks
the jar SHA-256 and installs the pinned transfer dependency before those tests.

The existing protected native fixture follows the real path: explicit consent,
validation, exact source objects and hashes, project binding, owner retrieval,
denied agent access, backup, fresh restoration and deletion-aware retry.
Only synthetic fixtures enter public tests. Imported source text remains inert.

## Release boundary

This is a review candidate. No production deployment or native installation is
implied. The previously supplied 0.7.0-test APK is still the original artifact;
this branch does not relabel it as a newly built binary. Its phone code is
unchanged. Physical Android Keystore, share picker, microphone and lifecycle
checks still require the owner's device. Follow `android/README.md` and the
in-app Test wizard, then retain the exported redacted receipt.

Do not migrate sensitive phone data into the plaintext Continuity working
store. Transfer requires explicit non-sensitive test approval. No automatic
upload, background sync, Supabase service, new network permission, or public
personal archive is introduced.
