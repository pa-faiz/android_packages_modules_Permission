/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.ecm;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TargetApi;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.os.UserHandle;
import android.permission.flags.Flags;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.annotation.Retention;

/**
 * This class provides the core API for ECM (Enhanced Confirmation Mode). ECM is a feature that
 * restricts access to protected **settings** (i.e., sensitive resources) by restricted **apps**
 * (apps from from dangerous sources, such as sideloaded packages or packages downloaded from a web
 * browser).
 *
 * <p>Specifically, this class provides the ability to:
 *
 * <ol>
 *   <li>Check whether a setting is restricted from an app ({@link #isRestricted})
 *   <li>Get an intent that will open the "Restricted setting" dialog ({@link
 *       #getRestrictedSettingDialogIntent}) (a dialog that informs the user that the operation
 *       they've attempted to perform is restricted)
 *   <li>Check whether an app is eligible to have its restriction status cleared ({@link
 *       #isClearRestrictionAllowed})
 *   <li>Clear an app's restriction status (i.e., un-restrict it). ({@link #clearRestriction})
 * </ol>
 *
 * <p>Methods of this class will generally accept an app (identified by a packageName and a user)
 * and a "setting" (a string representing the "sensitive resource") as arguments. ECM's exact
 * behavior will generally depend on what restriction state ECM considers each setting and app. For
 * example:
 *
 * <ol>
 *   <li>A setting may be considered by ECM to be either **protected** or **not protected**. In
 *       general, this should be considered hardcoded into ECM's implementation: nothing can
 *       "protect" or "unprotect" a setting.
 *   <li>An app may be considered as being **not restricted** or **restricted**. A restricted app
 *       will be restricted from accessing all protected settings. Whether ECM considers any
 *       particular app restricted is an implementation detail of ECM. However, the user is able to
 *       clear any restricted app's restriction status (i.e, un-restrict it), after which ECM will
 *       consider the app **not restricted**.
 * </ol>
 *
 * Why is ECM needed? Consider the following (pre-ECM) scenario:
 *
 * <ol>
 *   <li>The user downloads and installs an apk file from a browser.
 *   <li>The user opens Settings -> Accessibility
 *   <li>The user tries to register the app as an accessibility service.
 *   <li>The user is shown a permission prompt "Allow _ to have full control of your device?"
 *   <li>The user clicks "Allow"
 *   <li>The downloaded app now has full control of the device.
 * </ol>
 *
 * The purpose of ECM is to add more friction to this scenario.
 *
 * <p>With ECM, this scenario becomes:
 *
 * <ol>
 *   <li>The user downloads and installs an apk file from a browser.
 *   <li>The user goes into Settings -> Accessibility.
 *   <li>The user tries to register the app as an accessibility service.
 *   <li>The user is presented with a "Restricted setting" dialog explaining that the attempted
 *       action has been restricted. (No "allow" button is shown, but a link is given to a screen
 *       with intentionally-obscure instructions on how to proceed.)
 *   <li>The user must now navigate to Settings -> Apps -> [app]
 *   <li>The user then must click on "..." (top-right corner hamburger menu), then click "Allow
 *       restricted settings"
 *   <li>The user goes (again) into Settings -> Accessibility and (again) tries to register the app
 *       as an accessibility service.
 *   <li>The user is shown a permission prompt "Allow _ to have full control of your device?"
 *   <li>The user clicks "Allow"
 *   <li>The downloaded app now has full control of the device.
 * </ol>
 *
 * And, expanding on the above scenario, the role that this class plays is as follows:
 *
 * <ol>
 *   <li>The user downloads and installs an apk file from a browser.
 *   <li>The user goes into Settings -> Accessibility.
 *       <p>**This screen then calls {@link #isRestricted}, which checks whether each app listed
 *       on-screen is restricted from the accessibility service setting. It uses this to visually
 *       "gray out" restricted apps.**
 *   <li>The user tries to register the app as an accessibility service.
 *       <p>**This screen then calls {@link #getRestrictedSettingDialogIntent} and starts the
 *       intent. This opens the "Restricted setting" dialog.**
 *   <li>The user is presented with a "Restricted setting" dialog explaining that the attempted
 *       action is restricted. (No "allow" button is shown, but a link is given to a screen with
 *       intentionally-obscure instructions on how to proceed.)
 *       <p>**Upon opening, this dialog marks the app as eligible to have its restriction status
 *       cleared.**
 *   <li>The user must now navigate to Settings -> Apps -> [app].
 *       <p>**This screen calls {@link #isClearRestrictionAllowed} to check whether the app is
 *       eligible to have its restriction status cleared. If this returns {@code true}, this screen
 *       should then show a "Allow restricted setting" button inside the top-right hamburger menu.**
 *   <li>The user then must click on "..." (top-right corner hamburger menu), then click "Allow
 *       restricted settings".
 *       <p>**In response, this screen should now call {@link #clearRestriction}.**
 *   <li>The user goes (again) into Settings -> Accessibility and (again) tries to register the app
 *       as an accessibility service.
 *   <li>The user is shown a permission prompt "Allow _ to have full control of your device?"
 *   <li>The user clicks "Allow"
 *   <li>The downloaded app now has full control of the device.
 * </ol>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENHANCED_CONFIRMATION_MODE_APIS_ENABLED)
@TargetApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@SystemService(Context.ECM_ENHANCED_CONFIRMATION_SERVICE)
public final class EnhancedConfirmationManager {
    /*
     * At the API level, we use the following terminology:
     *
     * - The capability of an app to access a setting may be considered (by ECM) to be *restricted*
     * or *not restricted*.
     * - A setting may be considered (by ECM) to be *protected* or *not protected*.
     * - The state of an app may be considered (by ECM) to be *restricted* or *not restricted*
     *
     * In this implementation, however, the state of an app is considered either **guarded** or
     * **not guarded**; these terms can generally be considered synonymous with **restricted** and
     * **not restricted**. (Keeping in mind that, the capability of any app to access any
     * non-protected setting will always be considered "not restricted", even if the state of the
     * app is considered "restricted".). An app can also be in a third state: **guarded and
     * acknowledged**, which corresponds with an app that is restricted and is eligible to have its
     * restriction status cleared.
     *
     * Currently, the ECM state of any given app is stored in the OP_ACCESS_RESTRICTED_SETTINGS
     * appop (though this may change in the future):
     *
     * - MODE_ALLOWED means the app is explicitly **not guarded**. (U- default)
     * - MODE_ERRORED means the app is explicitly **guarded**. (Only settable in U-.)
     * - MODE_IGNORED means the app is explicitly **guarded and acknowledged**. (An app enters this
     *   state as soon as the "Restricted setting" dialog has been shown to the user. If an app is
     *   in this state, Settings is now allowed to provide the user with the option to clear the
     *   restriction.)
     * - MODE_DEFAULT means the app's ECM state should be decided lazily. (V+ default) (That is,
     *   each time a caller checks whether or not an app is considered guarded by ECM, we'll run an
     *   heuristic to determine this.)
     *
     * Some notes on compatibility:
     *
     *   - On U-, MODE_ALLOWED is the default mode of OP_ACCESS_RESTRICTED_SETTINGS. On both U- and
     *   V+, this is also the mode after the app's restriction has been cleared.
     *   - In U-, the mode needed to be explicitly set (for example, by a browser that allows a
     *   dangerous app to be installed) to MODE_ERRORED to indicate that an app is guarded. In V+,
     *   we no longer allow an app to be placed into MODE_ERRORED, but for compatibility, we still
     *   recognize MODE_ERRORED to indicate that an app is explicitly guarded.
     * - In V+, the default mode is MODE_DEFAULT. Unlike U-, this potentially affects *all* apps,
     *   not just the ones which have been explicitly marked as **guarded**.
     *
     * Regarding ECM "setting"s: a setting may be any abstract resource identified by a string. ECM
     * may consider any particular setting **protected** or **not protected**. For now, the set of
     * protected settings is hardcoded, but this may evolve in the future.
     *
     * TODO(b/320512579): These methods currently enforce UPDATE_APP_OPS_STATS,
     * UPDATE_APP_OPS_STATS, and, for setter methods, MANAGE_APP_OPS_MODES. We should add
     * RequiresPermission annotations, but we can't, because some of these permissions are hidden
     * API. Either upgrade these to SystemApi or enforce a different permission, then add the
     * appropriate RequiresPermission annotation.
     */

    /** A map of ECM states to their corresponding app op states */
    @Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ECM_STATE_"}, value = {EcmState.ECM_STATE_NOT_GUARDED,
            EcmState.ECM_STATE_GUARDED, EcmState.ECM_STATE_GUARDED_AND_ACKNOWLEDGED,
            EcmState.ECM_STATE_IMPLICIT})
    private @interface EcmState {
        int ECM_STATE_NOT_GUARDED = AppOpsManager.MODE_ALLOWED;
        int ECM_STATE_GUARDED = AppOpsManager.MODE_ERRORED;
        int ECM_STATE_GUARDED_AND_ACKNOWLEDGED = AppOpsManager.MODE_IGNORED;
        int ECM_STATE_IMPLICIT = AppOpsManager.MODE_DEFAULT;
    }

    private static final String LOG_TAG = EnhancedConfirmationManager.class.getSimpleName();

    private static final ArraySet<String> PROTECTED_SETTINGS = new ArraySet<>();

    static {
        PROTECTED_SETTINGS.add(AppOpsManager.OPSTR_BIND_ACCESSIBILITY_SERVICE);
        // TODO(b/310654015): Add other explicitly protected settings
    }

    private final @NonNull Context mContext;
    private final String mAttributionTag;
    private final @NonNull UserHandle mUser;
    private final AppOpsManager mAppOpsManager;
    private final PackageManager mPackageManager;

    /**
     * @hide
     */
    public EnhancedConfirmationManager(@NonNull Context context) {
        mContext = context;
        mAttributionTag = context.getAttributionTag();
        mUser = context.getUser();
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mPackageManager = context.getPackageManager();
    }

    /**
     * Check whether a setting is restricted from an app.
     *
     * <p>This is {@code true} when the setting is a protected setting (i.e., a sensitive resource),
     * and the app is restricted (i.e., considered dangerous), and the user has not yet cleared the
     * app's restriction status (i.e., by clicking "Allow restricted settings" for this app).
     *
     * @param packageName package name of the application to check for
     * @param settingIdentifier identifier of the resource to check to check for
     * @return {@code true} if the setting is restricted from the app
     * @throws NameNotFoundException if the provided package was not found
     */
    public boolean isRestricted(@NonNull String packageName, @NonNull String settingIdentifier)
            throws NameNotFoundException {
        return isSettingEcmProtected(settingIdentifier) && isPackageEcmGuarded(packageName);
    }

    /**
     * Clear an app's restriction status (i.e., un-restrict it).
     *
     * <p>After this is called, the app will no longer be restricted from accessing any protected
     * setting by ECM. This method should be called when the user clicks "Allow restricted settings"
     * for the app.
     *
     * @param packageName package name of the application to remove protection from
     * @throws NameNotFoundException if the provided package was not found
     */
    public void clearRestriction(@NonNull String packageName) throws NameNotFoundException {
        if (!isClearRestrictionAllowed(packageName)) {
            throw new IllegalStateException("Clear restriction attempted but not allowed");
        }
        setAppEcmState(packageName, EcmState.ECM_STATE_NOT_GUARDED);
    }

    /**
     * Check whether the provided app is eligible to have its restriction status cleared (i.e., the
     * app is restricted, and the "Restricted setting" dialog has been presented to the user).
     *
     * <p>The Settings UI should use method this to check whether to present the user with the
     * "Allow restricted settings" button.
     *
     * @param packageName package name of the application to check for
     * @return {@code true} if the settings UI should present the user with the ability to clear
     * restrictions from the provided app
     * @throws NameNotFoundException if the provided package was not found
     */
    public boolean isClearRestrictionAllowed(@NonNull String packageName)
            throws NameNotFoundException {
        int state = getAppEcmState(packageName);
        return state == EcmState.ECM_STATE_GUARDED_AND_ACKNOWLEDGED;
    }

    /**
     * Mark the app as eligible to have its restriction status cleared.
     *
     * <p>This should be called from the "Restricted setting" dialog (which {@link
     * #getRestrictedSettingDialogIntent} directs to) upon being presented to the user.
     *
     * @param packageName package name of the application which should be considered acknowledged
     * @throws NameNotFoundException if the provided package was not found
     *
     * @hide
     */
    public void setClearRestrictionAllowed(@NonNull String packageName)
            throws NameNotFoundException {
        if (isPackageEcmGuarded(packageName)) {
            setAppEcmState(packageName, EcmState.ECM_STATE_GUARDED_AND_ACKNOWLEDGED);
        }
    }

    /**
     * Gets an intent that will open the "Restricted setting" dialog for the specified app.
     *
     * <p>The "Restricted setting" dialog is a dialog that informs the user that the operation
     * they've attempted to perform is restricted, and provides them with a link explaining how to
     * proceed.
     *
     * @param packageName package name of the application to open the dialog for
     * @throws NameNotFoundException if the provided package was not found
     */
    public @NonNull PendingIntent getRestrictedSettingDialogIntent(@NonNull String packageName)
            throws NameNotFoundException {
        Intent intent = new Intent(Settings.ACTION_SHOW_RESTRICTED_SETTING_DIALOG);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
        intent.putExtra(Intent.EXTRA_UID, getPackageUid(packageName));
        return PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    private boolean isPackageEcmGuarded(@NonNull String packageName) throws NameNotFoundException {
        // If this is a trusted installer or a pre-installed app, it is not ECM guarded
        if (isAppTrustedInstaller(packageName) || isPackagePreinstalled(packageName)) {
            return false;
        }

        // If the package already has an explicitly-set state, use that
        @EcmState int ecmState = getAppEcmState(packageName);
        if (ecmState == EcmState.ECM_STATE_GUARDED
                || ecmState == EcmState.ECM_STATE_GUARDED_AND_ACKNOWLEDGED) {
            return true;
        }
        if (ecmState == EcmState.ECM_STATE_NOT_GUARDED) {
            return false;
        }

        // Otherwise, lazily decide whether the app is considered guarded.
        InstallSourceInfo installSource;
        try {
            installSource = mPackageManager.getInstallSourceInfo(packageName);
        } catch (NameNotFoundException e) {
            Log.w(LOG_TAG, "Package not found: " + packageName);
            return false;
        }

        // These install sources are always considered dangerous.
        // PackageInstallers that are trusted can use these as a signal that the
        // packages they've installed aren't as trusted as themselves.
        int packageSource = installSource.getPackageSource();
        if (packageSource == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
                || packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE) {
            return true;
        }

        // ECM doesn't consider a transitive chain of trust for install sources.
        // If this package hasn't been explicitly handled by this point
        // then it is exempt from ECM if the immediate parent is a trusted installer
        boolean installedFromTrustedInstaller = installSource.getInstallingPackageName() != null
                && isAppTrustedInstaller(installSource.getInstallingPackageName());
        return !installedFromTrustedInstaller;
    }

    /**
     * A "trusted installer" is any app with the INSTALL_PACKAGES permission.
     */
    private boolean isAppTrustedInstaller(@NonNull String packageName)
            throws NameNotFoundException {
        int uid = getPackageUid(packageName);
        // TODO(b/310654834): Support allow-list for OEM installer exemptions
        return mContext.checkPermission(android.Manifest.permission.INSTALL_PACKAGES, 0, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isPackagePreinstalled(@NonNull String packageName) {
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = mPackageManager.getApplicationInfoAsUser(packageName, 0, mUser);
        } catch (NameNotFoundException e) {
            Log.w(LOG_TAG, "Package not found: " + packageName);
            return false;
        }
        return ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
    }

    private void setAppEcmState(@NonNull String packageName, @EcmState int ecmState)
            throws NameNotFoundException {
        mAppOpsManager.setMode(AppOpsManager.OPSTR_ACCESS_RESTRICTED_SETTINGS,
                getPackageUid(packageName), packageName, ecmState);
    }

    private @EcmState int getAppEcmState(@NonNull String packageName) throws NameNotFoundException {
        return mAppOpsManager.noteOpNoThrow(AppOpsManager.OPSTR_ACCESS_RESTRICTED_SETTINGS,
                getPackageUid(packageName), packageName, mAttributionTag, /* message */
                null);
    }

    private boolean isSettingEcmProtected(@NonNull String settingIdentifier) {
        if (PROTECTED_SETTINGS.contains(settingIdentifier)) {
            return true;
        }
        // TODO(b/310654818): If this is a permission, coerce it into a PermissionGroup.
        // TODO(b/310218979): Add role selections as protected settings
        return false;
    }

    private int getPackageUid(String packageName) throws NameNotFoundException {
        return mPackageManager.getApplicationInfoAsUser(packageName, /* flags */ 0, mUser).uid;
    }
}
