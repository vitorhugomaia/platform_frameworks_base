/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.net;

import static android.Manifest.permission.CONNECTIVITY_INTERNAL;
import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.READ_NETWORK_USAGE_HISTORY;
import static android.Manifest.permission.SHUTDOWN;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.UID_ALL;
import static android.provider.Settings.Secure.NETSTATS_DETAIL_BUCKET_DURATION;
import static android.provider.Settings.Secure.NETSTATS_DETAIL_MAX_HISTORY;
import static android.provider.Settings.Secure.NETSTATS_PERSIST_THRESHOLD;
import static android.provider.Settings.Secure.NETSTATS_POLL_INTERVAL;
import static android.provider.Settings.Secure.NETSTATS_SUMMARY_BUCKET_DURATION;
import static android.provider.Settings.Secure.NETSTATS_SUMMARY_MAX_HISTORY;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.app.AlarmManager;
import android.app.IAlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.net.IConnectivityManager;
import android.net.INetworkStatsService;
import android.net.NetworkInfo;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.NtpTrustedTime;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TrustedTime;

import com.android.internal.os.AtomicFile;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import libcore.io.IoUtils;

/**
 * Collect and persist detailed network statistics, and provide this data to
 * other system services.
 */
public class NetworkStatsService extends INetworkStatsService.Stub {
    private static final String TAG = "NetworkStats";
    private static final boolean LOGD = true;

    /** File header magic number: "ANET" */
    private static final int FILE_MAGIC = 0x414E4554;
    private static final int VERSION_CURRENT = 1;

    private final Context mContext;
    private final INetworkManagementService mNetworkManager;
    private final IAlarmManager mAlarmManager;
    private final TrustedTime mTime;

    private IConnectivityManager mConnManager;

    // @VisibleForTesting
    public static final String ACTION_NETWORK_STATS_POLL =
            "com.android.server.action.NETWORK_STATS_POLL";

    private PendingIntent mPollIntent;

    // TODO: listen for kernel push events through netd instead of polling

    private static final long KB_IN_BYTES = 1024;
    private static final long MB_IN_BYTES = 1024 * KB_IN_BYTES;
    private static final long GB_IN_BYTES = 1024 * MB_IN_BYTES;

    private LongSecureSetting mPollInterval = new LongSecureSetting(
            NETSTATS_POLL_INTERVAL, 15 * MINUTE_IN_MILLIS);
    private LongSecureSetting mPersistThreshold = new LongSecureSetting(
            NETSTATS_PERSIST_THRESHOLD, 16 * KB_IN_BYTES);

    // TODO: adjust these timings for production builds
    private LongSecureSetting mSummaryBucketDuration = new LongSecureSetting(
            NETSTATS_SUMMARY_BUCKET_DURATION, 1 * HOUR_IN_MILLIS);
    private LongSecureSetting mSummaryMaxHistory = new LongSecureSetting(
            NETSTATS_SUMMARY_MAX_HISTORY, 90 * DAY_IN_MILLIS);
    private LongSecureSetting mDetailBucketDuration = new LongSecureSetting(
            NETSTATS_DETAIL_BUCKET_DURATION, 2 * HOUR_IN_MILLIS);
    private LongSecureSetting mDetailMaxHistory = new LongSecureSetting(
            NETSTATS_DETAIL_MAX_HISTORY, 90 * DAY_IN_MILLIS);

    private static final long TIME_CACHE_MAX_AGE = DAY_IN_MILLIS;

    private final Object mStatsLock = new Object();

    /** Set of active ifaces during this boot. */
    private HashMap<String, InterfaceIdentity> mActiveIface = Maps.newHashMap();

    /** Set of historical stats for known ifaces. */
    private HashMap<InterfaceIdentity, NetworkStatsHistory> mSummaryStats = Maps.newHashMap();
    /** Set of historical stats for known UIDs. */
    private SparseArray<NetworkStatsHistory> mDetailStats = new SparseArray<NetworkStatsHistory>();

    private NetworkStats mLastSummaryPoll;
    private NetworkStats mLastSummaryPersist;

    private NetworkStats mLastDetailPoll;

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private final AtomicFile mSummaryFile;

    // TODO: collect detailed uid stats, storing tag-granularity data until next
    // dropbox, and uid summary for a specific bucket count.

