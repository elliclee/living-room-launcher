package io.github.elliclee.livingroom;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public final class UpdateResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){UpdateFlow.receive(context,intent);}
}
