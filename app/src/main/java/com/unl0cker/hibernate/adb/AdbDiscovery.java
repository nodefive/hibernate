package com.unl0cker.hibernate.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Finds the wireless debugging port. adbd announces it over mDNS and picks a new port every
 * time wireless debugging is turned on; other phones on the network announce theirs too, so a
 * port only counts if its address is one of this phone's.
 */
public final class AdbDiscovery {

    private static final String SERVICE_TYPE = "_adb-tls-connect._tcp";

    private AdbDiscovery() {
    }

    /** Blocks until the local port is found or the timeout passes. Not on the main thread. */
    public static int findLocalPort(Context context, long timeoutMs) throws Exception {
        return findLocalPort(context, timeoutMs, null);
    }

    /**
     * Same, running afterStart once discovery is listening. adbd announces its port only once,
     * when wireless debugging turns on, and only to a running mDNS daemon (which discovery
     * starts): so turn it on from afterStart, never before.
     */
    public static int findLocalPort(Context context, long timeoutMs, Runnable afterStart)
            throws Exception {
        NsdManager nsd = context.getSystemService(NsdManager.class);
        BlockingQueue<NsdServiceInfo> found = new LinkedBlockingQueue<>();
        CountDownLatch started = new CountDownLatch(1);
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                started.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                started.countDown();
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                found.add(info);
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }
        };

        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<Integer> tried = new HashSet<>();
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        try {
            if (afterStart != null) {
                started.await(5, TimeUnit.SECONDS);
                // Give the freshly started mDNS daemon a moment to accept registrations.
                Thread.sleep(300);
                afterStart.run();
            }
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    throw new TimeoutException("wireless debugging port not found");
                }
                NsdServiceInfo info = found.poll(left, TimeUnit.MILLISECONDS);
                if (info == null) {
                    continue;
                }
                NsdServiceInfo resolved = resolve(nsd, info, Math.min(left, 5000));
                // Other phones on the network announce theirs too; keep only this phone's.
                // No test connection here: adbd confuses a quick connect-and-close with the
                // real connection that follows when both get the same socket number.
                if (resolved != null && resolved.getPort() > 0 && tried.add(resolved.getPort())
                        && isThisPhone(resolved.getHost())) {
                    return resolved.getPort();
                }
            }
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static NsdServiceInfo resolve(NsdManager nsd, NsdServiceInfo info, long timeoutMs)
            throws InterruptedException {
        AtomicReference<NsdServiceInfo> out = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        nsd.resolveService(info, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                latch.countDown();
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                out.set(serviceInfo);
                latch.countDown();
            }
        });
        latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        return out.get();
    }

    /** The announced address is one of this phone's own (unknown counts as ours). */
    private static boolean isThisPhone(InetAddress host) {
        if (host == null || host.isLoopbackAddress()) {
            return true;
        }
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address.equals(host)) {
                        return true;
                    }
                }
            }
        } catch (SocketException ignored) {
            return true;
        }
        return false;
    }
}
