package io.github.lsposed.lspd.service;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.Map;

import io.github.lsposed.lspd.ILSPManagerService;
import io.github.xposed.xposedservice.IXposedService;

import static android.os.Binder.getCallingUid;
import static io.github.lsposed.lspd.service.Service.TAG;

public class BridgeService {
    private static final int TRANSACTION_CODE = ('_' << 24) | ('L' << 16) | ('S' << 8) | 'P';
    private static final String DESCRIPTOR = "android.app.IActivityManager";
    private static final String SERVICE_NAME = "activity";

    enum ACTION {
        ACTION_SEND_BINDER,
        ACTION_SEND_MANAGER_BINDER,
        ACTION_GET_BINDER,
    }

    private static IBinder serviceBinder = null;
    private static IXposedService service = null;

    private static IBinder managerBinder = null;
    private static ILSPManagerService manager = null;

    private static final IBinder.DeathRecipient BRIDGE_SERVICE_DEATH_RECIPIENT = () -> {
        Log.i(TAG, "service " + SERVICE_NAME + " is dead. ");

        try {
            Field field = ServiceManager.class.getDeclaredField("sServiceManager");
            field.setAccessible(true);
            field.set(null, null);

            //noinspection JavaReflectionMemberAccess
            field = ServiceManager.class.getDeclaredField("sCache");
            field.setAccessible(true);
            Object sCache = field.get(null);
            if (sCache instanceof Map) {
                //noinspection rawtypes
                ((Map) sCache).clear();
            }
            Log.i(TAG, "clear ServiceManager");
        } catch (Throwable e) {
            Log.w(TAG, "clear ServiceManager: " + Log.getStackTraceString(e));
        }

        sendToBridge(ACTION.ACTION_SEND_BINDER, serviceBinder, true);
        sendToBridge(ACTION.ACTION_SEND_MANAGER_BINDER, managerBinder, true);
    };

    private static final IBinder.DeathRecipient LSPSERVICE_DEATH_RECIPIENT = () -> {
        serviceBinder = null;
        service = null;
        managerBinder = null;
        manager = null;
        Log.e(TAG, "service is dead");
    };

    public interface Listener {

        void onSystemServerRestarted();

        void onResponseFromBridgeService(boolean response);
    }

    private static Listener listener;

    private static void sendToBridge(ACTION action, IBinder binder, boolean isRestart) {
        IBinder bridgeService;
        do {
            bridgeService = ServiceManager.getService(SERVICE_NAME);
            if (bridgeService != null && bridgeService.pingBinder()) {
                break;
            }

            Log.i(TAG, "service " + SERVICE_NAME + " is not started, wait 1s.");

            try {
                //noinspection BusyWait
                Thread.sleep(1000);
            } catch (Throwable e) {
                Log.w(TAG, "sleep" + Log.getStackTraceString(e));
            }
        } while (true);

        if (isRestart && listener != null) {
            listener.onSystemServerRestarted();
        }

        try {
            bridgeService.linkToDeath(BRIDGE_SERVICE_DEATH_RECIPIENT, 0);
        } catch (Throwable e) {
            Log.w(TAG, "linkToDeath " + Log.getStackTraceString(e));
            sendToBridge(action, binder, false);
            return;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        boolean res = false;
        // try at most three times
        for (int i = 0; i < 3; i++) {
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(action.ordinal());
                Log.v(TAG, "binder " + binder.toString());
                data.writeStrongBinder(binder);
                res = bridgeService.transact(TRANSACTION_CODE, data, reply, 0);
                reply.readException();
            } catch (Throwable e) {
                Log.e(TAG, "send binder " + Log.getStackTraceString(e));
            } finally {
                data.recycle();
                reply.recycle();
            }

            if (res) break;

            Log.w(TAG, "no response from bridge, retry in 1s");

            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }

        if (listener != null) {
            listener.onResponseFromBridgeService(res);
        }
    }

