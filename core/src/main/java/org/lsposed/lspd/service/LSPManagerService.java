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

import static android.content.Context.BIND_AUTO_CREATE;
import static org.lsposed.lspd.service.ServiceManager.TAG;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.VersionedPackage;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemProperties;
import android.util.Log;

import androidx.annotation.NonNull;

import org.lsposed.lspd.BuildConfig;
import org.lsposed.lspd.ILSPManagerService;
import org.lsposed.lspd.models.Application;
import org.lsposed.lspd.models.UserInfo;
import org.lsposed.lspd.util.Utils;

import java.io.FileDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.robv.android.xposed.XposedBridge;
import io.github.xposed.xposedservice.utils.ParceledListSlice;

public class LSPManagerService extends ILSPManagerService.Stub {
    private static final String PROP_NAME = "dalvik.vm.dex2oat-flags";
    private static final String PROP_VALUE = "--inline-max-code-units=0";
    // this maybe useful when obtaining the manager binder
    private static final String RANDOM_UUID = UUID.randomUUID().toString();

    public class ManagerGuard implements IBinder.DeathRecipient {
        private final @NonNull
        IBinder binder;
        private final int pid;
        private final int uid;
        private final IServiceConnection connection = new IServiceConnection.Stub() {
            @Override
            public void connected(ComponentName name, IBinder service, boolean dead) {
            }
        };

        public ManagerGuard(@NonNull IBinder binder, int pid, int uid) {
            guard = this;
            this.pid = pid;
            this.uid = uid;
            this.binder = binder;
            try {
                this.binder.linkToDeath(this, 0);
                if (Utils.isMIUI) {
                    var intent = new Intent();
                    intent.setComponent(ComponentName.unflattenFromString("com.miui.securitycore/com.miui.xspace.service.XSpaceService"));
                    ActivityManagerService.bindService(intent, intent.getType(), connection, BIND_AUTO_CREATE, "android", 0);
                }
            } catch (Throwable e) {
                Log.e(TAG, "manager guard", e);
                guard = null;
            }
        }

        @Override
        public void binderDied() {
            try {
                binder.unlinkToDeath(this, 0);
                ActivityManagerService.unbindService(connection);
            } catch (Throwable e) {
                Log.e(TAG, "manager guard", e);
            }
            guard = null;
        }

        boolean isAlive() {
            return binder.isBinderAlive();
        }
    }

    public ManagerGuard guard = null;

    // guard to determine the manager or the injected app
    // that is to say, to make the parasitic success,
    // we should make sure no extra launch after parasitic
    // launch is queued and before the process is started
    private boolean pendingManager = false;
    private int managerPid = -1;

    LSPManagerService() {
    }

    public ManagerGuard guardSnapshot() {
        var snapshot = guard;
        return snapshot != null && snapshot.isAlive() ? snapshot : null;
    }

    // To start injected manager, we should take care about conflict
    // with the target app since we won't inject into it
    // if we are not going to display manager.
    // Thus, when someone launching manager, we should no matter
    // stop any process of the target app
    // Ideally we should call force stop package here,
    // however it's not feasible because it will cause deadlock
    // Thus we will cancel the launch of the activity
    // and manually start activity with force stopping
    // However, the intent we got here is not complete since
    // there's no extras. We cannot do the same thing
    // where starting the target app while the manager is
    // still running.
    // We instead let the manager to restart the activity.
    synchronized boolean preStartManager(String pkgName, Intent intent) {
        // first, check if it's our target app, if not continue the start
        if (BuildConfig.MANAGER_INJECTED_PKG_NAME.equals(pkgName)) {
            Log.d(TAG, "starting target app of parasitic manager");
            // check if it's launching our manager
            if (intent.getCategories() != null &&
                    intent.getCategories().contains("org.lsposed.manager.LAUNCH_MANAGER")) {
                Log.d(TAG, "requesting launch of manager");
                // a new launch for the manager
                // check if there's one running
                // or it's run by ourselves after force stopping
                var snapshot = guardSnapshot();
                if (intent.getCategories().contains(RANDOM_UUID) ||
                        (snapshot != null && snapshot.isAlive() && snapshot.uid == BuildConfig.MANAGER_INJECTED_UID)) {
                    Log.d(TAG, "manager is still running or is on its way");
                    // there's one running parasitic manager
                    // or it's run by ourself after killing, resume it
                    return true;
                } else {
                    // new parasitic manager launch, set the flag and kill
                    // old processes
                    // we do it by cancelling the launch (return false)
                    // and start activity in a new thread
                    pendingManager = true;
                    new Thread(() -> stopAndStartActivity(pkgName, intent, true)).start();
                    Log.d(TAG, "requested to launch manager");
                    return false;
                }
            } else if (pendingManager) {
                // there's still parasitic manager, cancel a normal launch until
                // the parasitic manager is launch
                Log.d(TAG, "previous request is not yet done");
                return false;
            }
            // this is a normal launch of the target app
            // send it to the manager and let it to restart the package
            // if the manager is running
            // or normally restart without injecting
            Log.d(TAG, "launching the target app normally");
            return true;
        }
        return true;
    }

