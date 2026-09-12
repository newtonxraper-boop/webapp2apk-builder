package com.webapp2apk.generated;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Background safety net for the offline queue. MainActivity already tries
 * an immediate flush the moment it detects a connection, for fast visible
 * feedback - but that only works while the app process is alive. This
 * Worker is scheduled by the OS itself with a "network connected"
 * constraint, so it keeps trying - with WorkManager's own exponential
 * backoff - even if the app was killed, the phone rebooted, or Android's
 * battery optimizer suspended the app's own background threads. It's what
 * actually guarantees a queued item eventually gets sent, rather than only
 * "usually does, as long as the app happens to still be open".
 */
public class OfflineQueueWorker extends Worker {

    private static final String UNIQUE_WORK_NAME = "offline_queue_sync";

    public OfflineQueueWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (OfflineQueueSync.getQueueSize(context) == 0) {
            return Result.success();
        }
        OfflineQueueSync.FlushResult result = OfflineQueueSync.flush(context);
        return result.remaining == 0 ? Result.success() : Result.retry();
    }

    /**
     * Enqueues (or replaces) a single background sync job, constrained to
     * run only once the device actually reports network connectivity. Safe
     * to call often and from anywhere (after queueing something, from
     * onPause, on app start) - REPLACE policy means calling this again just
     * restarts the wait, it never stacks up duplicate jobs. A no-op if
     * nothing is actually queued.
     */
    public static void scheduleIfNeeded(Context context) {
        if (OfflineQueueSync.getQueueSize(context) == 0) return;

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OfflineQueueWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();

        WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }
}
