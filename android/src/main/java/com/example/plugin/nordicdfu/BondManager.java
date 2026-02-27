package com.example.plugin.nordicdfu;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Removes BLE bonds using the hidden {@code BluetoothDevice.removeBond()} API
 * via reflection. This is the same approach used by the Nordic DFU library
 * ({@code BaseDfuImpl}).
 *
 * This class is intentionally self-contained so it can be moved to another
 * plugin/module later.
 */
public class BondManager {

    private static final String TAG = BondManager.class.getSimpleName();
    private static final long REMOVE_BOND_TIMEOUT_MS = 10_000;

    /**
     * Removes the bond for the given device address. Blocks until
     * {@code ACTION_BOND_STATE_CHANGED} reports {@code BOND_NONE} or the
     * timeout expires.
     *
     * @param context        Application or activity context (for registering
     *                       the broadcast receiver).
     * @param deviceAddress  BLE MAC address (e.g. "AA:BB:CC:DD:EE:FF").
     * @throws Exception     If the adapter is unavailable, reflection fails,
     *                       {@code removeBond()} returns false, or the wait
     *                       times out.
     */
    public static void removeBond(Context context, String deviceAddress) throws Exception {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            throw new IllegalStateException("BluetoothAdapter is not available");
        }
        BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);

        if (device.getBondState() == BluetoothDevice.BOND_NONE) {
            Log.d(TAG, "Device " + deviceAddress + " is not bonded, nothing to remove");
            return;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                BluetoothDevice bondDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (bondDevice != null && bondDevice.getAddress().equals(deviceAddress)) {
                    int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                    if (bondState == BluetoothDevice.BOND_NONE) {
                        latch.countDown();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        context.registerReceiver(receiver, filter);

        try {
            Method removeBond = device.getClass().getMethod("removeBond");
            boolean result = (boolean) removeBond.invoke(device);
            if (!result) {
                throw new RuntimeException("removeBond() returned false for " + deviceAddress);
            }

            Log.d(TAG, "removeBond() called for " + deviceAddress + ", waiting for BOND_NONE");
            if (!latch.await(REMOVE_BOND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Timed out waiting for bond removal on " + deviceAddress);
            }
            Log.d(TAG, "Bond removed for " + deviceAddress);
        } finally {
            context.unregisterReceiver(receiver);
        }
    }
}
