package com.golradreth.randooffline;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // La session reste sauvegardée, mais elle est mise en pause après un redémarrage.
            HikeService.markPausedAfterReboot(context);
        }
    }
}
