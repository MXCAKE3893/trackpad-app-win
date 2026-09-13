package jp.pocket.trackpad;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

@SuppressLint("MissingPermission")
public final class MainActivity extends Activity {
    private Hid hid;
    private PadView pad;
    private TextView status;
    private LinearLayout root;
    private boolean pendingDiscovery;
    private TextView discovery;
    private boolean discoveryDialog;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable preparationTimeout = () -> status.setText(
        "HIDの準備が完了しません。スマホの機種・Androidバージョンと、この画面をお知らせください");
    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_SCAN_MODE_CHANGED.equals(intent.getAction())) {
                boolean visible = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.ERROR)
                    == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
                showDiscovery(visible);
            }
        }
    };

    private void showDiscovery(boolean visible) {
        String name = permitted() && hid.adapter != null ? hid.adapter.getName() : "スマホのBluetooth名";
        discovery.setText(visible ? "PCから検出可能：" + name + "\nWindowsでこの名前を探してください（最大300秒）"
            : "検出可能モードではありません。「ペアリング」を押して許可してください");
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(12,18,29)); root.setPadding(20,12,20,12);
        if (Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener((v,insets) -> {
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(20+bars.left,12+bars.top,20+bars.right,12+bars.bottom); return insets;
        });
        else root.setFitsSystemWindows(true);
        setContentView(root);
        TextView title=text("POCKET TRACKPAD",22); title.setTextColor(0xFF5EDFC5); root.addView(title);
        status=text("接続準備を押してください",14); root.addView(status);
        hid=new Hid(this, message -> {
            handler.removeCallbacks(preparationTimeout);
            status.setText(message);
        });
        hid.onReady = () -> {
            handler.removeCallbacks(preparationTimeout);
            if (pendingDiscovery && !discoveryDialog) {
                pendingDiscovery = false;
                discoveryDialog = true;
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,300),3);
            }
        };
        discovery=text("ペアリングするときは、スマホを検出可能にする許可が必要です",12);
        root.addView(discovery);
        IntentFilter scanFilter = new IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver,scanFilter,Context.RECEIVER_EXPORTED);
        else registerReceiver(scanReceiver,scanFilter);
        LinearLayout connection=row();
        button(connection,"接続準備",() -> prepare(false));
        button(connection,"ペアリング",() -> prepare(true));
        button(connection,"PCを選択",this::selectHost);
        pad=new PadView(this,hid);
        root.addView(pad,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout clicks=row();
        holdButton(clicks,"左クリック / 長押しでドラッグ",1);
        holdButton(clicks,"右クリック",2);
        TextView sensitivity=text("カーソル感度",13); root.addView(sensitivity);
        SeekBar seek=new SeekBar(this); seek.setMax(35);
        android.content.SharedPreferences prefs=getPreferences(0);
        seek.setProgress(prefs.getInt("sensitivity",10)); pad.sensitivity=(seek.getProgress()+5)/10f;
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s,int value,boolean user) {
                pad.sensitivity=(value+5)/10f;
                prefs.edit().putInt("sensitivity",value).apply();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        }); root.addView(seek);
        Switch natural=new Switch(this); natural.setText("ナチュラルスクロール");
        natural.setChecked(prefs.getBoolean("natural",true)); pad.natural=natural.isChecked();
        natural.setOnCheckedChangeListener((b,on) -> { pad.natural=on; prefs.edit().putBoolean("natural",on).apply(); });
        root.addView(natural);
        LinearLayout bottom=row(); button(bottom,"操作ガイド",this::help); button(bottom,"切断",() -> { if (permitted()) hid.disconnect(); });
    }
    private TextView text(String value,int size) {
        TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setPadding(4,8,4,8); return v;
    }
    private LinearLayout row() { LinearLayout v=new LinearLayout(this); root.addView(v); return v; }
    private void button(LinearLayout row,String label,Runnable action) {
        Button b=new Button(this); b.setText(label); b.setTextSize(12); row.addView(b,new LinearLayout.LayoutParams(0,-2,1));
        b.setOnClickListener(v -> action.run());
    }
    @SuppressLint("ClickableViewAccessibility")
    private void holdButton(LinearLayout row,String label,int mask) {
        Button b=new Button(this); b.setText(label); b.setTextSize(12); row.addView(b,new LinearLayout.LayoutParams(0,-2,1));
        b.setOnClickListener(v -> hid.click(mask));
        b.setOnTouchListener((v,e) -> {
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN) { hid.button(mask,true); b.setPressed(true); }
            if(e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL) {
                hid.button(mask,false); b.setPressed(false);
            }
            return true;
        });
    }
    private boolean permitted() {
        return Build.VERSION.SDK_INT<31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED);
    }
    private void prepare(boolean discover) {
        pendingDiscovery=discover;
        if(!permitted()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE},1); return;
        }
        if(hid.adapter==null) { status.setText("Bluetooth非対応の端末です"); return; }
        if(!hid.adapter.isEnabled()) { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),2); return; }
        status.setText("Bluetooth HIDの準備中…");
        handler.removeCallbacks(preparationTimeout);
        handler.postDelayed(preparationTimeout,10000);
        hid.start();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==1 && permitted()) prepare(pendingDiscovery);
        else status.setText("付近のデバイスの権限が必要です。接続準備から許可してください");
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==2 && result==RESULT_OK) prepare(pendingDiscovery);
        else if(request==2) status.setText("Bluetoothの有効化がキャンセルされました");
        if(request==3) {
            discoveryDialog=false;
            showDiscovery(result>0);
            if(result<=0) discovery.setText("検出の許可がキャンセルされたか、端末が要求を拒否しました。「ペアリング」から再試行してください");
            else hid.start();
        }
    }
    private void selectHost() {
        if(!permitted() || hid.adapter==null || !hid.adapter.isEnabled()) { prepare(false); return; }
        List<BluetoothDevice> devices=new ArrayList<>(hid.adapter.getBondedDevices());
        if(devices.isEmpty()) { status.setText("ペアリングを押し、WindowsのBluetooth設定からスマホを追加してください"); return; }
        String[] labels=new String[devices.size()];
        for(int i=0;i<labels.length;i++) labels[i]=devices.get(i).getName()+"\n"+devices.get(i).getAddress();
        new AlertDialog.Builder(this).setTitle("接続先のWindows PC").setItems(labels,(d,i) -> hid.connect(devices.get(i)))
            .setNegativeButton("キャンセル",null).show();
    }
    private void help() {
        new AlertDialog.Builder(this).setTitle("操作ガイド").setMessage(
            "1本指：カーソル移動・タップで左クリック\n2回目のタップを保持：ドラッグ\n2本指タップ：右クリック\n2本指移動：縦・横スクロール\nピンチ：Ctrl＋ホイールでズーム\n3本指左右：アプリ切り替え\n3本指上：タスクビュー\n3本指下：デスクトップ表示\n4本指左右：仮想デスクトップ切り替え\n\n左ボタンを押しながらパッドを動かしてもドラッグできます。\nアプリを表示したまま使ってください。ズーム・横スクロールは操作先アプリに依存します。")
            .setPositiveButton("OK",null).show();
    }
    @Override protected void onPause() { if(pad!=null) pad.cancel(); super.onPause(); }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(scanReceiver); if(hid!=null) hid.close(); super.onDestroy();
    }
}
