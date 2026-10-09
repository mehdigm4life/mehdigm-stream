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
        if (PKG.equals(name)) return fakePackageInfo(flags);
        return real.getPackageInfo(name, flags);
    }

    @Override public PackageInfo getPackageInfo(android.content.pm.VersionedPackage vp, int flags) throws PackageManager.NameNotFoundException {
        if (vp != null && PKG.equals(vp.getPackageName())) return fakePackageInfo(flags);
        return real.getPackageInfo(vp, flags);
    }

    @Override public ApplicationInfo getApplicationInfo(java.lang.String name, int flags) throws PackageManager.NameNotFoundException {
        if (PKG.equals(name)) return fakeAppInfo(flags);
        return real.getApplicationInfo(name, flags);
    }

    @Override public List<PackageInfo> getInstalledPackages(int flags) {
        List<PackageInfo> list = new ArrayList<>(real.getInstalledPackages(flags));
        list.add(fakePackageInfo(flags));
        return list;
    }

    @Override public List<ApplicationInfo> getInstalledApplications(int flags) {
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

    @Override public java.lang.String[] currentToCanonicalPackageNames(java.lang.String[] p0) { throw new UnsupportedOperationException(); }
    @Override public java.lang.String[] canonicalToCurrentPackageNames(java.lang.String[] p0) { throw new UnsupportedOperationException(); }
    @Override public android.content.Intent getLaunchIntentForPackage(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public android.content.Intent getLeanbackLaunchIntentForPackage(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public android.content.pm.PermissionInfo getPermissionInfo(java.lang.String p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public java.util.List<android.content.pm.PermissionInfo> queryPermissionsByGroup(java.lang.String p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public android.content.pm.PermissionGroupInfo getPermissionGroupInfo(java.lang.String p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public java.util.List<android.content.pm.PermissionGroupInfo> getAllPermissionGroups(int p0) { throw new UnsupportedOperationException(); }
    @Override public android.content.pm.ActivityInfo getActivityInfo(android.content.ComponentName p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public android.content.pm.ActivityInfo getReceiverInfo(android.content.ComponentName p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public android.content.pm.ServiceInfo getServiceInfo(android.content.ComponentName p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public android.content.pm.ProviderInfo getProviderInfo(android.content.ComponentName p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public java.util.List<android.content.pm.PackageInfo> getPackagesHoldingPermissions(java.lang.String[] p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public int checkPermission(java.lang.String p0, java.lang.String p1) { throw new UnsupportedOperationException(); }
    @Override public boolean isPermissionRevokedByPolicy(java.lang.String p0, java.lang.String p1) { throw new UnsupportedOperationException(); }
    @Override public boolean addPermission(android.content.pm.PermissionInfo p0) { throw new UnsupportedOperationException(); }
    @Override public boolean addPermissionAsync(android.content.pm.PermissionInfo p0) { throw new UnsupportedOperationException(); }
    @Override public void removePermission(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public int checkSignatures(int p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public java.lang.String getNameForUid(int p0) { throw new UnsupportedOperationException(); }
    @Override public boolean isInstantApp() { throw new UnsupportedOperationException(); }
    @Override public boolean isInstantApp(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public int getInstantAppCookieMaxBytes() { throw new UnsupportedOperationException(); }
    @Override public byte[] getInstantAppCookie() { throw new UnsupportedOperationException(); }
    @Override public void clearInstantAppCookie() { throw new UnsupportedOperationException(); }
    @Override public void updateInstantAppCookie(byte[] p0) { throw new UnsupportedOperationException(); }
    @Override public java.lang.String[] getSystemSharedLibraryNames() { throw new UnsupportedOperationException(); }
    @Override public java.util.List<android.content.pm.SharedLibraryInfo> getSharedLibraries(int p0) { throw new UnsupportedOperationException(); }
    @Override public android.content.pm.ChangedPackages getChangedPackages(int p0) { throw new UnsupportedOperationException(); }
    @Override public android.content.pm.FeatureInfo[] getSystemAvailableFeatures() { throw new UnsupportedOperationException(); }
    @Override public boolean hasSystemFeature(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public boolean hasSystemFeature(java.lang.String p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public android.content.pm.ResolveInfo resolveActivity(android.content.Intent p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryIntentActivities(android.content.Intent p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryIntentActivityOptions(android.content.ComponentName p0, android.content.Intent[] p1, android.content.Intent p2, int p3) { throw new UnsupportedOperationException(); }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryBroadcastReceivers(android.content.Intent p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public android.content.pm.ResolveInfo resolveService(android.content.Intent p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryIntentServices(android.content.Intent p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryIntentContentProviders(android.content.Intent p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public android.content.pm.ProviderInfo resolveContentProvider(java.lang.String p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public java.util.List<android.content.pm.ProviderInfo> queryContentProviders(java.lang.String p0, int p1, int p2) { throw new UnsupportedOperationException(); }
    @Override public android.content.pm.InstrumentationInfo getInstrumentationInfo(android.content.ComponentName p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public java.util.List<android.content.pm.InstrumentationInfo> queryInstrumentation(java.lang.String p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getDrawable(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getActivityIcon(android.content.ComponentName p0) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getActivityIcon(android.content.Intent p0) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getActivityBanner(android.content.ComponentName p0) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getActivityBanner(android.content.Intent p0) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getDefaultActivityIcon() { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getApplicationIcon(android.content.pm.ApplicationInfo p0) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getApplicationIcon(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getApplicationBanner(android.content.pm.ApplicationInfo p0) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getApplicationBanner(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getActivityLogo(android.content.ComponentName p0) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getActivityLogo(android.content.Intent p0) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getApplicationLogo(android.content.pm.ApplicationInfo p0) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getApplicationLogo(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getUserBadgedIcon(android.graphics.drawable.Drawable p0, android.os.UserHandle p1) { throw new UnsupportedOperationException(); }
    @Override public android.graphics.drawable.Drawable getUserBadgedDrawableForDensity(android.graphics.drawable.Drawable p0, android.os.UserHandle p1, android.graphics.Rect p2, int p3) { throw new UnsupportedOperationException(); }
    @Override public java.lang.CharSequence getUserBadgedLabel(java.lang.CharSequence p0, android.os.UserHandle p1) { throw new UnsupportedOperationException(); }
    @Override public java.lang.CharSequence getText(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) { throw new UnsupportedOperationException(); }
    @Override public android.content.res.XmlResourceParser getXml(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) { throw new UnsupportedOperationException(); }
    @Override public java.lang.CharSequence getApplicationLabel(android.content.pm.ApplicationInfo p0) { throw new UnsupportedOperationException(); }
    @Override public android.content.res.Resources getResourcesForActivity(android.content.ComponentName p0) { throw new UnsupportedOperationException(); }
    @Override public android.content.res.Resources getResourcesForApplication(android.content.pm.ApplicationInfo p0) { throw new UnsupportedOperationException(); }
    @Override public android.content.res.Resources getResourcesForApplication(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public void verifyPendingInstall(int p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public void extendVerificationTimeout(int p0, int p1, long p2) { throw new UnsupportedOperationException(); }
    @Override public void setInstallerPackageName(java.lang.String p0, java.lang.String p1) { throw new UnsupportedOperationException(); }
    @Override public java.lang.String getInstallerPackageName(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public void addPackageToPreferred(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public void removePackageFromPreferred(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public java.util.List<android.content.pm.PackageInfo> getPreferredPackages(int p0) { throw new UnsupportedOperationException(); }
    @Override public void addPreferredActivity(android.content.IntentFilter p0, int p1, android.content.ComponentName[] p2, android.content.ComponentName p3) { throw new UnsupportedOperationException(); }
    @Override public void clearPackagePreferredActivities(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public int getPreferredActivities(java.util.List<android.content.IntentFilter> p0, java.util.List<android.content.ComponentName> p1, java.lang.String p2) { throw new UnsupportedOperationException(); }
    @Override public void setComponentEnabledSetting(android.content.ComponentName p0, int p1, int p2) { throw new UnsupportedOperationException(); }
    @Override public int getComponentEnabledSetting(android.content.ComponentName p0) { throw new UnsupportedOperationException(); }
    @Override public void setApplicationEnabledSetting(java.lang.String p0, int p1, int p2) { throw new UnsupportedOperationException(); }
    @Override public int getApplicationEnabledSetting(java.lang.String p0) { throw new UnsupportedOperationException(); }
    @Override public boolean isSafeMode() { throw new UnsupportedOperationException(); }
    @Override public void setApplicationCategoryHint(java.lang.String p0, int p1) { throw new UnsupportedOperationException(); }
    @Override public android.content.pm.PackageInstaller getPackageInstaller() { throw new UnsupportedOperationException(); }
    @Override public boolean canRequestPackageInstalls() { throw new UnsupportedOperationException(); }
}
