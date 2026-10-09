package com.cimacloud;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

public final class FakeContext extends ContextWrapper {
    private final PackageManager pm;

    public FakeContext(Context base) {
        super(base);
        this.pm = new FakePackageManager(base.getPackageManager());
    }

    @Override
    public String getPackageName() {
        return FakePackageManager.PKG;
    }

    @Override
    public String getOpPackageName() {
        return FakePackageManager.PKG;
    }

    @Override
    public PackageManager getPackageManager() {
        return pm;
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        try {
            return pm.getApplicationInfo(FakePackageManager.PKG, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return super.getApplicationInfo();
        }
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }
}