    // TODO: periodically compile statistics and send to dropbox.

    public NetworkStatsService(
            Context context, INetworkManagementService networkManager, IAlarmManager alarmManager) {
        // TODO: move to using cached NtpTrustedTime
        this(context, networkManager, alarmManager, new NtpTrustedTime(), getSystemDir());
    }

    private static File getSystemDir() {
        return new File(Environment.getDataDirectory(), "system");
    }

    public NetworkStatsService(Context context, INetworkManagementService networkManager,
            IAlarmManager alarmManager, TrustedTime time, File systemDir) {
        mContext = checkNotNull(context, "missing Context");
        mNetworkManager = checkNotNull(networkManager, "missing INetworkManagementService");
        mAlarmManager = checkNotNull(alarmManager, "missing IAlarmManager");
        mTime = checkNotNull(time, "missing TrustedTime");

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mSummaryFile = new AtomicFile(new File(systemDir, "netstats.bin"));
    }

    public void bindConnectivityManager(IConnectivityManager connManager) {
        mConnManager = checkNotNull(connManager, "missing IConnectivityManager");
    }

    public void systemReady() {
        synchronized (mStatsLock) {
            // read historical stats from disk
            readStatsLocked();
        }

        // watch for network interfaces to be claimed
        final IntentFilter ifaceFilter = new IntentFilter();
        ifaceFilter.addAction(CONNECTIVITY_ACTION);
        mContext.registerReceiver(mIfaceReceiver, ifaceFilter, CONNECTIVITY_INTERNAL, mHandler);

        // listen for periodic polling events
        // TODO: switch to stronger internal permission
        final IntentFilter pollFilter = new IntentFilter(ACTION_NETWORK_STATS_POLL);
        mContext.registerReceiver(mPollReceiver, pollFilter, READ_NETWORK_USAGE_HISTORY, mHandler);

        // persist stats during clean shutdown
        final IntentFilter shutdownFilter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiver(mShutdownReceiver, shutdownFilter, SHUTDOWN, null);

        try {
            registerPollAlarmLocked();
        } catch (RemoteException e) {
            Slog.w(TAG, "unable to register poll alarm");
        }
    }

    private void shutdownLocked() {
        mContext.unregisterReceiver(mIfaceReceiver);
        mContext.unregisterReceiver(mPollReceiver);
        mContext.unregisterReceiver(mShutdownReceiver);

        writeStatsLocked();
        mSummaryStats.clear();
        mDetailStats.clear();
    }

