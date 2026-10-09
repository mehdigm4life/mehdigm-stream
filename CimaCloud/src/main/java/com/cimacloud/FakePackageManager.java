package com.cimacloud;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.util.Base64;

import java.util.ArrayList;
import java.util.List;

public final class FakePackageManager extends PackageManager {
    public static final String PKG = "com.app.cimacloud";

    private static final byte[] CERT = Base64.decode("MIIDgjCCAmqgAwIBAgIEBF/5ozANBgkqhkiG9w0BAQ0FADBaMQ8wDQYDVQQGDAbkuK3lm70xDzANBgNVBAgMBuaxn+iLjzEPMA0GA1UEBwwG5Y2X5LqsMQswCQYDVQQKEwJucDELMAkGA1UECxMCbnAxCzAJBgNVBAMTAm5wMCAXDTIxMDQyNTA5MDM1NloYDzMwMjAwODI2MDkwMzU2WjBaMQ8wDQYDVQQGDAbkuK3lm70xDzANBgNVBAgMBuaxn+iLjzEPMA0GA1UEBwwG5Y2X5LqsMQswCQYDVQQKEwJucDELMAkGA1UECxMCbnAxCzAJBgNVBAMTAm5wMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtAzLX5A4PlE55bBj7YgIGDByCm+0ylWGGHm8gFfXmrSaIbOhwYKvMAaR3plzMNQPzEDW330sENjEEetMRyqwuVCSnUf8FtH7s1QusQHm5d+wCsmpIeq1+lkqjrOfSAZzTAYaG6VrX5hLXeKnpQ96p+VNxCpduQrc+G76iIM3JCf+W9UPkH44WN2hnjVJReiobM4pNTrnMHWFUAuVh7VdNaEH1jkpIvh3c19hIwZNU5xUugIzGTi19ZyoDtT2p/cGiRnEEtP83TcLvS9CpsU68KaLKgvqBSmwIGVnxziVBEUSjVu5T38w/iRfl9evuoXJ+ucmpPK9Tlg8u5prYZ9IAwIDAQABo04wTDArBgNVHRAEJDAigA8yMDIxMDQyNTA5MDM1NlqBDzMwMjAwODI2MDkwMzU2WjAdBgNVHQ4EFgQUJHO/+imFJV6E83qFzdBYz/roeUcwDQYJKoZIhvcNAQENBQADggEBAKy2PCN4FvEd2EyG/P4o6WWyXEdHBGuEdBaLQ1exLseRL2fasekgx+bAYGkvaRwCBQ41mX0qoAAN4lO1vYHuas7nTp+bLm5MtfVUyMKDzI+Fs/ntLsoTQvajfS7um/ntqD1WB5Pn1kIASakWRTbb2vg560FyQyBZ+vPZI+6w6WQ9gqlkcuwaXbha5rQ1FND4o8wjE/0t66NjcM2aG1wu6R1jMhXB1DeSk2rLOPMeciZvAo/lanq7TsynR9SaR3iyQ4461V3qCy297hoyaQMmM6CsgsHpRkWcnLXZKLrvDv6e7A04tZhf9Mg+m5yrV180lINb1yuiYx6q6937tT0iNLk=", Base64.DEFAULT);


    public static final String TAG = "CimaNative";

    public static void log(String m) {
        try { android.util.Log.i(TAG, "FPM." + m); } catch (Throwable ignored) {}
    }

    private final PackageManager real;

    public FakePackageManager(PackageManager real) {
        this.real = real;
    }

