/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.lspd.service;

import android.content.AttributionSource;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.lsposed.daemon.BuildConfig;
import org.lsposed.lspd.models.Module;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.service.IXposedService;

public class LSPModuleService extends IXposedService.Stub {

    private final static String TAG = "LSPosedModuleService";


    private final static Set<Integer> uidSet = ConcurrentHashMap.newKeySet();

    private final Module loadedModule;

    static void uidStarts(int uid) {
        if (!uidSet.contains(uid)) {
            uidSet.add(uid);
            var module = ConfigManager.getInstance().getModule(uid);
            if (module != null) {
                ((LSPInjectedModuleService) module.service).getModuleService().sendBinder(uid);
            }
        }
    }

    static void uidGone(int uid) {
        uidSet.remove(uid);
    }

    private void sendBinder(int uid) {
        var name = loadedModule.packageName;
        try {
            int userId = uid / PackageService.PER_USER_RANGE;
            var authority = name + AUTHORITY_SUFFIX;
            var provider = ActivityManagerService.getContentProvider(authority, userId);
            if (provider == null) {
                Log.d(TAG, "no service provider for " + name);
                return;
            }
            var extra = new Bundle();
            extra.putBinder("binder", asBinder());
            Bundle reply = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                reply = provider.call(new AttributionSource.Builder(1000).setPackageName("android").build(), authority, SEND_BINDER, null, extra);
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                reply = provider.call("android", null, authority, SEND_BINDER, null, extra);
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                reply = provider.call("android", authority, SEND_BINDER, null, extra);
            }
            if (reply != null) {
                Log.d(TAG, "sent module binder to " + name);
            } else {
                Log.w(TAG, "failed to send module binder to " + name);
            }
        } catch (Throwable e) {
            Log.w(TAG, "failed to send module binder for uid " + uid, e);
        }
    }

    LSPModuleService(Module module) {
        loadedModule = module;
    }

    @Override
    public long getAPIVersion() {
        return API;
    }

    @Override
    public String implementationName() {
        return "LSPosed";
    }

    @Override
    public String implementationVersion() throws RemoteException {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public long implementationVersionCode() throws RemoteException {
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public List<String> getScope() {
        ArrayList<String> res = new ArrayList<>();
        var scope = ConfigManager.getInstance().getModuleScope(loadedModule.packageName);
        if (scope == null) return res;
        for (var s : scope) {
            res.add(s.packageName);
        }
        return res;
    }

    @Override
    public void requestScope(String packageName) {
        // TODO
    }

    @Override
    public Bundle requestRemotePreferences(String group) throws RemoteException {
        // TODO
        return null;
    }

    @Override
    public void updateRemotePreferences(String group, Bundle diff) throws RemoteException {
        // TODO
        ((LSPInjectedModuleService) loadedModule.service).onUpdateRemotePreferences(group, diff);
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String path, int mode) throws RemoteException {
        try {
            var absolutePath = ConfigFileManager.resolveModulePath(loadedModule.packageName, path);
            if (!absolutePath.getParent().toFile().mkdirs()) {
                throw new IOException("failed to create parent dir");
            }
            return ParcelFileDescriptor.open(absolutePath.toFile(), mode);
        } catch (Throwable e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public boolean deleteRemoteFile(String path) throws RemoteException {
        try {
            var absolutePath = ConfigFileManager.resolveModulePath(loadedModule.packageName, path);
            return absolutePath.toFile().delete();
        } catch (Throwable e) {
            throw new RemoteException(e.getMessage());
        }
    }
}