    private static void receiveFromBridge(ACTION action, IBinder binder) {
        if (binder == null) {
            Log.e(TAG, "received empty binder");
            return;
        }

        if(action == ACTION.ACTION_SEND_MANAGER_BINDER) {
            if (managerBinder != null) {
                managerBinder.unlinkToDeath(LSPSERVICE_DEATH_RECIPIENT, 0);
            }

            managerBinder = binder;
            manager = LSPManagerService.Stub.asInterface(managerBinder);
            try {
                managerBinder.linkToDeath(LSPSERVICE_DEATH_RECIPIENT, 0);
            } catch (RemoteException ignored) {
            }
        } else if (action == ACTION.ACTION_SEND_BINDER) {
            if (serviceBinder == null) {
                PackageReceiver.register();
            } else {
                serviceBinder.unlinkToDeath(LSPSERVICE_DEATH_RECIPIENT, 0);
            }

            serviceBinder = binder;
            service = IXposedService.Stub.asInterface(serviceBinder);
            try {
                serviceBinder.linkToDeath(LSPSERVICE_DEATH_RECIPIENT, 0);
            } catch (RemoteException ignored) {
            }
        }

        Log.i(TAG, "binder received");
    }

    public static void send(LSPosedService service, Listener listener) {
        BridgeService.listener = listener;
        BridgeService.service = service;
        BridgeService.serviceBinder = service.asBinder();
        sendToBridge(ACTION.ACTION_SEND_BINDER, serviceBinder, false);
    }

    public static void send(LSPManagerService service, Listener listener) {
        BridgeService.listener = listener;
        BridgeService.manager = service;
        BridgeService.managerBinder = service.asBinder();
        sendToBridge(ACTION.ACTION_SEND_MANAGER_BINDER, managerBinder, false);
    }

    public static IXposedService getService() {
        return service;
    }

    public static ILSPManagerService getManager() {
        return manager;
    }

    public static IBinder requireBinder() {
        IBinder binder = ServiceManager.getService(SERVICE_NAME);
        if (binder == null) return null;

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(ACTION.ACTION_GET_BINDER.ordinal());
            binder.transact(TRANSACTION_CODE, data, reply, 0);
            reply.readException();
            IBinder received = reply.readStrongBinder();
            if (received != null) {
                return received;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            data.recycle();
            reply.recycle();
        }
        return null;
    }

    @SuppressWarnings({"unused", "RedundantSuppression"})
    public static boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) {
        data.enforceInterface(DESCRIPTOR);

        ACTION action = ACTION.values()[data.readInt()];
        Log.d(TAG, "onTransact: action=" + action + ", callingUid=" + Binder.getCallingUid() + ", callingPid=" + Binder.getCallingPid());

        switch (action) {
            case ACTION_SEND_BINDER:
            case ACTION_SEND_MANAGER_BINDER: {
                if (Binder.getCallingUid() == 0) {
                    receiveFromBridge(action, data.readStrongBinder());
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                break;
            }
            case ACTION_GET_BINDER: {
                try {
                    if (!PackageService.isInstaller(getCallingUid())) return false;
                } catch (Throwable ignored) {
                    return false;
                }
                if (reply != null) {
                    reply.writeNoException();
                    Log.d(TAG, "saved binder is " + managerBinder.toString());
                    reply.writeStrongBinder(managerBinder);
                }
                return true;
            }
        }
        return false;
    }

    public static boolean execTransact(int code, long dataObj, long replyObj, int flags) {
        Log.d(TAG, String.valueOf(code));
        if (code != TRANSACTION_CODE) return false;

        Parcel data = ParcelUtils.fromNativePointer(dataObj);
        Parcel reply = ParcelUtils.fromNativePointer(replyObj);

        if (data == null) {
            return false;
        }

        boolean res = false;

        try {
            String descriptor = ParcelUtils.readInterfaceDescriptor(data);
            data.setDataPosition(0);

            if (descriptor.equals(DESCRIPTOR)) {
                res = onTransact(code, data, reply, flags);
            }
        } catch (Exception e) {
            if ((flags & IBinder.FLAG_ONEWAY) != 0) {
                Log.w(TAG, "Caught a Exception from the binder stub implementation. " + Log.getStackTraceString(e));
            } else {
                reply.setDataPosition(0);
                reply.writeException(e);
            }
            res = true;
        }

        if (res) {
            if (data != null) data.recycle();
            if (reply != null) reply.recycle();
        }

        return res;
    }
}
