# Hosted archive recovery

The hosted receiver keeps original AAC, selected WAV and journal files in its dedicated private bucket. SQLite holds dates, searchable text, edit history, original IDs, sample boundaries, verified object receipts and monthly transfer counters. The receiver acknowledges a final transfer after validating its data, reading the uploaded bytes back and matching their SHA-256 digest. Verified files free their staging space. All three media types support private byte-range playback through the authenticated origin.

The service creates one catalog snapshot per UTC day when maintenance runs. Two verified snapshots remain in the bucket. Only snapshots with a completion marker can be restored. The snapshot contains catalog data, excludes credentials and incomplete phone uploads, and points back to each original file in the same bucket. An upload in progress can restart from the phone. The catalog and bucket need each other for complete recovery.

## Restore a volume

1. Stop the hosted receiver and preserve the old volume. Mount a fresh volume at the receiver’s usual catalog directory, or choose a new empty directory for the restored catalog.
2. In the service image with access to the same private bucket, run the following command. Replace the destination with the path used for `TOTAL_RECALL_ARCHIVE_HOME`. The destination file must be new.

   ```sh
   python -m cloud_media restore /data/clips.sqlite3
   ```

3. Restart the service with the original bucket bindings and hosted authentication settings. Open an older selected clip and a continuous audio segment, then confirm that the phone can resume a transfer. The restored timeline covers records committed by the snapshot time. Keep the phone’s copies of newer transfers until they have been checked.

The restore command checks the catalog SHA-256, archive identity and SQLite integrity before publishing the destination. It tries the previous verified snapshot if the newest fails validation. Keep the source bucket, including its media and catalog prefixes, intact during recovery. The receiver’s 7-calendar-year expiry is enforced by the catalog and maintenance. A stopped service resumes its cleanup when maintenance runs again.

Configure `TR_S3_ENDPOINT`, `TR_S3_BUCKET`, `TR_S3_REGION`, `TR_S3_KEY_ID` and `TR_S3_SECRET` using private service environment variables. New Railway buckets use virtual-hosted addressing. Set `TR_S3_STYLE=path` if the credentials page for an older bucket explicitly calls for path-style addressing.