    /**
     * Clear any existing {@link #ACTION_NETWORK_STATS_POLL} alarms, and
     * reschedule based on current {@link #mPollInterval} value.
     */
    private void registerPollAlarmLocked() throws RemoteException {
        if (mPollIntent != null) {
            mAlarmManager.remove(mPollIntent);
        }

        mPollIntent = PendingIntent.getBroadcast(
                mContext, 0, new Intent(ACTION_NETWORK_STATS_POLL), 0);

        final long currentRealtime = SystemClock.elapsedRealtime();
        mAlarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME, currentRealtime, mPollInterval.get(), mPollIntent);
    }

    @Override
    public NetworkStatsHistory getHistoryForNetwork(int networkTemplate) {
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);

        synchronized (mStatsLock) {
            // combine all interfaces that match template
            final String subscriberId = getActiveSubscriberId();
            final NetworkStatsHistory combined = new NetworkStatsHistory(
                    mSummaryBucketDuration.get());
            for (InterfaceIdentity ident : mSummaryStats.keySet()) {
                final NetworkStatsHistory history = mSummaryStats.get(ident);
                if (ident.matchesTemplate(networkTemplate, subscriberId)) {
                    combined.recordEntireHistory(history);
                }
            }
            return combined;
        }
    }

    @Override
    public NetworkStatsHistory getHistoryForUid(int uid, int networkTemplate) {
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);

        // TODO: return history for requested uid
        return null;
    }

    @Override
    public NetworkStats getSummaryForNetwork(
            long start, long end, int networkTemplate, String subscriberId) {
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);

        synchronized (mStatsLock) {
            long rx = 0;
            long tx = 0;
            long[] networkTotal = new long[2];

            // combine total from all interfaces that match template
            for (InterfaceIdentity ident : mSummaryStats.keySet()) {
                final NetworkStatsHistory history = mSummaryStats.get(ident);
                if (ident.matchesTemplate(networkTemplate, subscriberId)) {
                    networkTotal = history.getTotalData(start, end, networkTotal);
                    rx += networkTotal[0];
                    tx += networkTotal[1];
                }
            }

            final NetworkStats.Builder stats = new NetworkStats.Builder(end - start, 1);
            stats.addEntry(IFACE_ALL, UID_ALL, tx, tx);
            return stats.build();
        }
    }

    @Override
    public NetworkStats getSummaryForAllUid(long start, long end, int networkTemplate) {
        mContext.enforceCallingOrSelfPermission(READ_NETWORK_USAGE_HISTORY, TAG);

        // TODO: apply networktemplate once granular uid stats are stored.

        synchronized (mStatsLock) {
            final int size = mDetailStats.size();
            final NetworkStats.Builder stats = new NetworkStats.Builder(end - start, size);

            long[] total = new long[2];
            for (int i = 0; i < size; i++) {
                final int uid = mDetailStats.keyAt(i);
                final NetworkStatsHistory history = mDetailStats.valueAt(i);
                total = history.getTotalData(start, end, total);
                stats.addEntry(IFACE_ALL, uid, total[0], total[1]);
            }
            return stats.build();
        }
    }

    /**
     * Receiver that watches for {@link IConnectivityManager} to claim network
     * interfaces. Used to associate {@link TelephonyManager#getSubscriberId()}
     * with mobile interfaces.
     */
    private BroadcastReceiver mIfaceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified CONNECTIVITY_INTERNAL
            // permission above.
            synchronized (mStatsLock) {
                updateIfacesLocked();
            }
        }
    };

    private BroadcastReceiver mPollReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified UPDATE_DEVICE_STATS
            // permission above.
            synchronized (mStatsLock) {
                // TODO: acquire wakelock while performing poll
                performPollLocked();
            }
        }
    };

    private BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // verified SHUTDOWN permission above.
            synchronized (mStatsLock) {
                shutdownLocked();
            }
        }
    };

    /**
     * Inspect all current {@link NetworkState} to derive mapping from {@code
     * iface} to {@link NetworkStatsHistory}. When multiple {@link NetworkInfo}
     * are active on a single {@code iface}, they are combined under a single
     * {@link InterfaceIdentity}.
     */
    private void updateIfacesLocked() {
        if (LOGD) Slog.v(TAG, "updateIfacesLocked()");

        // take one last stats snapshot before updating iface mapping. this
        // isn't perfect, since the kernel may already be counting traffic from
        // the updated network.
        // TODO: verify that we only poll summary stats, not uid details
        performPollLocked();

        final NetworkState[] states;
        try {
            states = mConnManager.getAllNetworkState();
        } catch (RemoteException e) {
            Slog.w(TAG, "problem reading network state");
            return;
        }

        // rebuild active interfaces based on connected networks
        mActiveIface.clear();

        for (NetworkState state : states) {
            if (state.networkInfo.isConnected()) {
                // collect networks under their parent interfaces
                final String iface = state.linkProperties.getInterfaceName();
                final InterfaceIdentity ident = findOrCreateInterfaceLocked(iface);
                ident.add(NetworkIdentity.buildNetworkIdentity(mContext, state));
            }
        }
    }

    private void performPollLocked() {
        if (LOGD) Slog.v(TAG, "performPollLocked()");

        // try refreshing time source when stale
        if (mTime.getCacheAge() > TIME_CACHE_MAX_AGE) {
            mTime.forceRefresh();
        }

        // TODO: consider marking "untrusted" times in historical stats
        final long currentTime = mTime.hasCache() ? mTime.currentTimeMillis()
                : System.currentTimeMillis();

        final NetworkStats summary;
        final NetworkStats detail;
        try {
            summary = mNetworkManager.getNetworkStatsSummary();
            detail = mNetworkManager.getNetworkStatsDetail();
        } catch (RemoteException e) {
            Slog.w(TAG, "problem reading network stats");
            return;
        }

        performSummaryPollLocked(summary, currentTime);
        performDetailPollLocked(detail, currentTime);

        // decide if enough has changed to trigger persist
        final NetworkStats persistDelta = computeStatsDelta(mLastSummaryPersist, summary);
        final long persistThreshold = mPersistThreshold.get();
        for (String iface : persistDelta.getUniqueIfaces()) {
            final int index = persistDelta.findIndex(iface, UID_ALL);
            if (persistDelta.rx[index] > persistThreshold
                    || persistDelta.tx[index] > persistThreshold) {
                writeStatsLocked();
                mLastSummaryPersist = summary;
                break;
            }
        }
    }

    /**
     * Update {@link #mSummaryStats} historical usage.
     */
    private void performSummaryPollLocked(NetworkStats summary, long currentTime) {
        final ArrayList<String> unknownIface = Lists.newArrayList();

        final NetworkStats delta = computeStatsDelta(mLastSummaryPoll, summary);
        final long timeStart = currentTime - delta.elapsedRealtime;
        final long maxHistory = mSummaryMaxHistory.get();
        for (String iface : delta.getUniqueIfaces()) {
            final InterfaceIdentity ident = mActiveIface.get(iface);
            if (ident == null) {
                unknownIface.add(iface);
                continue;
            }

            final int index = delta.findIndex(iface, UID_ALL);
            final long rx = delta.rx[index];
            final long tx = delta.tx[index];

            final NetworkStatsHistory history = findOrCreateSummaryLocked(ident);
            history.recordData(timeStart, currentTime, rx, tx);
            history.removeBucketsBefore(currentTime - maxHistory);
        }
        mLastSummaryPoll = summary;

        if (LOGD && unknownIface.size() > 0) {
            Slog.w(TAG, "unknown interfaces " + unknownIface.toString() + ", ignoring those stats");
        }
    }

    /**
     * Update {@link #mDetailStats} historical usage.
     */
    private void performDetailPollLocked(NetworkStats detail, long currentTime) {
        final NetworkStats delta = computeStatsDelta(mLastDetailPoll, detail);
        final long timeStart = currentTime - delta.elapsedRealtime;
        final long maxHistory = mDetailMaxHistory.get();
        for (int uid : delta.getUniqueUids()) {
            final int index = delta.findIndex(IFACE_ALL, uid);
            final long rx = delta.rx[index];
            final long tx = delta.tx[index];

            final NetworkStatsHistory history = findOrCreateDetailLocked(uid);
            history.recordData(timeStart, currentTime, rx, tx);
            history.removeBucketsBefore(currentTime - maxHistory);
        }
        mLastDetailPoll = detail;
    }

    private NetworkStatsHistory findOrCreateSummaryLocked(InterfaceIdentity ident) {
        NetworkStatsHistory stats = mSummaryStats.get(ident);
        if (stats == null) {
            stats = new NetworkStatsHistory(mSummaryBucketDuration.get());
            mSummaryStats.put(ident, stats);
        }
        return stats;
    }

    private NetworkStatsHistory findOrCreateDetailLocked(int uid) {
        NetworkStatsHistory stats = mDetailStats.get(uid);
        if (stats == null) {
            stats = new NetworkStatsHistory(mDetailBucketDuration.get());
            mDetailStats.put(uid, stats);
        }
        return stats;
    }

    private InterfaceIdentity findOrCreateInterfaceLocked(String iface) {
        InterfaceIdentity ident = mActiveIface.get(iface);
        if (ident == null) {
            ident = new InterfaceIdentity();
            mActiveIface.put(iface, ident);
        }
        return ident;
    }

    private void readStatsLocked() {
        if (LOGD) Slog.v(TAG, "readStatsLocked()");

        // clear any existing stats and read from disk
        mSummaryStats.clear();

        FileInputStream fis = null;
        try {
            fis = mSummaryFile.openRead();
            final DataInputStream in = new DataInputStream(fis);

            // verify file magic header intact
            final int magic = in.readInt();
            if (magic != FILE_MAGIC) {
                throw new ProtocolException("unexpected magic: " + magic);
            }

            final int version = in.readInt();
            switch (version) {
                case VERSION_CURRENT: {
                    // file format is pairs of interfaces and stats:
                    // summary := size *(InterfaceIdentity NetworkStatsHistory)

                    final int size = in.readInt();
                    for (int i = 0; i < size; i++) {
                        final InterfaceIdentity ident = new InterfaceIdentity(in);
                        final NetworkStatsHistory history = new NetworkStatsHistory(in);
                        mSummaryStats.put(ident, history);
                    }
                    break;
                }
                default: {
                    throw new ProtocolException("unexpected version: " + version);
                }
            }
        } catch (FileNotFoundException e) {
            // missing stats is okay, probably first boot
        } catch (IOException e) {
            Slog.e(TAG, "problem reading network stats", e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    private void writeStatsLocked() {
        if (LOGD) Slog.v(TAG, "writeStatsLocked()");

        // TODO: consider duplicating stats and releasing lock while writing

        FileOutputStream fos = null;
        try {
            fos = mSummaryFile.startWrite();
            final DataOutputStream out = new DataOutputStream(fos);

            out.writeInt(FILE_MAGIC);
            out.writeInt(VERSION_CURRENT);

            out.writeInt(mSummaryStats.size());
            for (InterfaceIdentity ident : mSummaryStats.keySet()) {
                final NetworkStatsHistory history = mSummaryStats.get(ident);
                ident.writeToStream(out);
                history.writeToStream(out);
            }

            mSummaryFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                mSummaryFile.failWrite(fos);
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        final HashSet<String> argSet = new HashSet<String>();
        for (String arg : args) {
            argSet.add(arg);
        }

        synchronized (mStatsLock) {
            // TODO: remove this testing code, since it corrupts stats
            if (argSet.contains("generate")) {
                generateRandomLocked();
                pw.println("Generated stub stats");
                return;
            }

            if (argSet.contains("poll")) {
                performPollLocked();
                pw.println("Forced poll");
                return;
            }

            pw.println("Active interfaces:");
            for (String iface : mActiveIface.keySet()) {
                final InterfaceIdentity ident = mActiveIface.get(iface);
                pw.print("  iface="); pw.print(iface);
                pw.print(" ident="); pw.println(ident.toString());
            }

            pw.println("Known historical stats:");
            for (InterfaceIdentity ident : mSummaryStats.keySet()) {
                final NetworkStatsHistory stats = mSummaryStats.get(ident);
                pw.print("  ident="); pw.println(ident.toString());
                stats.dump("    ", pw);
            }

            if (argSet.contains("detail")) {
                pw.println("Known detail stats:");
                for (int i = 0; i < mDetailStats.size(); i++) {
                    final int uid = mDetailStats.keyAt(i);
                    final NetworkStatsHistory stats = mDetailStats.valueAt(i);
                    pw.print("  UID="); pw.println(uid);
                    stats.dump("    ", pw);
                }
            }
        }
    }

    /**
     * @deprecated only for temporary testing
     */
    @Deprecated
    private void generateRandomLocked() {
        long end = System.currentTimeMillis();
        long start = end - mSummaryMaxHistory.get();
        long rx = 3 * GB_IN_BYTES;
        long tx = 2 * GB_IN_BYTES;

        mSummaryStats.clear();
        for (InterfaceIdentity ident : mActiveIface.values()) {
            final NetworkStatsHistory stats = findOrCreateSummaryLocked(ident);
            stats.generateRandom(start, end, rx, tx);
        }

        end = System.currentTimeMillis();
        start = end - mDetailMaxHistory.get();
        rx = 500 * MB_IN_BYTES;
        tx = 100 * MB_IN_BYTES;

        mDetailStats.clear();
        for (ApplicationInfo info : mContext.getPackageManager().getInstalledApplications(0)) {
            final int uid = info.uid;
            final NetworkStatsHistory stats = findOrCreateDetailLocked(uid);
            stats.generateRandom(start, end, rx, tx);
        }
    }

    private class LongSecureSetting {
        private String mKey;
        private long mDefaultValue;

        public LongSecureSetting(String key, long defaultValue) {
            mKey = key;
            mDefaultValue = defaultValue;
        }

        public long get() {
            if (mContext != null) {
                return Settings.Secure.getLong(mContext.getContentResolver(), mKey, mDefaultValue);
            } else {
                return mDefaultValue;
            }
        }
    }

    /**
     * Return the delta between two {@link NetworkStats} snapshots, where {@code
     * before} can be {@code null}.
     */
    private static NetworkStats computeStatsDelta(NetworkStats before, NetworkStats current) {
        if (before != null) {
            return current.subtractClamped(before);
        } else {
            return current;
        }
    }

    private String getActiveSubscriberId() {
        final TelephonyManager telephony = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        return telephony.getSubscriberId();
    }

}
