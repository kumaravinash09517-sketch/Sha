package com.sarkar.myassistant;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * DeviceAdminReceiver enabling immediate Screen Lock trigger via DevicePolicyManager.
 */
public class MyAdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Toast.makeText(context, "My Assistant Screen Lock Admin Enabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Toast.makeText(context, "My Assistant Screen Lock Admin Disabled", Toast.LENGTH_SHORT).show();
    }
}
