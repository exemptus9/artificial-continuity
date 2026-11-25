# Your hosted Total Recall archive

Open the archive website, enter the **Owner key** from your private access file, and choose **Open private archive**. The owner key controls browser access. **Pair phone → Reveal pairing code** displays a separate upload credential. Copy it on the phone or scan the QR on a different screen. Keep both credentials private. [Archive website](https://total-recall-archive-production.up.railway.app)

On Android, open **Archive & sync → Connect website or Mint**, paste the pairing code, then tap **Pair archive → Connect website**. For full sessions, choose **Set up continuous backup**, review the destination and limits, choose whether to free acknowledged phone copies, and confirm **Enable continuous backup**. For a manual catch-up, **Preview all pending → Queue this backlog** approves the displayed items once, up to 1 GiB and 4,096 items. Under **Transfer choices**, you can change the content, network and regular batch limits. The website remains available while the Mint laptop is asleep. [Complete phone instructions](../android/SELECTIVE_LISTENING.md)

## Capacity and cost

The hosted app sets a 1 TB media allowance (1,000 GB). It tracks upload and playback separately with a 25 GB allowance for each per UTC calendar month. A transfer can also wait for staging space. The phone shows pending items and keeps its copy until it verifies a receipt. **Check server space** shows current capacity.

The owner's monthly hosting budget is $15–30. Railway bills bucket storage at $0.015 per GB-month of actual usage. Holding a full 1,000 GB for a whole billing month would cost about $15 for bucket storage alone. An empty allowance costs no bucket storage. Bucket ingress and egress are free. Public **service** egress costs $0.05/GB, including data the service sends to the bucket and playback streamed from the service. Compute, the persistent volume and other workspace usage also contribute to the bill. Check actual spend and billing alerts in Railway. [Bucket billing](https://docs.railway.com/storage-buckets/billing) · [Service resource prices](https://docs.railway.com/pricing/plans)

Seven years is the retention target for accepted captures. At 32 kbps, seven years of continuous AAC is about 884 GB before overhead. That fits within 1,000 GB on paper. Container overhead, additional WAV highlights, and other items use part of the remaining space. **Check server space** shows the actual remaining capacity.

## Receipts and recovery

Each completed phone transfer requires a matching item ID, byte count and SHA-256 from the server. On the hosted service, a persistent volume holds the catalog and transfer staging, and a private object bucket stores media. An interrupted upload can resume. On the phone, automatic reclamation of acknowledged full-audio segments is an independent setting under **Full audio & phone storage**.

While the host is running, archive maintenance checks expiry hourly and creates a daily catalog snapshot in the bucket. It keeps the two newest verified daily snapshots. A recent upload may be newer than the last snapshot. Snapshot recovery requires access to the same bucket and its media objects. A separately retained export can also preserve an important recording.

If the catalog volume is lost, stop the service and restore the most recent verified database to a new volume while keeping the media bucket. The recovery command checks the stored hash, archive identity and SQLite integrity, then tries the previous snapshot if needed. Sign in and play a known recording before resuming phone transfers. [Operator recovery instructions](CLOUD_RECOVERY.md)