    synchronized void stopAndStartActivity(String packageName, Intent intent, boolean addUUID) {
        try {
            ActivityManagerService.forceStopPackage(packageName, 0);
            Log.d(TAG, "stopped old package");
            if (addUUID) {
                intent = (Intent) intent.clone();
                intent.addCategory(RANDOM_UUID);
            }
            ActivityManagerService.startActivityAsUserWithFeature("android", null, intent, intent.getType(), null, null, 0, 0, null, null, 0);
            Log.d(TAG, "relaunching");
        } catch (RemoteException e) {
            Log.e(TAG, "stop and start activity", e);
        }
    }

    // return true to inject manager
    synchronized boolean shouldStartManager(int pid, int uid, String processName) {
        if (uid != BuildConfig.MANAGER_INJECTED_UID || !BuildConfig.MANAGER_INJECTED_PKG_NAME.equals(processName) || !pendingManager)
            return false;
        // pending parasitic manager launch it processes
        // now we have its pid so we allow it to be killed
        // and thus reset the pending flag and mark its pid
        pendingManager = false;
        managerPid = pid;
        Log.d(TAG, "starting injected manager: pid = " + pid + " uid = " + uid + " processName = " + processName);
        return true;
    }

    // return true to send manager binder
    synchronized boolean postStartManager(int pid, int uid) {
        return pid == managerPid && uid == BuildConfig.MANAGER_INJECTED_UID;
    }

    public @NonNull
    IBinder obtainManagerBinder(@NonNull IBinder heartbeat, int pid, int uid) {
        new ManagerGuard(heartbeat, pid, uid);
        if (postStartManager(pid, uid)) {
            managerPid = 0;
        }
        return this;
    }

    public boolean isRunningManager(int pid, int uid) {
        var snapshotPid = managerPid;
        var snapshotGuard = guardSnapshot();
        return (pid == snapshotPid && uid == BuildConfig.MANAGER_INJECTED_UID) || (snapshotGuard != null && snapshotGuard.pid == pid && snapshotGuard.uid == uid);
    }

    void onSystemServerDied() {
        pendingManager = false;
        managerPid = 0;
        guard = null;
    }

    @Override
    public IBinder asBinder() {
        return this;
    }

    @Override
    public int getXposedApiVersion() {
        return XposedBridge.getXposedVersion();
    }

    @Override
    public int getXposedVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public String getXposedVersionName() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public ParceledListSlice<PackageInfo> getInstalledPackagesFromAllUsers(int flags, boolean filterNoProcess) throws RemoteException {
        return PackageService.getInstalledPackagesFromAllUsers(flags, filterNoProcess);
    }

    @Override
    public String[] enabledModules() {
        return ConfigManager.getInstance().enabledModules();
    }

    @Override
    public boolean enableModule(String packageName) throws RemoteException {
        PackageInfo pkgInfo = PackageService.getPackageInfo(packageName, PackageService.MATCH_ALL_FLAGS, 0);
        if (pkgInfo != null && pkgInfo.applicationInfo != null) {
            return ConfigManager.getInstance().enableModule(packageName, pkgInfo.applicationInfo);
        } else {
            return false;
        }
    }

    @Override
    public boolean setModuleScope(String packageName, ParceledListSlice<Application> scope) {
        return ConfigManager.getInstance().setModuleScope(packageName, scope.getList());
    }

    @Override
    public ParceledListSlice<Application> getModuleScope(String packageName) {
        List<Application> list = ConfigManager.getInstance().getModuleScope(packageName);
        if (list == null) return null;
        else return new ParceledListSlice<>(list);
    }

    @Override
    public boolean disableModule(String packageName) {
        return ConfigManager.getInstance().disableModule(packageName);
    }

    @Override
    public boolean isResourceHook() {
        return ConfigManager.getInstance().resourceHook();
    }

    @Override
    public void setResourceHook(boolean enabled) {
        ConfigManager.getInstance().setResourceHook(enabled);
    }

