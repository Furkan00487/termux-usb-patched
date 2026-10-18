package com.termux.app.usb;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.JsonWriter;
import android.util.SparseArray;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class TermuxUsbAPI {

    protected static final String LOG_TAG = "TermuxUsbAPI";

    public static final String ACTION_USB_PERMISSION = TermuxConstants.TERMUX_PACKAGE_NAME + ".USB_PERMISSION";
    public static final String ACTION_USB_API = TermuxConstants.TERMUX_PACKAGE_NAME + ".USB_API";

    // PendingIntent.FLAG_MUTABLE (added in API 31)
    private static final int FLAG_MUTABLE_COMPAT = 0x02000000;
    // Context.RECEIVER_NOT_EXPORTED (added in API 33)
    private static final int RECEIVER_NOT_EXPORTED_COMPAT = 0x00000004;

    protected static final SparseArray<UsbDeviceConnection> openDevices = new SparseArray<>();

    public static void onReceive(final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive: " + intent);

        Intent serviceIntent = new Intent(context, UsbService.class);
        serviceIntent.setAction(intent.getAction());
        Bundle extras = intent.getExtras();
        if (extras != null) {
            serviceIntent.putExtras(extras);
        }
        context.startService(serviceIntent);
    }

    /**
     * Bugjaeger tactic: inspect USB interfaces for ADB (FF/42/01), Fastboot (FF/42/03),
     * or Unisoc/Spreadtrum (0x1782) / Lenovo (0x17EF) / MediaTek (0x0E8D) / Qualcomm (0x05C6).
     */
    @NonNull
    public static String getDeviceModeDescription(@NonNull UsbDevice device) {
        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == 255 && intf.getInterfaceSubclass() == 66) {
                if (intf.getInterfaceProtocol() == 1) {
                    return "ADB";
                } else if (intf.getInterfaceProtocol() == 3) {
                    return "Fastboot";
                }
            }
        }
        int vid = device.getVendorId();
        int pid = device.getProductId();
        if (vid == 0x1782) {
            return (pid == 0x4D00) ? "Unisoc Download Mode" : "Unisoc USB";
        } else if (vid == 0x17EF) {
            return "Lenovo USB";
        } else if (vid == 0x0E8D) {
            return "MediaTek USB";
        } else if (vid == 0x05C6) {
            return "Qualcomm USB";
        }
        return "USB OTG";
    }

    /**
     * Bugjaeger tactic: handle USB_DEVICE_ATTACHED intent delivered to TermuxActivity
     * or scan all connected USB OTG devices and immediately request permission.
     */
    public static void handleActivityUsbIntent(@NonNull Context context, @Nullable Intent intent) {
        setupTermuxAmUsbRedirect();
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
                String mode = getDeviceModeDescription(device);
                if (usbManager != null && usbManager.hasPermission(device)) {
                    showToastOnUiThread(context, "USB Ready (" + mode + "): " + device.getDeviceName());
                } else {
                    requestUsbPermissionAsync(context, device, true);
                }
                return;
            }
        }
        scanAndRequestPermissions(context, false);
    }

    /**
     * Scans all connected USB devices and triggers Android's UsbManager permission popup
     * for any device that does not yet have permission.
     */
    public static void scanAndRequestPermissions(@NonNull Context context, boolean showToastWhenEmpty) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return;

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList == null || deviceList.isEmpty()) {
            if (showToastWhenEmpty) {
                showToastOnUiThread(context, "No USB OTG device detected.");
            }
            return;
        }

        for (UsbDevice device : deviceList.values()) {
            String mode = getDeviceModeDescription(device);
            if (!usbManager.hasPermission(device)) {
                Logger.logInfo(LOG_TAG, "Auto-requesting USB permission for " + device.getDeviceName() + " (" + mode + ")");
                requestUsbPermissionAsync(context, device, true);
            } else if (showToastWhenEmpty) {
                showToastOnUiThread(context, "USB Permitted (" + mode + "): " + device.getDeviceName());
            }
        }
    }

    public static void requestUsbPermissionAsync(@NonNull Context context, @NonNull UsbDevice device, boolean showToast) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return;

        Intent usbIntent = new Intent(ACTION_USB_PERMISSION);
        usbIntent.setPackage(context.getPackageName());
        usbIntent.putExtra("show_toast", showToast);
        int flags = Build.VERSION.SDK_INT >= 31 ? FLAG_MUTABLE_COMPAT : 0;
        PendingIntent permissionIntent = PendingIntent.getBroadcast(context.getApplicationContext(), 0, usbIntent, flags);
        usbManager.requestPermission(device, permissionIntent);
    }

    public static void showToastOnUiThread(@NonNull Context context, @NonNull String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show());
    }

    /**
     * Automatically patches $PREFIX/bin/am so that `termux-usb`, `termux-adb`, and `termux-fastboot`
     * route `Usb` broadcasts directly to `com.termux/.app.usb.TermuxUsbReceiver` on Android 14+
     * without requiring a separate Termux:API APK.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void setupTermuxAmUsbRedirect() {
        try {
            java.io.File amFile = new java.io.File("/data/data/com.termux/files/usr/bin/am");
            if (!amFile.exists() || !amFile.canRead() || !amFile.canWrite()) return;

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream fis = new java.io.FileInputStream(amFile)) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = fis.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
            }
            String content = new String(baos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            if (content.contains("com.termux/.app.usb.TermuxUsbReceiver")) {
                return;
            }

            String target = "exec /system/bin/app_process";
            int idx = content.indexOf(target);
            if (idx == -1) return;

            String hook = "# Built-in Termux USB Host redirect for termux-usb / termux-adb\n" +
                    "case \"$*\" in\n" +
                    "    *\"com.termux.api/.TermuxApiReceiver\"*\"Usb\"*)\n" +
                    "        for _arg in \"$@\"; do\n" +
                    "            if [ \"$_arg\" = \"com.termux.api/.TermuxApiReceiver\" ]; then\n" +
                    "                set -- \"$@\" \"com.termux/.app.usb.TermuxUsbReceiver\"\n" +
                    "            else\n" +
                    "                set -- \"$@\" \"$_arg\"\n" +
                    "            fi\n" +
                    "            shift\n" +
                    "        done\n" +
                    "        ;;\n" +
                    "esac\n\n";

            String updated = content.substring(0, idx) + hook + content.substring(idx);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(amFile, false)) {
                fos.write(updated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            amFile.setExecutable(true, false);
            Logger.logInfo(LOG_TAG, "Patched $PREFIX/bin/am with built-in TermuxUsbReceiver redirect");
        } catch (Exception e) {
            Logger.logVerbose(LOG_TAG, "Failed to patch $PREFIX/bin/am: " + e.getMessage());
        }
    }

    private static void registerReceiverCompat(@NonNull Context context,
                                               @NonNull BroadcastReceiver receiver,
                                               @NonNull IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Method m = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class, int.class);
                m.invoke(context, receiver, filter, RECEIVER_NOT_EXPORTED_COMPAT);
                return;
            } catch (Exception ignored) {
            }
        }
        context.registerReceiver(receiver, filter);
    }

    public static class UsbService extends Service {

        protected static final String LOG_TAG = "UsbService";

        private final ThreadPoolExecutor mThreadPoolExecutor;

        public UsbService() {
            super();
            mThreadPoolExecutor = new ThreadPoolExecutor(1, 1,
                    0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>());
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent == null) return Service.START_NOT_STICKY;

            String action = intent.getAction();
            if (action == null || ACTION_USB_API.equals(action)) {
                action = intent.getStringExtra("action");
            }
            if (action == null) {
                action = "list";
            }

            switch (action) {
                case "list":
                    runListAction(intent);
                    break;
                case "status":
                case "info":
                    runStatusAction(intent);
                    break;
                case "permission":
                    runPermissionAction(intent);
                    break;
                case "open":
                    runOpenAction(intent);
                    break;
                default:
                    final String invalidAction = action;
                    TermuxUsbResultReturner.returnData(this, intent, out ->
                            out.append("Invalid action: \"").append(invalidAction).append("\"\n"));
                    break;
            }

            return Service.START_NOT_STICKY;
        }

        protected void runListAction(Intent intent) {
            TermuxUsbResultReturner.returnData(this, intent, new TermuxUsbResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    listDevices(out);
                }
            });
        }

        protected void listDevices(JsonWriter out) throws IOException {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> deviceList = usbManager != null ? usbManager.getDeviceList() : new HashMap<>();
            out.beginArray();
            for (String deviceName : deviceList.keySet()) {
                out.value(deviceName);
            }
            out.endArray();
        }

        protected void runStatusAction(Intent intent) {
            TermuxUsbResultReturner.returnData(this, intent, new TermuxUsbResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
                    HashMap<String, UsbDevice> deviceList = usbManager != null ? usbManager.getDeviceList() : new HashMap<>();
                    out.beginArray();
                    for (Map.Entry<String, UsbDevice> entry : deviceList.entrySet()) {
                        UsbDevice dev = entry.getValue();
                        out.beginObject();
                        out.name("device").value(entry.getKey());
                        out.name("vendorId").value(String.format("0x%04x", dev.getVendorId()));
                        out.name("productId").value(String.format("0x%04x", dev.getProductId()));
                        out.name("mode").value(getDeviceModeDescription(dev));
                        out.name("hasPermission").value(usbManager.hasPermission(dev));
                        out.endObject();
                    }
                    out.endArray();
                }
            });
        }

        protected void runPermissionAction(Intent intent) {
            mThreadPoolExecutor.submit(() -> {
                UsbDevice device = getDevice(intent);
                if (device == null) return;

                int status = checkAndRequestUsbDevicePermission(intent, device);
                TermuxUsbResultReturner.returnData(this, intent, out -> {
                    if (status == 0) {
                        out.append("yes\n");
                    } else if (status == 1) {
                        out.append("no\n");
                    } else if (status == -1) {
                        out.append("Permission request timeout.\n");
                    }
                });
            });
        }

        protected void runOpenAction(Intent intent) {
            mThreadPoolExecutor.submit(() -> {
                UsbDevice device = getDevice(intent);
                if (device == null) return;

                int status = checkAndRequestUsbDevicePermission(intent, device);
                TermuxUsbResultReturner.returnData(this, intent, new TermuxUsbResultReturner.WithAncillaryFd() {
                    @Override
                    public void writeResult(PrintWriter out) {
                        if (status == 0) {
                            int fd = open(device);
                            if (fd < 0) {
                                out.append("Open device failed.\n");
                            } else {
                                this.sendFd(out, fd);
                            }
                        } else if (status == 1) {
                            out.append("Permission denied.\n");
                        } else if (status == -1) {
                            out.append("Permission request timeout.\n");
                        }
                    }
                });
            });
        }

        protected int open(@NonNull UsbDevice device) {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (usbManager == null) return -2;

            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) return -2;

            int fd = connection.getFileDescriptor();
            if (fd == -1) {
                connection.close();
                return -1;
            }

            openDevices.put(fd, connection);
            return fd;
        }

        @Nullable
        protected UsbDevice getDevice(Intent intent) {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (usbManager == null) return null;

            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            String deviceName = intent.getStringExtra("device");
            String vendorIdStr = intent.getStringExtra("vendorId");
            String productIdStr = intent.getStringExtra("productId");

            UsbDevice device = null;
            if (deviceName != null && !deviceName.isEmpty() && !"auto".equalsIgnoreCase(deviceName)) {
                device = deviceList.get(deviceName);
            } else if (vendorIdStr != null && productIdStr != null) {
                try {
                    int vid = Integer.decode(vendorIdStr);
                    int pid = Integer.decode(productIdStr);
                    for (UsbDevice d : deviceList.values()) {
                        if (d.getVendorId() == vid && d.getProductId() == pid) {
                            device = d;
                            break;
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            } else if (!deviceList.isEmpty()) {
                // Auto-select first connected USB device
                device = deviceList.values().iterator().next();
            }

            if (device == null) {
                TermuxUsbResultReturner.returnData(this, intent, out -> out.append("No such device.\n"));
            }

            return device;
        }

        protected boolean checkUsbDevicePermission(@NonNull UsbDevice device) {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            return usbManager != null && usbManager.hasPermission(device);
        }

        protected int checkAndRequestUsbDevicePermission(Intent intent, @NonNull UsbDevice device) {
            if (checkUsbDevicePermission(device)) {
                return 0;
            }

            // Default to requesting permission if "request" is true or not explicitly set to false
            if (!intent.getBooleanExtra("request", true)) {
                return 1;
            }

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Boolean> result = new AtomicReference<>();

            BroadcastReceiver usbReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent usbIntent) {
                    if (ACTION_USB_PERMISSION.equals(usbIntent.getAction())) {
                        boolean requestResult = usbIntent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                        result.set(requestResult);
                    }
                    try {
                        context.unregisterReceiver(this);
                    } catch (Exception ignored) {
                    }
                    latch.countDown();
                }
            };

            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (usbManager == null) return 1;

            Intent usbIntent = new Intent(ACTION_USB_PERMISSION);
            usbIntent.setPackage(getPackageName());

            int pendingIntentFlags = Build.VERSION.SDK_INT >= 31 ? FLAG_MUTABLE_COMPAT : 0;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, usbIntent, pendingIntentFlags);

            try {
                registerReceiverCompat(this, usbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
                usbManager.requestPermission(device, permissionIntent);

                if (!latch.await(30L, TimeUnit.SECONDS)) {
                    return -1;
                }

                Boolean requestResult = result.get();
                if (requestResult != null) {
                    usbReceiver = null;
                    return requestResult ? 0 : 1;
                } else {
                    return checkUsbDevicePermission(device) ? 0 : 1;
                }
            } catch (InterruptedException e) {
                return 1;
            } finally {
                if (usbReceiver != null) {
                    try {
                        unregisterReceiver(usbReceiver);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }
}