    private ApplicationInfo fakeAppInfo(int flags) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = PKG;
        ai.sourceDir = "/data/app/" + PKG + "/base.apk";
        ai.publicSourceDir = ai.sourceDir;
        ai.nativeLibraryDir = "/data/app/" + PKG + "/lib";
        ai.dataDir = "/data/data/" + PKG;
        ai.enabled = true;
        ai.uid = 10000;
        if (ai.metaData == null) ai.metaData = new Bundle();
        return ai;
    }

    private PackageInfo fakePackageInfo(int flags) {
        PackageInfo pi = new PackageInfo();
        pi.packageName = PKG;
        pi.versionName = "1.4";
        pi.versionCode = 4;
        pi.firstInstallTime = 1600000000000L;
        pi.lastUpdateTime = 1600000000000L;
        pi.signatures = new Signature[]{ new Signature(CERT) };
        pi.applicationInfo = fakeAppInfo(flags);
        return pi;
    }

    @Override public PackageInfo getPackageInfo(java.lang.String name, int flags) throws PackageManager.NameNotFoundException {
        log("getPackageInfo(" + name + ",0x" + Integer.toHexString(flags) + ")");
        if (PKG.equals(name)) return fakePackageInfo(flags);
        return real.getPackageInfo(name, flags);
    }

    @Override public PackageInfo getPackageInfo(android.content.pm.VersionedPackage vp, int flags) throws PackageManager.NameNotFoundException {
        log("getPackageInfo(VP,0x" + Integer.toHexString(flags) + ")");
        if (vp != null && PKG.equals(vp.getPackageName())) return fakePackageInfo(flags);
        return real.getPackageInfo(vp, flags);
    }

    @Override public ApplicationInfo getApplicationInfo(java.lang.String name, int flags) throws PackageManager.NameNotFoundException {
        log("getApplicationInfo(" + name + ",0x" + Integer.toHexString(flags) + ")");
        if (PKG.equals(name)) return fakeAppInfo(flags);
        return real.getApplicationInfo(name, flags);
    }

    @Override public List<PackageInfo> getInstalledPackages(int flags) {
        log("getInstalledPackages(0x" + Integer.toHexString(flags) + ")");
        List<PackageInfo> list = new ArrayList<>(real.getInstalledPackages(flags));
        list.add(fakePackageInfo(flags));
        return list;
    }

    @Override public List<ApplicationInfo> getInstalledApplications(int flags) {
        log("getInstalledApplications(0x" + Integer.toHexString(flags) + ")");
        List<ApplicationInfo> list = new ArrayList<>(real.getInstalledApplications(flags));
        list.add(fakeAppInfo(flags));
        return list;
    }

    @Override public int getPackageUid(java.lang.String name, int flags) throws PackageManager.NameNotFoundException {
        if (PKG.equals(name)) return 10000;
        return real.getPackageUid(name, flags);
    }

    @Override public int[] getPackageGids(java.lang.String name) throws PackageManager.NameNotFoundException {
        if (PKG.equals(name)) return new int[]{10000};
        return real.getPackageGids(name);
    }

    @Override public int[] getPackageGids(java.lang.String name, int flags) throws PackageManager.NameNotFoundException {
        if (PKG.equals(name)) return new int[]{10000};
        return real.getPackageGids(name, flags);
    }

    @Override public java.lang.String[] getPackagesForUid(int uid) {
        return new java.lang.String[]{PKG};
    }

    @Override public int checkSignatures(java.lang.String pkg1, java.lang.String pkg2) {
        if (PKG.equals(pkg1) || PKG.equals(pkg2)) return 0;
        return real.checkSignatures(pkg1, pkg2);
    }

    @Override public java.lang.String[] currentToCanonicalPackageNames(java.lang.String[] p0) { log("currentToCanonicalPackageNames"); try { return real.currentToCanonicalPackageNames(p0); } catch (Throwable e) { return null; } }
    @Override public java.lang.String[] canonicalToCurrentPackageNames(java.lang.String[] p0) { log("canonicalToCurrentPackageNames"); try { return real.canonicalToCurrentPackageNames(p0); } catch (Throwable e) { return null; } }
    @Override public android.content.Intent getLaunchIntentForPackage(java.lang.String p0) { log("getLaunchIntentForPackage"); try { return real.getLaunchIntentForPackage(p0); } catch (Throwable e) { return null; } }
    @Override public android.content.Intent getLeanbackLaunchIntentForPackage(java.lang.String p0) { log("getLeanbackLaunchIntentForPackage"); try { return real.getLeanbackLaunchIntentForPackage(p0); } catch (Throwable e) { return null; } }
    @Override public android.content.pm.PermissionInfo getPermissionInfo(java.lang.String p0, int p1) { log("getPermissionInfo"); try { return real.getPermissionInfo(p0, p1); } catch (Throwable e) { return null; } }
    @Override public java.util.List<android.content.pm.PermissionInfo> queryPermissionsByGroup(java.lang.String p0, int p1) { log("queryPermissionsByGroup"); try { return real.queryPermissionsByGroup(p0, p1); } catch (Throwable e) { return null; } }
    @Override public android.content.pm.PermissionGroupInfo getPermissionGroupInfo(java.lang.String p0, int p1) { log("getPermissionGroupInfo"); try { return real.getPermissionGroupInfo(p0, p1); } catch (Throwable e) { return null; } }
    @Override public java.util.List<android.content.pm.PermissionGroupInfo> getAllPermissionGroups(int p0) { log("getAllPermissionGroups"); try { return real.getAllPermissionGroups(p0); } catch (Throwable e) { return null; } }
    @Override public android.content.pm.ActivityInfo getActivityInfo(android.content.ComponentName p0, int p1) { log("getActivityInfo"); try { return real.getActivityInfo(p0, p1); } catch (Throwable e) { return null; } }
    @Override public android.content.pm.ActivityInfo getReceiverInfo(android.content.ComponentName p0, int p1) { log("getReceiverInfo"); try { return real.getReceiverInfo(p0, p1); } catch (Throwable e) { return null; } }
    @Override public android.content.pm.ServiceInfo getServiceInfo(android.content.ComponentName p0, int p1) { log("getServiceInfo"); try { return real.getServiceInfo(p0, p1); } catch (Throwable e) { return null; } }
    @Override public android.content.pm.ProviderInfo getProviderInfo(android.content.ComponentName p0, int p1) { log("getProviderInfo"); try { return real.getProviderInfo(p0, p1); } catch (Throwable e) { return null; } }
    @Override public java.util.List<android.content.pm.PackageInfo> getPackagesHoldingPermissions(java.lang.String[] p0, int p1) { log("getPackagesHoldingPermissions"); try { return real.getPackagesHoldingPermissions(p0, p1); } catch (Throwable e) { return null; } }
    @Override public int checkPermission(java.lang.String p0, java.lang.String p1) { log("checkPermission"); try { return real.checkPermission(p0, p1); } catch (Throwable e) { return 0; } }
    @Override public boolean isPermissionRevokedByPolicy(java.lang.String p0, java.lang.String p1) { log("isPermissionRevokedByPolicy"); try { return real.isPermissionRevokedByPolicy(p0, p1); } catch (Throwable e) { return false; } }
    @Override public boolean addPermission(android.content.pm.PermissionInfo p0) { log("addPermission"); try { return real.addPermission(p0); } catch (Throwable e) { return false; } }
    @Override public boolean addPermissionAsync(android.content.pm.PermissionInfo p0) { log("addPermissionAsync"); try { return real.addPermissionAsync(p0); } catch (Throwable e) { return false; } }
    @Override public void removePermission(java.lang.String p0) { log("removePermission"); try { real.removePermission(p0); } catch (Throwable e) {} }
    @Override public int checkSignatures(int p0, int p1) { log("checkSignatures"); try { return real.checkSignatures(p0, p1); } catch (Throwable e) { return 0; } }
    @Override public java.lang.String getNameForUid(int p0) { log("getNameForUid"); try { return real.getNameForUid(p0); } catch (Throwable e) { return null; } }
    @Override public boolean isInstantApp() { log("isInstantApp"); try { return real.isInstantApp(); } catch (Throwable e) { return false; } }
    @Override public boolean isInstantApp(java.lang.String p0) { log("isInstantApp"); try { return real.isInstantApp(p0); } catch (Throwable e) { return false; } }
    @Override public int getInstantAppCookieMaxBytes() { log("getInstantAppCookieMaxBytes"); try { return real.getInstantAppCookieMaxBytes(); } catch (Throwable e) { return 0; } }
    @Override public byte[] getInstantAppCookie() { log("getInstantAppCookie"); try { return real.getInstantAppCookie(); } catch (Throwable e) { return null; } }
    @Override public void clearInstantAppCookie() { log("clearInstantAppCookie"); try { real.clearInstantAppCookie(); } catch (Throwable e) {} }
    @Override public void updateInstantAppCookie(byte[] p0) { log("updateInstantAppCookie"); try { real.updateInstantAppCookie(p0); } catch (Throwable e) {} }
    @Override public java.lang.String[] getSystemSharedLibraryNames() { log("getSystemSharedLibraryNames"); try { return real.getSystemSharedLibraryNames(); } catch (Throwable e) { return null; } }
    @Override public java.util.List<android.content.pm.SharedLibraryInfo> getSharedLibraries(int p0) { log("getSharedLibraries"); try { return real.getSharedLibraries(p0); } catch (Throwable e) { return null; } }
    @Override public android.content.pm.ChangedPackages getChangedPackages(int p0) { log("getChangedPackages"); try { return real.getChangedPackages(p0); } catch (Throwable e) { return null; } }
    @Override public android.content.pm.FeatureInfo[] getSystemAvailableFeatures() { log("getSystemAvailableFeatures"); try { return real.getSystemAvailableFeatures(); } catch (Throwable e) { return null; } }
    @Override public boolean hasSystemFeature(java.lang.String p0) { log("hasSystemFeature"); try { return real.hasSystemFeature(p0); } catch (Throwable e) { return false; } }
    @Override public boolean hasSystemFeature(java.lang.String p0, int p1) { log("hasSystemFeature"); try { return real.hasSystemFeature(p0, p1); } catch (Throwable e) { return false; } }
    @Override public android.content.pm.ResolveInfo resolveActivity(android.content.Intent p0, int p1) { log("resolveActivity"); try { return real.resolveActivity(p0, p1); } catch (Throwable e) { return null; } }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryIntentActivities(android.content.Intent p0, int p1) { log("queryIntentActivities"); try { return real.queryIntentActivities(p0, p1); } catch (Throwable e) { return null; } }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryIntentActivityOptions(android.content.ComponentName p0, android.content.Intent[] p1, android.content.Intent p2, int p3) { log("queryIntentActivityOptions"); try { return real.queryIntentActivityOptions(p0, p1, p2, p3); } catch (Throwable e) { return null; } }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryBroadcastReceivers(android.content.Intent p0, int p1) { log("queryBroadcastReceivers"); try { return real.queryBroadcastReceivers(p0, p1); } catch (Throwable e) { return null; } }
    @Override public android.content.pm.ResolveInfo resolveService(android.content.Intent p0, int p1) { log("resolveService"); try { return real.resolveService(p0, p1); } catch (Throwable e) { return null; } }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryIntentServices(android.content.Intent p0, int p1) { log("queryIntentServices"); try { return real.queryIntentServices(p0, p1); } catch (Throwable e) { return null; } }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryIntentContentProviders(android.content.Intent p0, int p1) { log("queryIntentContentProviders"); try { return real.queryIntentContentProviders(p0, p1); } catch (Throwable e) { return null; } }
    @Override public android.content.pm.ProviderInfo resolveContentProvider(java.lang.String p0, int p1) { log("resolveContentProvider"); try { return real.resolveContentProvider(p0, p1); } catch (Throwable e) { return null; } }
    @Override public java.util.List<android.content.pm.ProviderInfo> queryContentProviders(java.lang.String p0, int p1, int p2) { log("queryContentProviders"); try { return real.queryContentProviders(p0, p1, p2); } catch (Throwable e) { return null; } }
    @Override public android.content.pm.InstrumentationInfo getInstrumentationInfo(android.content.ComponentName p0, int p1) { log("getInstrumentationInfo"); try { return real.getInstrumentationInfo(p0, p1); } catch (Throwable e) { return null; } }
    @Override public java.util.List<android.content.pm.InstrumentationInfo> queryInstrumentation(java.lang.String p0, int p1) { log("queryInstrumentation"); try { return real.queryInstrumentation(p0, p1); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getDrawable(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) { log("getDrawable"); try { return real.getDrawable(p0, p1, p2); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getActivityIcon(android.content.ComponentName p0) { log("getActivityIcon"); try { return real.getActivityIcon(p0); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getActivityIcon(android.content.Intent p0) { log("getActivityIcon"); try { return real.getActivityIcon(p0); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getActivityBanner(android.content.ComponentName p0) { log("getActivityBanner"); try { return real.getActivityBanner(p0); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getActivityBanner(android.content.Intent p0) { log("getActivityBanner"); try { return real.getActivityBanner(p0); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getDefaultActivityIcon() { log("getDefaultActivityIcon"); try { return real.getDefaultActivityIcon(); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getApplicationIcon(android.content.pm.ApplicationInfo p0) { log("getApplicationIcon"); try { return real.getApplicationIcon(p0); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getApplicationIcon(java.lang.String p0) { log("getApplicationIcon"); try { return real.getApplicationIcon(p0); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getApplicationBanner(android.content.pm.ApplicationInfo p0) { log("getApplicationBanner"); try { return real.getApplicationBanner(p0); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getApplicationBanner(java.lang.String p0) { log("getApplicationBanner"); try { return real.getApplicationBanner(p0); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getActivityLogo(android.content.ComponentName p0) { log("getActivityLogo"); try { return real.getActivityLogo(p0); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getActivityLogo(android.content.Intent p0) { log("getActivityLogo"); try { return real.getActivityLogo(p0); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getApplicationLogo(android.content.pm.ApplicationInfo p0) { log("getApplicationLogo"); try { return real.getApplicationLogo(p0); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getApplicationLogo(java.lang.String p0) { log("getApplicationLogo"); try { return real.getApplicationLogo(p0); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getUserBadgedIcon(android.graphics.drawable.Drawable p0, android.os.UserHandle p1) { log("getUserBadgedIcon"); try { return real.getUserBadgedIcon(p0, p1); } catch (Throwable e) { return null; } }
    @Override public android.graphics.drawable.Drawable getUserBadgedDrawableForDensity(android.graphics.drawable.Drawable p0, android.os.UserHandle p1, android.graphics.Rect p2, int p3) { log("getUserBadgedDrawableForDensity"); try { return real.getUserBadgedDrawableForDensity(p0, p1, p2, p3); } catch (Throwable e) { return null; } }
    @Override public java.lang.CharSequence getUserBadgedLabel(java.lang.CharSequence p0, android.os.UserHandle p1) { log("getUserBadgedLabel"); try { return real.getUserBadgedLabel(p0, p1); } catch (Throwable e) { return null; } }
    @Override public java.lang.CharSequence getText(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) { log("getText"); try { return real.getText(p0, p1, p2); } catch (Throwable e) { return null; } }
    @Override public android.content.res.XmlResourceParser getXml(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) { log("getXml"); try { return real.getXml(p0, p1, p2); } catch (Throwable e) { return null; } }
    @Override public java.lang.CharSequence getApplicationLabel(android.content.pm.ApplicationInfo p0) { log("getApplicationLabel"); try { return real.getApplicationLabel(p0); } catch (Throwable e) { return null; } }
    @Override public android.content.res.Resources getResourcesForActivity(android.content.ComponentName p0) { log("getResourcesForActivity"); try { return real.getResourcesForActivity(p0); } catch (Throwable e) { return null; } }
    @Override public android.content.res.Resources getResourcesForApplication(android.content.pm.ApplicationInfo p0) { log("getResourcesForApplication"); try { return real.getResourcesForApplication(p0); } catch (Throwable e) { return null; } }
    @Override public android.content.res.Resources getResourcesForApplication(java.lang.String p0) { log("getResourcesForApplication"); try { return real.getResourcesForApplication(p0); } catch (Throwable e) { return null; } }
    @Override public void verifyPendingInstall(int p0, int p1) { log("verifyPendingInstall"); try { real.verifyPendingInstall(p0, p1); } catch (Throwable e) {} }
    @Override public void extendVerificationTimeout(int p0, int p1, long p2) { log("extendVerificationTimeout"); try { real.extendVerificationTimeout(p0, p1, p2); } catch (Throwable e) {} }
    @Override public void setInstallerPackageName(java.lang.String p0, java.lang.String p1) { log("setInstallerPackageName"); try { real.setInstallerPackageName(p0, p1); } catch (Throwable e) {} }
    @Override public java.lang.String getInstallerPackageName(java.lang.String p0) { log("getInstallerPackageName"); try { return real.getInstallerPackageName(p0); } catch (Throwable e) { return null; } }
    @Override public void addPackageToPreferred(java.lang.String p0) { log("addPackageToPreferred"); try { real.addPackageToPreferred(p0); } catch (Throwable e) {} }
    @Override public void removePackageFromPreferred(java.lang.String p0) { log("removePackageFromPreferred"); try { real.removePackageFromPreferred(p0); } catch (Throwable e) {} }
    @Override public java.util.List<android.content.pm.PackageInfo> getPreferredPackages(int p0) { log("getPreferredPackages"); try { return real.getPreferredPackages(p0); } catch (Throwable e) { return null; } }
    @Override public void addPreferredActivity(android.content.IntentFilter p0, int p1, android.content.ComponentName[] p2, android.content.ComponentName p3) { log("addPreferredActivity"); try { real.addPreferredActivity(p0, p1, p2, p3); } catch (Throwable e) {} }
    @Override public void clearPackagePreferredActivities(java.lang.String p0) { log("clearPackagePreferredActivities"); try { real.clearPackagePreferredActivities(p0); } catch (Throwable e) {} }
    @Override public int getPreferredActivities(java.util.List<android.content.IntentFilter> p0, java.util.List<android.content.ComponentName> p1, java.lang.String p2) { log("getPreferredActivities"); try { return real.getPreferredActivities(p0, p1, p2); } catch (Throwable e) { return 0; } }
    @Override public void setComponentEnabledSetting(android.content.ComponentName p0, int p1, int p2) { log("setComponentEnabledSetting"); try { real.setComponentEnabledSetting(p0, p1, p2); } catch (Throwable e) {} }
    @Override public int getComponentEnabledSetting(android.content.ComponentName p0) { log("getComponentEnabledSetting"); try { return real.getComponentEnabledSetting(p0); } catch (Throwable e) { return 0; } }
    @Override public void setApplicationEnabledSetting(java.lang.String p0, int p1, int p2) { log("setApplicationEnabledSetting"); try { real.setApplicationEnabledSetting(p0, p1, p2); } catch (Throwable e) {} }
    @Override public int getApplicationEnabledSetting(java.lang.String p0) { log("getApplicationEnabledSetting"); try { return real.getApplicationEnabledSetting(p0); } catch (Throwable e) { return 0; } }
    @Override public boolean isSafeMode() { log("isSafeMode"); try { return real.isSafeMode(); } catch (Throwable e) { return false; } }
    @Override public void setApplicationCategoryHint(java.lang.String p0, int p1) { log("setApplicationCategoryHint"); try { real.setApplicationCategoryHint(p0, p1); } catch (Throwable e) {} }
    @Override public android.content.pm.PackageInstaller getPackageInstaller() { log("getPackageInstaller"); try { return real.getPackageInstaller(); } catch (Throwable e) { return null; } }
    @Override public boolean canRequestPackageInstalls() { log("canRequestPackageInstalls"); try { return real.canRequestPackageInstalls(); } catch (Throwable e) { return false; } }
}
