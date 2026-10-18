package com.termux.app.usb;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.BroadcastReceiver.PendingResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import android.os.ParcelFileDescriptor;
import android.util.JsonWriter;

import androidx.annotation.NonNull;

import com.termux.shared.android.PackageUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public abstract class TermuxUsbResultReturner {

    @SuppressLint("StaticFieldLeak")
    public static Context context;

    private static final String LOG_TAG = "TermuxUsbResultReturner";

    private static final String SOCKET_OUTPUT_EXTRA = "socket_output";
    private static final String SOCKET_INPUT_EXTRA = "socket_input";
    private static final String __RETURN_DATA_CALLED_EXTRA = "__return_data_called";

    public interface ResultWriter {
        void writeResult(PrintWriter out) throws Exception;
    }

    public static abstract class WithInput implements ResultWriter {
        protected InputStream in;

        public void setInput(InputStream inputStream) throws Exception {
            this.in = inputStream;
        }
    }

    public static abstract class BinaryOutput implements ResultWriter {
        private OutputStream out;

        public void setOutput(OutputStream outputStream) {
            this.out = outputStream;
        }

        public abstract void writeResult(OutputStream out) throws Exception;

        @Override
        public final void writeResult(PrintWriter unused) throws Exception {
            writeResult(out);
            out.flush();
        }
    }

    public static abstract class WithStringInput extends WithInput {
        protected String inputString;

        protected boolean trimInput() {
            return true;
        }

        @Override
        public final void setInput(InputStream inputStream) throws Exception {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int l;
            while ((l = inputStream.read(buffer)) > 0) {
                baos.write(buffer, 0, l);
            }
            inputString = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            if (trimInput()) inputString = inputString.trim();
        }
    }

    public static abstract class WithAncillaryFd implements ResultWriter {
        private LocalSocket outputSocket = null;
        private final ParcelFileDescriptor[] pfds = { null };

        public final void setOutputSocketForFds(LocalSocket outputSocket) {
            this.outputSocket = outputSocket;
        }

        public final void sendFd(PrintWriter out, int fd) {
            if (this.pfds[0] != null) {
                Logger.logError(LOG_TAG, "File descriptor already sent");
                return;
            }

            this.pfds[0] = ParcelFileDescriptor.adoptFd(fd);
            FileDescriptor[] fds = { pfds[0].getFileDescriptor() };

            // Send raw USB file descriptor via SCM_RIGHTS ancillary message for termux-usb / termux-adb
            outputSocket.setFileDescriptorsForSend(fds);
            out.print("@");
            out.flush();
            outputSocket.setFileDescriptorsForSend(null);
        }

        public final void cleanupFds() {
            if (this.pfds[0] != null) {
                try {
                    this.pfds[0].close();
                } catch (IOException e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to close file descriptor", e);
                }
            }
        }
    }

    public static abstract class ResultJsonWriter implements ResultWriter {
        @Override
        public final void writeResult(PrintWriter out) throws Exception {
            JsonWriter writer = new JsonWriter(out);
            writer.setIndent("  ");
            writeJson(writer);
            out.println();
        }

        public abstract void writeJson(JsonWriter out) throws Exception;
    }

    @SuppressLint("SdCardPath")
    public static LocalSocketAddress getApiLocalSocketAddress(@NonNull Context ctx,
                                                              @NonNull String socketLabel,
                                                              @NonNull String socketAddress) {
        if (socketAddress.startsWith("/")) {
            isPathInTermuxAppDataDirectory(ctx, socketLabel + " socket address", socketAddress, true);
            return new LocalSocketAddress(socketAddress, Namespace.FILESYSTEM);
        } else {
            return new LocalSocketAddress(socketAddress, Namespace.ABSTRACT);
        }
    }

    @SuppressLint("SdCardPath")
    public static boolean isPathInTermuxAppDataDirectory(@NonNull Context ctx,
                                                         @NonNull String label,
                                                         String path,
                                                         boolean throwException) {
        if (path == null || !path.startsWith("/")) return false;

        ApplicationInfo termuxApplicationInfo = PackageUtils.getApplicationInfoForPackage(ctx,
                TermuxConstants.TERMUX_PACKAGE_NAME);
        if (termuxApplicationInfo == null) {
            if (throwException) {
                throw new RuntimeException("Failed to get ApplicationInfo for Termux package: " +
                        TermuxConstants.TERMUX_PACKAGE_NAME);
            }
            return false;
        }

        List<String> termuxAppDataDirectories = Arrays.asList(termuxApplicationInfo.dataDir,
                "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME);
        if (!FileUtils.isPathInDirPaths(path, termuxAppDataDirectories, true)) {
            if (throwException) {
                throw new RuntimeException("The " + label + " \"" + path + "\"" +
                        " is not under Termux app data directories: " + termuxAppDataDirectories);
            }
            return false;
        }

        return true;
    }

    public static boolean shouldRunThreadForResultRunnable(Object callerContext) {
        return !(callerContext instanceof IntentService);
    }

    public static void returnData(Object callerContext, final Intent intent, final ResultWriter resultWriter) {
        boolean returnDataCalled = intent.getBooleanExtra(__RETURN_DATA_CALLED_EXTRA, false);
        if (!returnDataCalled) {
            intent.putExtra(__RETURN_DATA_CALLED_EXTRA, true);
        }

        final BroadcastReceiver receiver = (callerContext instanceof BroadcastReceiver) ? (BroadcastReceiver) callerContext : null;
        final Activity activity = (callerContext instanceof Activity) ? (Activity) callerContext : null;
        final PendingResult asyncResult = (receiver != null && !returnDataCalled) ? receiver.goAsync() : null;

        final Runnable runnable = () -> {
            PrintWriter writer = null;
            LocalSocket outputSocket = null;
            try {
                if (returnDataCalled) {
                    throw new IllegalStateException("returnData() called again for the same API command");
                }

                String outputSocketAddress = intent.getStringExtra(SOCKET_OUTPUT_EXTRA);
                if (outputSocketAddress != null && !outputSocketAddress.isEmpty()) {
                    outputSocket = new LocalSocket();
                    outputSocket.connect(getApiLocalSocketAddress(TermuxUsbResultReturner.context, "output", outputSocketAddress));
                    writer = new PrintWriter(outputSocket.getOutputStream());

                    if (resultWriter != null) {
                        if (resultWriter instanceof WithAncillaryFd) {
                            ((WithAncillaryFd) resultWriter).setOutputSocketForFds(outputSocket);
                        }
                        if (resultWriter instanceof BinaryOutput) {
                            ((BinaryOutput) resultWriter).setOutput(outputSocket.getOutputStream());
                        }
                        if (resultWriter instanceof WithInput) {
                            try (LocalSocket inputSocket = new LocalSocket()) {
                                String inputSocketAddress = intent.getStringExtra(SOCKET_INPUT_EXTRA);
                                if (inputSocketAddress == null || inputSocketAddress.isEmpty())
                                    throw new IOException("Missing '" + SOCKET_INPUT_EXTRA + "' extra");
                                inputSocket.connect(getApiLocalSocketAddress(TermuxUsbResultReturner.context, "input", inputSocketAddress));
                                ((WithInput) resultWriter).setInput(inputSocket.getInputStream());
                                resultWriter.writeResult(writer);
                            }
                        } else {
                            resultWriter.writeResult(writer);
                        }
                        if (resultWriter instanceof WithAncillaryFd) {
                            ((WithAncillaryFd) resultWriter).cleanupFds();
                        }
                    }
                } else if (resultWriter != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    PrintWriter memWriter = new PrintWriter(baos);
                    resultWriter.writeResult(memWriter);
                    memWriter.flush();
                    String resultString = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                    Logger.logInfo(LOG_TAG, "USB API output: " + resultString.trim());
                    if (asyncResult != null && receiver != null && receiver.isOrderedBroadcast()) {
                        asyncResult.setResultData(resultString);
                    }
                }

                if (asyncResult != null && receiver != null && receiver.isOrderedBroadcast()) {
                    asyncResult.setResultCode(0);
                } else if (activity != null) {
                    activity.setResult(0);
                }
            } catch (Throwable t) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Error in TermuxUsbResultReturner", t);
                if (asyncResult != null && receiver != null && receiver.isOrderedBroadcast()) {
                    asyncResult.setResultCode(1);
                } else if (activity != null) {
                    activity.setResult(1);
                }
            } finally {
                try {
                    if (writer != null) writer.close();
                    if (outputSocket != null) outputSocket.close();
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to close socket", e);
                }
                try {
                    if (asyncResult != null) {
                        asyncResult.finish();
                    } else if (activity != null) {
                        activity.finish();
                    }
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to finish asyncResult", e);
                }
            }
        };

        if (shouldRunThreadForResultRunnable(callerContext)) {
            new Thread(runnable).start();
        } else {
            runnable.run();
        }
    }
}
