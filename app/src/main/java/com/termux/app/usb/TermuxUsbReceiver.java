package com.termux.app.usb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.termux.shared.logger.Logger;

public class TermuxUsbReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "TermuxUsbReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        TermuxUsbResultReturner.context = context.getApplicationContext();

        String action = intent.getAction();
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            Logger.logInfo(LOG_TAG, "USB device attached via BroadcastReceiver");
            TermuxUsbAPI.handleActivityUsbIntent(context, intent);
            return;
        }

        if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                Logger.logInfo(LOG_TAG, "USB device detached: " + device.getDeviceName());
            }
            return;
        }

        if (TermuxUsbAPI.ACTION_USB_PERMISSION.equals(action)) {
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra("show_toast", false) && device != null) {
                String mode = TermuxUsbAPI.getDeviceModeDescription(device);
                String statusMsg = granted
                        ? "USB Permission Granted (" + mode + "): " + device.getDeviceName()
                        : "USB Permission Denied: " + device.getDeviceName();
                TermuxUsbAPI.showToastOnUiThread(context, statusMsg);
            }
            return;
        }

        String apiMethod = intent.getStringExtra("api_method");
        if ("Usb".equalsIgnoreCase(apiMethod) || TermuxUsbAPI.ACTION_USB_API.equals(action)
                || "list".equals(action) || "permission".equals(action)
                || "open".equals(action) || "status".equals(action)) {
            TermuxUsbAPI.onReceive(context, intent);
        }
    }
}