    @Override
    public boolean isVerboseLog() {
        return ConfigManager.getInstance().verboseLog();
    }

    @Override
    public void setVerboseLog(boolean enabled) {
        ConfigManager.getInstance().setVerboseLog(enabled);
    }

    @Override
    public ParcelFileDescriptor getVerboseLog() {
        return ConfigManager.getInstance().getVerboseLog();
    }

    @Override
    public ParcelFileDescriptor getModulesLog() {
        return ConfigManager.getInstance().getModulesLog();
    }

    @Override
    public boolean clearLogs(boolean verbose) {
        return ConfigManager.getInstance().clearLogs(verbose);
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags, int uid) throws RemoteException {
        return PackageService.getPackageInfo(packageName, flags, uid);
    }

    @Override
    public void forceStopPackage(String packageName, int userId) throws RemoteException {
        ActivityManagerService.forceStopPackage(packageName, userId);
    }

    @Override
    public void reboot(boolean shutdown) {
        var value = shutdown ? "shutdown" : "reboot";
        SystemProperties.set("sys.powerctl", value);
    }

    @Override
    public boolean uninstallPackage(String packageName, int userId) throws RemoteException {
        try {
            if (ActivityManagerService.startUserInBackground(userId))
                return PackageService.uninstallPackage(new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST), userId);
            else return false;
        } catch (InterruptedException | InvocationTargetException | NoSuchMethodException | InstantiationException | IllegalAccessException e) {
            Log.e(TAG, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean isSepolicyLoaded() {
        return ConfigManager.getInstance().isSepolicyLoaded();
    }

    @Override
    public List<UserInfo> getUsers() throws RemoteException {
        var users = new LinkedList<UserInfo>();
        for (var user : UserService.getUsers()) {
            var info = new UserInfo();
            info.id = user.id;
            info.name = user.name;
            users.add(info);
        }
        return users;
    }

    @Override
    public int installExistingPackageAsUser(String packageName, int userId) {
        try {
            if (ActivityManagerService.startUserInBackground(userId))
                return PackageService.installExistingPackageAsUser(packageName, userId);
            else return PackageService.INSTALL_FAILED_INTERNAL_ERROR;
        } catch (Throwable e) {
            Log.w(TAG, "install existing package as user: ", e);
            return PackageService.INSTALL_FAILED_INTERNAL_ERROR;
        }
    }

    @Override
    public boolean systemServerRequested() {
        return ServiceManager.systemServerRequested();
    }

    @Override
    public int startActivityAsUserWithFeature(Intent intent, int userId) throws RemoteException {
        if (!intent.getBooleanExtra("lsp_no_switch_to_user", false)) {
            intent.removeExtra("lsp_no_switch_to_user");
            var currentUser = ActivityManagerService.getCurrentUser();
            if (currentUser == null) return -1;
            var parent = UserService.getProfileParent(userId);
            if (parent < 0) return -1;
            if (currentUser.id != parent && !ActivityManagerService.switchUser(parent)) return -1;
        }
        return ActivityManagerService.startActivityAsUserWithFeature("android", null, intent, intent.getType(), null, null, 0, 0, null, null, userId);
    }

    @Override
    public ParceledListSlice<ResolveInfo> queryIntentActivitiesAsUser(Intent intent, int flags, int userId) throws RemoteException {
        return PackageService.queryIntentActivities(intent, intent.getType(), flags, userId);
    }

    @Override
    public boolean dex2oatFlagsLoaded() {
        return SystemProperties.get(PROP_NAME).contains(PROP_VALUE);
    }

    @Override
    public void setHiddenIcon(boolean hide) {
        var settings = new ServiceShellCommand("settings");
        var enable = hide ? "0" : "1";
        var args = new String[]{"put", "global", "show_hidden_icon_apps_enabled", enable};
        try {
            settings.shellCommand(FileDescriptor.in, FileDescriptor.out, FileDescriptor.err,
                    args, new ResultReceiver(null));
        } catch (RemoteException e) {
            Log.w(TAG, "setHiddenIcon: ", e);
        }
    }

    @Override
    public Map<String, ParcelFileDescriptor> getLogs() {
        return ConfigFileManager.getLogs();
    }

    @Override
    public void restartFor(Intent intent) throws RemoteException {
        forceStopPackage(BuildConfig.MANAGER_INJECTED_PKG_NAME, 0);
        stopAndStartActivity(BuildConfig.MANAGER_INJECTED_PKG_NAME, intent, false);
    }
}
