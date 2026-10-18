package com.termux.app.usb;

import android.app.Application;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import com.termux.shared.logger.Logger;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listens on abstract UNIX sockets ("com.termux.api://listen" and "com.termux.usb://listen")
 * so that `termux-usb`, `termux-adb`, and `termux-fastboot` can request USB permissions
 * and receive raw USB file descriptors directly from the main Termux app.
 */
public class TermuxUsbSocketListener {

    public static final String LISTEN_ADDRESS_API = "com.termux.api://listen";
    public static final String LISTEN_ADDRESS_USB = "com.termux.usb://listen";

    private static final Pattern EXTRA_STRING = Pattern.compile("(-e|--es|--esa) +([^ ]+) +\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
    private static final Pattern EXTRA_BOOLEAN = Pattern.compile("--ez +([^ ]+) +([^ ]+)");
    private static final Pattern EXTRA_INT = Pattern.compile("--ei +([^ ]+) +(-?[0-9]+)");
    private static final Pattern ACTION = Pattern.compile("-a *([^ ]+)");

    private static Thread apiListenerThread = null;
    private static Thread usbListenerThread = null;

    private static final String LOG_TAG = "TermuxUsbSocketListener";

    public static synchronized void createSocketListener(Application app) {
        TermuxUsbResultReturner.context = app.getApplicationContext();
        if (apiListenerThread == null) {
            apiListenerThread = startListenerForAddress(app, LISTEN_ADDRESS_API);
        }
        if (usbListenerThread == null) {
            usbListenerThread = startListenerForAddress(app, LISTEN_ADDRESS_USB);
        }
    }

    private static Thread startListenerForAddress(final Application app, final String socketAddress) {
        Thread thread = new Thread(() -> {
            try (LocalServerSocket listen = new LocalServerSocket(socketAddress)) {
                Logger.logInfo(LOG_TAG, "Listening for USB API connections on abstract socket: " + socketAddress);
                while (true) {
                    try (LocalSocket con = listen.accept();
                         DataInputStream in = new DataInputStream(con.getInputStream());
                         BufferedWriter out = new BufferedWriter(new OutputStreamWriter(con.getOutputStream()))) {

                        if (con.getPeerCredentials().getUid() != app.getApplicationInfo().uid) {
                            continue;
                        }

                        try {
                            int length = in.readUnsignedShort();
                            byte[] b = new byte[length];
                            in.readFully(b);
                            String cmdline = new String(b, StandardCharsets.UTF_8);

                            Intent intent = new Intent(app.getApplicationContext(), TermuxUsbReceiver.class);
                            HashMap<String, String> stringExtras = new HashMap<>();
                            HashMap<String, String[]> stringArrayExtras = new HashMap<>();
                            HashMap<String, Boolean> booleanExtras = new HashMap<>();
                            HashMap<String, Integer> intExtras = new HashMap<>();
                            boolean err = false;

                            Matcher m = EXTRA_STRING.matcher(cmdline);
                            while (m.find()) {
                                String option = m.group(1);
                                if ("-e".equals(option) || "--es".equals(option)) {
                                    stringExtras.put(m.group(2), Objects.requireNonNull(m.group(3)).replaceAll("\\\\\"", "\""));
                                } else {
                                    String[] list = Objects.requireNonNull(m.group(3)).split("(?<!\\\\),");
                                    for (int i = 0; i < list.length; i++) {
                                        list[i] = list[i].replaceFirst("\\\\,", ",");
                                    }
                                    stringArrayExtras.put(m.group(2), list);
                                }
                            }
                            cmdline = m.replaceAll("");

                            m = EXTRA_BOOLEAN.matcher(cmdline);
                            while (m.find()) {
                                String value = m.group(2);
                                value = value != null ? value.toLowerCase() : null;
                                Boolean arg = null;
                                if ("true".equals(value) || "t".equals(value)) {
                                    arg = true;
                                } else if ("false".equals(value) || "f".equals(value)) {
                                    arg = false;
                                } else {
                                    try {
                                        if (value != null) arg = Integer.decode(value) != 0;
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                                if (arg == null) {
                                    out.write("Invalid boolean extra: " + m.group(0) + "\n");
                                    err = true;
                                    break;
                                }
                                booleanExtras.put(m.group(1), arg);
                            }
                            cmdline = m.replaceAll("");

                            m = EXTRA_INT.matcher(cmdline);
                            while (m.find()) {
                                try {
                                    intExtras.put(m.group(1), Integer.decode(Objects.requireNonNull(m.group(2))));
                                } catch (NumberFormatException e) {
                                    out.write("Invalid integer extra: " + m.group(0) + "\n");
                                    err = true;
                                    break;
                                }
                            }
                            cmdline = m.replaceAll("");

                            m = ACTION.matcher(cmdline);
                            while (m.find()) {
                                intent.setAction(m.group(1));
                            }

                            if (err) {
                                out.flush();
                                continue;
                            }

                            for (Map.Entry<String, String> e : stringExtras.entrySet()) {
                                intent.putExtra(e.getKey(), e.getValue());
                            }
                            for (Map.Entry<String, String[]> e : stringArrayExtras.entrySet()) {
                                intent.putExtra(e.getKey(), e.getValue());
                            }
                            for (Map.Entry<String, Integer> e : intExtras.entrySet()) {
                                intent.putExtra(e.getKey(), e.getValue());
                            }
                            for (Map.Entry<String, Boolean> e : booleanExtras.entrySet()) {
                                intent.putExtra(e.getKey(), e.getValue());
                            }

                            app.getApplicationContext().sendOrderedBroadcast(intent, null);
                            con.getOutputStream().write(0);
                            con.getOutputStream().flush();
                        } catch (Exception e) {
                            Logger.logStackTraceWithMessage(LOG_TAG, "Error parsing socket command", e);
                            out.write("Exception in USB socket listener\n");
                            out.flush();
                        }
                    } catch (java.io.IOException e) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "Socket connection error", e);
                    }
                }
            } catch (Exception e) {
                Logger.logVerbose(LOG_TAG, "Socket listener not bound on " + socketAddress + ": " + e.getMessage());
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
