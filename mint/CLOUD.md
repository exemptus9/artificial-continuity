# Your hosted Total Recall archive

Open the archive website, enter the **Owner key** from your private access file, and choose **Open private archive**. The owner key controls browser access. **Pair phone → Reveal pairing code** displays a separate upload credential. Copy it on the phone or scan the QR on a different screen. Keep both credentials private. [Archive website](https://total-recall-archive-production.up.railway.app)

On Android, open **Archive & sync → Connect website or Mint**, paste the pairing code, then tap **Pair archive → Connect website**. In **Transfer choices**, select the media to send. **Preview transfer → Queue this batch** sends one approved batch. **Set up automatic transfers → Enable automatic transfers** permits recurring batches with the limits shown. The website remains available while the Mint laptop is asleep. [Complete phone instructions](../android/SELECTIVE_LISTENING.md)

## Capacity and cost

The hosted app sets a 350 GB media allowance. It tracks upload and playback separately with a 25 GB allowance for each per UTC calendar month. A transfer can also wait for staging space. The phone shows pending items and keeps its copy until it verifies a receipt. **Check server space** shows current capacity.

The owner's monthly hosting budget is $15–30. These app limits control bytes. Railway meters the service and storage separately, so check actual spend and alerts in Railway Billing. Seven years is the retention target for accepted captures. At 32 kbps, 24-hour listening for seven years would produce about 884 GB of AAC before overhead. Shorter listening sessions and selective clips use less space. The 350 GB allowance can fill before an item's seventh anniversary.

## Receipts and recovery

Each completed phone transfer requires a matching item ID, byte count and SHA-256 from the server. On the hosted service, a persistent volume holds the catalog and transfer staging, and a private object bucket stores media. An interrupted upload can resume. On the phone, automatic reclamation of acknowledged full-audio segments is an independent setting under **Full audio & phone storage**.

While the host is running, archive maintenance checks expiry hourly and creates a daily catalog snapshot in the bucket. It keeps the two newest verified daily snapshots. A recent upload may be newer than the last snapshot. Snapshot recovery requires access to the same bucket and its media objects. A separately retained export can also preserve an important recording.

For an operator restoring after loss of the catalog volume, stop the web service and start an isolated recovery container from the same image with the archive's bucket configuration. Mount a recovery volume at `/data`. Run the following from `/app` to write a **new** database file. The helper checks the stored hash, archive identity and SQLite integrity and tries the older verified snapshot if needed.

```sh
python - <<'PY'
from pathlib import Path
from cloud_media import CloudMedia
import recall_desk

target = Path("/data/recovery/clips.sqlite3")
CloudMedia(recall_desk.archive()).restore_latest(target)
print(target)
PY
```

Preserve the old volume. Use a copy of the restored database as `clips.sqlite3` at the root of the replacement volume. Point the service at that volume and the original bucket. After signing in, verify a known item's timestamp and play its original audio before resuming phone transfers. Keep any unsent or recently acknowledged phone copies until you have checked the recovery.
