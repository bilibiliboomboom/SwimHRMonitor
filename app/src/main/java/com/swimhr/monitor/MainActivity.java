package com.swimhr.monitor;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;

public class MainActivity extends Activity {
    static final UUID HR=UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"), MEAS=UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb"), CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), BAT=UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"), LEVEL=UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    final Handler h=new Handler(Looper.getMainLooper());
    final Map<String,BluetoothGatt> gatts=new LinkedHashMap<>();
    final Map<String,Sensor> sensors=new LinkedHashMap<>();
    final Map<String,ArrayList<Point>> live=new LinkedHashMap<>();
    final ArrayList<String> scanFound=new ArrayList<>();
    final Map<String,String> scanNames=new LinkedHashMap<>();
    final Set<String> userDisconnected=new HashSet<>();
    final Map<String,Integer> reconnectAttempts=new HashMap<>();
    final Map<String,Runnable> reconnectTasks=new HashMap<>();
    final Map<String,Runnable> connectTimeouts=new HashMap<>();
    BluetoothAdapter adapter; BluetoothLeScanner scanner; boolean scanning=false, aligned=false;
    long alignedStart=0; volatile String activeSession=""; Store store; LinearLayout root; GridLayout deviceBox; TextView summary,clock; int gridColumns=2; Button scanBtn,recordBtn; Curve curve; TabHost tabs;
    static class Point { long t; int bpm; Point(long t,int b){this.t=t;this.bpm=b;} }
    static class Sensor { String id,name; int bpm=-1,battery=-1; long last=0; String state="未连接"; Sensor(String i,String n){id=i;name=n;} }
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(128);setRequestedOrientation(0);store=new Store(this); BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);adapter=bm==null?null:bm.getAdapter(); build(); permissions(); }
    int d(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    TextView label(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.rgb(30,43,59));return t;}
    Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(13);b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(d(8),0,d(8),0);if(Build.VERSION.SDK_INT>=21){b.setStateListAnimator(null);b.setElevation(0);}return b;}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    int color(String hex){return Color.parseColor(hex);}
    GradientDrawable panel(String fill,String stroke){GradientDrawable g=new GradientDrawable();g.setColor(color(fill));g.setCornerRadius(d(14));g.setStroke(d(1),color(stroke));return g;}
    TextView txt(String s,int size,String col){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color(col));return t;}
    void build(){
        getWindow().setStatusBarColor(color("#101923"));getWindow().setNavigationBarColor(color("#101923"));
        root=column();root.setPadding(d(14),d(8),d(14),d(8));root.setBackgroundColor(color("#101923"));
        LinearLayout top=row();TextView brand=txt("SWIM  /  HR",22,"#F4F8FC");brand.setTypeface(null,1);top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        clock=txt("00:00:00",14,"#91A4B8");top.addView(clock);root.addView(top);
        TextView subtitle=txt("MULTI-ATHLETE LIVE MONITOR",10,"#6E849B");subtitle.setLetterSpacing(.16f);root.addView(subtitle);
        LinearLayout status=row();status.setPadding(0,d(8),0,d(10));
        summary=txt("等待连接心率设备",12,"#AFC0D0");status.addView(summary,new LinearLayout.LayoutParams(0,-2,1));
        TextView liveTag=txt("● LIVE",11,"#39D99A");status.addView(liveTag);root.addView(status);
        LinearLayout actions=row();scanBtn=button("＋ 添加设备");scanBtn.setTextColor(color("#FFFFFF"));scanBtn.setBackground(panel("#176B55","#23866C"));scanBtn.setOnClickListener(v->scan());
        actions.addView(scanBtn,new LinearLayout.LayoutParams(0,d(44),1));
        recordBtn=button("▶ 开始同步");recordBtn.setTextColor(color("#FFFFFF"));recordBtn.setBackground(panel("#245B9B","#3478BD"));recordBtn.setOnClickListener(v->toggleAligned());
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,d(44),1);rp.leftMargin=d(8);actions.addView(recordBtn,rp);
        Button moreBtn=button("⋯");moreBtn.setTextColor(color("#E6EEF7"));moreBtn.setBackground(panel("#202D3B","#34475B"));LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(d(54),d(44));mp.leftMargin=d(8);actions.addView(moreBtn,mp);moreBtn.setOnClickListener(v->more());root.addView(actions);
        LinearLayout nav=row();nav.setPadding(0,d(8),0,d(8));
        String[] navs={"实时监测","曲线","训练记录","数据分析"};
        for(int i=0;i<navs.length;i++){final int ix=i;Button b=button(navs[i]);b.setTextSize(11);b.setTextColor(color(i==0?"#5BE0AE":"#A9B8C8"));b.setBackground(panel(i==0?"#1B342F":"#17232F",i==0?"#2E765F":"#263747"));LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,d(38),1);if(i>0)np.leftMargin=d(5);nav.addView(b,np);b.setOnClickListener(v->{if(ix==1)showCurve();else if(ix==2)history();else if(ix==3)analysis();});}
        root.addView(nav);
        TextView section=txt("运动员心率  /  ATHLETE HEART RATE",11,"#7F94A9");section.setPadding(d(2),d(8),0,d(8));root.addView(section);
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);
        LinearLayout monitorContent=column();monitorContent.setPadding(0,0,0,d(10));
        deviceBox=new GridLayout(this);deviceBox.setAlignmentMode(GridLayout.ALIGN_BOUNDS);deviceBox.setUseDefaultMargins(false);
        monitorContent.addView(deviceBox,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout chartPanel=column();chartPanel.setPadding(d(12),d(10),d(12),d(10));chartPanel.setBackground(panel("#172431","#2B4052"));
        LinearLayout chartHead=row();TextView chartTitle=txt("实时心率趋势",13,"#EAF2F8");chartTitle.setTypeface(null,1);chartHead.addView(chartTitle,new LinearLayout.LayoutParams(0,-2,1));
        TextView chartWindow=txt("最近 2 分钟",10,"#8298AD");chartHead.addView(chartWindow);chartPanel.addView(chartHead);
        curve=new Curve(this);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,d(210));cp.topMargin=d(6);chartPanel.addView(curve,cp);
        monitorContent.addView(chartPanel,new LinearLayout.LayoutParams(-1,-2));
        sv.addView(monitorContent);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        TextView foot=txt("数据仅用于训练观察  ·  BLE 心率设备",9,"#52677C");foot.setGravity(Gravity.CENTER);foot.setPadding(0,d(7),0,0);root.addView(foot);
        setContentView(root);renderSensors();refresh();
        h.post(new Runnable(){public void run(){clock.setText(new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date()));h.postDelayed(this,1000);}});
    }
    void permissions(){ArrayList<String> p=new ArrayList<>();if(Build.VERSION.SDK_INT>=31){if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=0)p.add(Manifest.permission.BLUETOOTH_SCAN);if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=0)p.add(Manifest.permission.BLUETOOTH_CONNECT);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=0)p.add(Manifest.permission.POST_NOTIFICATIONS);}else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=0)p.add(Manifest.permission.ACCESS_FINE_LOCATION);if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),88);}
    boolean permitted(){return Build.VERSION.SDK_INT>=31?checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==0&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==0:checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==0;}
    void scan(){if(scanning){stopScan();return;}if(!permitted()){permissions();return;}if(adapter==null||!adapter.isEnabled()){toast("请打开蓝牙");return;}scanner=adapter.getBluetoothLeScanner();if(scanner==null){toast("无法启动 BLE 扫描");return;}scanFound.clear();scanNames.clear();try{scanner.startScan(scanCb);scanning=true;scanBtn.setText("停止扫描");toast("正在搜索标准心率设备");h.postDelayed(()->{if(scanning)stopScan();},15000);}catch(Exception e){toast("扫描失败："+e.getMessage());}}
    void stopScan(){boolean was=scanning;try{if(scanner!=null&&scanning)scanner.stopScan(scanCb);}catch(Exception ignored){}scanning=false;if(scanBtn!=null)scanBtn.setText("＋ 添加设备");if(was&&!scanFound.isEmpty()&&!isFinishing())showScanResults();}
    void showScanResults(){
        ArrayList<String> ids=new ArrayList<>(scanFound);if(ids.isEmpty())return;
        String[] labels=new String[ids.size()];boolean[] checked=new boolean[ids.size()];
        for(int i=0;i<ids.size();i++){Sensor s=sensors.get(ids.get(i));labels[i]=(s==null?"BLE 心率设备":s.name)+"\n"+ids.get(i);checked[i]=true;}
        new AlertDialog.Builder(this).setTitle("发现 "+ids.size()+" 台心率设备").setMultiChoiceItems(labels,checked,(dialog,which,isChecked)->checked[which]=isChecked)
            .setPositiveButton("连接所选",(dialog,which)->{int delay=0;for(int i=0;i<ids.size();i++)if(checked[i]){final String id=ids.get(i);h.postDelayed(()->connect(id),delay);delay+=900;}})
            .setNegativeButton("稍后",null).show();
    }
    ScanCallback scanCb=new ScanCallback(){@Override public void onScanResult(int type,ScanResult r){
        BluetoothDevice dev=r.getDevice();String id;String name;
        try{id=dev.getAddress();name=dev.getName();}catch(Exception e){return;}
        if(gatts.containsKey(id)||scanFound.contains(id))return;
        boolean hr=false;if(r.getScanRecord()!=null&&r.getScanRecord().getServiceUuids()!=null)
            for(android.os.ParcelUuid u:r.getScanRecord().getServiceUuids())if(HR.equals(u.getUuid()))hr=true;
        if(!hr)return;
        scanFound.add(id);scanNames.put(id,name);
        String saved=store.savedName(id);
        Sensor sensor=new Sensor(id,saved==null?"运动员 "+String.format(Locale.getDefault(),"%02d",sensors.size()+1):saved);
        sensors.put(id,sensor);runOnUiThread(()->{renderSensors();refresh();});
    }};
    void connect(String id){
        if(!permitted()){permissions();return;}
        Sensor sensor=sensors.get(id);if(sensor==null)return;
        if(activeSession.isEmpty())activeSession=store.newSession("自动监听",System.currentTimeMillis());
        userDisconnected.remove(id);
        Runnable pending=reconnectTasks.remove(id);if(pending!=null)h.removeCallbacks(pending);
        Runnable timeout=connectTimeouts.remove(id);if(timeout!=null)h.removeCallbacks(timeout);
        BluetoothGatt old=gatts.remove(id);if(old!=null){try{old.disconnect();old.close();}catch(Exception ignored){}}
        sensor.bpm=-1;sensor.state="正在连接…";renderSensors();refresh();
        try{
            BluetoothDevice dev=adapter.getRemoteDevice(id);
            BluetoothGatt g=Build.VERSION.SDK_INT>=23?dev.connectGatt(this,false,new Gatt(id),BluetoothDevice.TRANSPORT_LE):dev.connectGatt(this,false,new Gatt(id));
            if(g==null){sensor.state="连接启动失败";scheduleReconnect(id);return;}
            gatts.put(id,g);
            scheduleConnectTimeout(id,g,"连接超时");
        }catch(Exception e){sensor.state="连接失败："+e.getClass().getSimpleName();scheduleReconnect(id);}
        renderSensors();refresh();
    }
    void scheduleConnectTimeout(String id,BluetoothGatt g,String reason){
        Runnable old=connectTimeouts.remove(id);if(old!=null)h.removeCallbacks(old);
        Runnable r=()->{if(gatts.get(id)==g){Sensor s=sensors.get(id);if(s!=null&&(s.state.contains("连接")||s.state.contains("发现")||s.state.contains("订阅"))){s.state=reason;closeGatt(id,g);scheduleReconnect(id);renderSensors();refresh();}}};
        connectTimeouts.put(id,r);h.postDelayed(r,18000);
    }
    void clearConnectTimeout(String id){Runnable r=connectTimeouts.remove(id);if(r!=null)h.removeCallbacks(r);}
    void closeGatt(String id,BluetoothGatt g){if(gatts.get(id)==g)gatts.remove(id);try{g.disconnect();}catch(Exception ignored){}try{g.close();}catch(Exception ignored){}}
    void scheduleReconnect(String id){
        if(userDisconnected.contains(id)||!sensors.containsKey(id)||isFinishing())return;
        Runnable existing=reconnectTasks.get(id);if(existing!=null)return;
        int n=Math.min(reconnectAttempts.getOrDefault(id,0)+1,6);reconnectAttempts.put(id,n);
        long delay=Math.min(30000L,1500L*(1L<<(n-1)));
        Sensor sensor=sensors.get(id);if(sensor!=null)sensor.state="断线，"+(delay/1000)+" 秒后重连";
        Runnable task=()->{reconnectTasks.remove(id);if(!userDisconnected.contains(id)&&sensors.containsKey(id)&&!gatts.containsKey(id))connect(id);};
        reconnectTasks.put(id,task);h.postDelayed(task,delay);renderSensors();refresh();
    }
    class Gatt extends BluetoothGattCallback {
        final String id;Gatt(String i){id=i;}
        boolean current(BluetoothGatt g){return gatts.get(id)==g&&!userDisconnected.contains(id);}
        public void onConnectionStateChange(BluetoothGatt g,int status,int state){
            h.post(()->{
                if(!current(g)){try{g.close();}catch(Exception ignored){}return;}
                if(state==BluetoothProfile.STATE_CONNECTED&&status==BluetoothGatt.GATT_SUCCESS){
                    Sensor s=sensors.get(id);if(s!=null)s.state="已连接，正在发现服务…";
                    scheduleConnectTimeout(id,g,"服务发现超时");
                    boolean ok=false;try{ok=g.discoverServices();}catch(Exception ignored){}
                    if(!ok){closeGatt(id,g);if(s!=null)s.state="服务发现启动失败";scheduleReconnect(id);}
                }else if(state==BluetoothProfile.STATE_DISCONNECTED){
                    clearConnectTimeout(id);closeGatt(id,g);
                    Sensor s=sensors.get(id);if(s!=null){s.bpm=-1;s.state=status==0?"连接已断开":"连接异常 ("+status+")";}
                    scheduleReconnect(id);
                }
                renderSensors();refresh();
            });
        }
        public void onServicesDiscovered(BluetoothGatt g,int status){
            if(!current(g))return;
            if(status!=BluetoothGatt.GATT_SUCCESS){h.post(()->{closeGatt(id,g);setState(id,"服务发现失败 ("+status+")");scheduleReconnect(id);});return;}
            BluetoothGattService service=g.getService(HR);
            BluetoothGattCharacteristic c=service==null?null:service.getCharacteristic(MEAS);
            if(c==null){h.post(()->{closeGatt(id,g);setState(id,"未找到心率特征");scheduleReconnect(id);});return;}
            try{
                if(!g.setCharacteristicNotification(c,true)){h.post(()->{closeGatt(id,g);setState(id,"本地通知启用失败");scheduleReconnect(id);});return;}
                BluetoothGattDescriptor desc=c.getDescriptor(CCCD);
                if(desc==null){h.post(()->{closeGatt(id,g);setState(id,"缺少通知描述符");scheduleReconnect(id);});return;}
                byte[] value=(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0&&
                    (c.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0?
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE:BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                if(Build.VERSION.SDK_INT>=33){int result=g.writeDescriptor(desc,value);if(result!=BluetoothStatusCodes.SUCCESS)throw new IllegalStateException("writeDescriptor="+result);}
                else{desc.setValue(value);if(!g.writeDescriptor(desc))throw new IllegalStateException("writeDescriptor failed");}
                h.post(()->{Sensor s=sensors.get(id);if(s!=null)s.state="正在启用心率通知…";scheduleConnectTimeout(id,g,"通知订阅超时");renderSensors();refresh();});
            }catch(Exception e){h.post(()->{closeGatt(id,g);setState(id,"通知订阅失败");scheduleReconnect(id);});}
        }
        public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){
            if(!current(g)||!CCCD.equals(d.getUuid()))return;
            h.post(()->{
                if(status!=BluetoothGatt.GATT_SUCCESS){closeGatt(id,g);setState(id,"通知订阅失败 ("+status+")");scheduleReconnect(id);return;}
                clearConnectTimeout(id);reconnectAttempts.remove(id);
                Sensor s=sensors.get(id);if(s!=null)s.state="已连接，等待心率数据";
                // GATT operations must be serialized: read battery only after CCCD write completes.
                BluetoothGattService bs=g.getService(BAT);
                BluetoothGattCharacteristic bc=bs==null?null:bs.getCharacteristic(LEVEL);
                if(bc!=null){try{if(!g.readCharacteristic(bc)&&s!=null)s.state="已连接，电量读取暂不可用";}catch(Exception ignored){}}
                renderSensors();refresh();
            });
        }
        public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){if(current(g)&&MEAS.equals(c.getUuid()))parse(id,c.getValue());}
        public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){if(current(g)&&MEAS.equals(c.getUuid()))parse(id,value);}
        public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int status){
            if(!current(g)||status!=BluetoothGatt.GATT_SUCCESS||!LEVEL.equals(c.getUuid()))return;
            byte[] v=c.getValue();if(v!=null&&v.length>0)h.post(()->{Sensor s=sensors.get(id);if(s!=null){s.battery=v[0]&255;renderSensors();}});
        }
    }
    void setState(String id,String state){runOnUiThread(()->{Sensor s=sensors.get(id);if(s!=null)s.state=state;renderSensors();refresh();});}
    void parse(String id,byte[] v){
        if(v==null||v.length<2)return;
        int flags=v[0]&255;int bpm=(flags&1)!=0&&v.length>=3?((v[1]&255)|((v[2]&255)<<8)):(v[1]&255);
        if(bpm<1||bpm>250)return;
        long now=System.currentTimeMillis();
        // Serialize all device callbacks onto the UI/state thread; independent GATT callback
        // threads must not concurrently mutate the sensor maps, charts, or session state.
        h.post(()->{
            Sensor sensor=sensors.get(id);if(sensor==null||userDisconnected.contains(id))return;
            sensor.bpm=bpm;sensor.last=now;sensor.state="实时接收";
            if(!activeSession.isEmpty())store.sample(activeSession,id,sensor.name,now,bpm);
            ArrayList<Point> list=live.computeIfAbsent(id,k->new ArrayList<>());list.add(new Point(now,bpm));while(list.size()>240)list.remove(0);
            renderSensors();if(curve!=null)curve.invalidate();refresh();
        });
    }
    void renderSensors(){
        if(deviceBox==null)return;deviceBox.removeAllViews();
        int width=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);
        gridColumns=width>=1450?4:width>=980?3:2;deviceBox.setColumnCount(gridColumns);
        int index=0;
        for(Sensor sensor:sensors.values()){
            boolean fresh=sensor.bpm>0&&System.currentTimeMillis()-sensor.last<15000;
            LinearLayout card=column();card.setPadding(d(14),d(12),d(14),d(10));card.setMinimumHeight(d(174));
            card.setBackground(panel(fresh?"#17352F":"#1A2735",fresh?"#327B64":"#304356"));
            LinearLayout head=row();
            TextView name=txt(sensor.name,15,"#F1F5F9");name.setTypeface(null,1);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            head.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            TextView menu=txt("⋮",22,"#9EB0C2");menu.setGravity(Gravity.CENTER);menu.setPadding(d(12),0,0,0);menu.setOnClickListener(v->deviceMenu(sensor));head.addView(menu,new LinearLayout.LayoutParams(-2,d(28)));
            card.addView(head);
            TextView bpm=txt(sensor.bpm>0?String.valueOf(sensor.bpm):"--",46,fresh?"#55E0A9":"#D4DEE8");bpm.setTypeface(null,1);bpm.setGravity(Gravity.CENTER);bpm.setPadding(0,d(5),0,0);card.addView(bpm);
            TextView unit=txt("BPM",10,"#91A5B8");unit.setGravity(Gravity.CENTER);card.addView(unit);
            TextView status=txt(fresh?"● 实时接收":(sensor.bpm>0?"○ 信号暂时中断":"○ "+sensor.state),10,fresh?"#56D9A7":"#A4B4C4");status.setGravity(Gravity.CENTER);status.setPadding(0,d(5),0,d(7));card.addView(status);
            View divider=new View(this);divider.setBackgroundColor(color("#304456"));card.addView(divider,new LinearLayout.LayoutParams(-1,d(1)));
            LinearLayout foot=row();TextView battery=txt(sensor.battery>=0?"电量 "+sensor.battery+"%":"电量 --",10,"#8298AD");foot.addView(battery,new LinearLayout.LayoutParams(0,-2,1));
            String age=sensor.last>0?Math.max(0,(System.currentTimeMillis()-sensor.last)/1000)+"s 前":"尚无数据";
            TextView seen=txt(age,10,"#8298AD");foot.addView(seen);card.addView(foot);
            GridLayout.LayoutParams gp=new GridLayout.LayoutParams(GridLayout.spec(index/gridColumns),GridLayout.spec(index%gridColumns,1f));gp.width=0;gp.height=-2;gp.setMargins(d(5),d(5),d(5),d(5));deviceBox.addView(card,gp);index++;
        }
        if(sensors.isEmpty()){
            TextView empty=txt("准备开始训练监测？\n\n点击上方「添加设备」，扫描已开启心率广播的小米手环。\n\n建议先给每只手环命名为孩子姓名，方便教练同时观察。",15,"#91A5B9");
            empty.setGravity(Gravity.CENTER);empty.setPadding(d(24),d(48),d(24),d(48));
            GridLayout.LayoutParams ep=new GridLayout.LayoutParams();ep.width=-1;ep.height=-2;ep.columnSpec=GridLayout.spec(0,gridColumns);deviceBox.addView(empty,ep);
        }
    }
    void deviceMenu(Sensor sensor){
        String[] actions=gatts.containsKey(sensor.id)?new String[]{"修改运动员姓名","断开设备","移除设备"}:new String[]{"修改运动员姓名","连接设备","移除设备"};
        new AlertDialog.Builder(this).setTitle(sensor.name).setItems(actions,(dialog,which)->{
            if(which==0)rename(sensor);
            else if(which==1){if(gatts.containsKey(sensor.id))disconnect(sensor.id);else connect(sensor.id);}
            else{disconnect(sensor.id);sensors.remove(sensor.id);live.remove(sensor.id);renderSensors();refresh();}
        }).show();
    }
    void rename(Sensor s){EditText e=new EditText(this);e.setText(s.name);new AlertDialog.Builder(this).setTitle("运动员 / 设备名称").setView(e).setPositiveButton("保存",(a,b)->{s.name=e.getText().toString().trim();if(s.name.isEmpty())s.name="运动员";store.rename(s.id,s.name);renderSensors();}).setNegativeButton("取消",null).show();}
    void disconnect(String id){
        userDisconnected.add(id);
        Runnable task=reconnectTasks.remove(id);if(task!=null)h.removeCallbacks(task);
        clearConnectTimeout(id);reconnectAttempts.remove(id);
        BluetoothGatt g=gatts.remove(id);if(g!=null){try{g.disconnect();}catch(Exception ignored){}try{g.close();}catch(Exception ignored){}}
        Sensor s=sensors.get(id);if(s!=null){s.state="已手动断开";s.bpm=-1;s.last=0;}
        renderSensors();refresh();
    }
    void toggleAligned(){if(!aligned){if(gatts.size()<2){toast("至少连接两台设备才能开始同步对比记录");return;}aligned=true;alignedStart=System.currentTimeMillis();activeSession=store.newSession("同步对比",alignedStart);recordBtn.setText("停止同步记录");toast("同步记录已开始");}else{aligned=false;store.endSession(activeSession,System.currentTimeMillis());activeSession="";recordBtn.setText("开始同步记录");new AlertDialog.Builder(this).setTitle("记录已保存").setMessage("本次同步记录已保存在本机。").setItems(new String[]{"导出 Excel","导出 CSV","分析数据","查看历史","完成"},(a,w)->{if(w==0)export(true);else if(w==1)export(false);else if(w==2)analysis();else if(w==3)history();}).show();}}
    void more(){String[] items={"实时曲线","历史记录","数据分析","导出 CSV","导出 Excel","断开全部设备","关于 / 使用说明"};new AlertDialog.Builder(this).setTitle("功能").setItems(items,(a,w)->{if(w==0)showCurve();if(w==1)history();if(w==2)analysis();if(w==3)export(false);if(w==4)export(true);if(w==5){for(String id:new ArrayList<>(gatts.keySet()))disconnect(id);}if(w==6)new AlertDialog.Builder(this).setTitle("SWIM HR").setMessage("支持标准 BLE 心率服务 0x180D。单设备连接后自动记录；两台及以上设备可启动同步对比记录。数据默认保存在本机。仅用于训练观察和设备评估，不用于医疗诊断。").setPositiveButton("知道了",null).show();}).show();}
    void showCurve(){LinearLayout box=column();curve=new Curve(this);box.addView(curve,new LinearLayout.LayoutParams(-1,d(310)));new AlertDialog.Builder(this).setTitle("实时心率曲线").setView(box).setPositiveButton("关闭",null).show();}
    class Curve extends View{
        final Paint p=new Paint(3);
        final int[] colors={0xff55d9a7,0xff66a9f2,0xffffbd68,0xffc89af2,0xffff8585,0xff63cbd1,0xffe4df70,0xff8da8ff};
        Curve(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        protected void onDraw(Canvas c){
            super.onDraw(c);float w=getWidth(),hh=getHeight();c.drawColor(0xff172431);
            float left=d(34),right=w-d(8),top=d(10),bottom=hh-d(24);
            p.setStrokeWidth(1);p.setColor(0xff304354);
            for(int bpm=40;bpm<=200;bpm+=40){float y=bottom-(bpm-40)/160f*(bottom-top);c.drawLine(left,y,right,y,p);p.setColor(0xff91a5b8);p.setTextSize(d(9));c.drawText(String.valueOf(bpm),d(2),y+d(3),p);p.setColor(0xff304354);}
            p.setTextSize(d(9));p.setColor(0xff8298ad);c.drawText("-2 min",left,bottom+d(17),p);c.drawText("现在",right-d(24),bottom+d(17),p);
            long now=System.currentTimeMillis(),min=now-120000L;int ix=0;
            for(Map.Entry<String,ArrayList<Point>> entry:live.entrySet()){
                int col=colors[ix++%colors.length];ArrayList<Point> pts=entry.getValue();p.setColor(col);p.setStrokeWidth(d(2.3f));p.setStyle(Paint.Style.STROKE);
                Point prev=null;for(Point pt:pts){if(pt.t<min||pt.t>now)continue;if(prev!=null&&pt.t-prev.t<=15000){float x1=left+(prev.t-min)/120000f*(right-left),x2=left+(pt.t-min)/120000f*(right-left);float y1=bottom-(prev.bpm-40)/160f*(bottom-top),y2=bottom-(pt.bpm-40)/160f*(bottom-top);c.drawLine(x1,y1,x2,y2,p);}prev=pt;}
                p.setStyle(Paint.Style.FILL);
            }
        }
    }
    void history(){ArrayList<String> rows=store.sessions();if(rows.isEmpty()){toast("暂无历史记录");return;}new AlertDialog.Builder(this).setTitle("历史训练记录").setItems(rows.toArray(new String[0]),(a,w)->{String id=rows.get(w).split("  ")[0];activeSession=id;analysis();}).setNegativeButton("关闭",null).show();}
    void analysis(){ArrayList<String> ids=store.deviceIds(activeSession);if(ids.size()<2){toast("当前记录中至少需要两台设备的数据");return;}final ArrayList<String> compareIds=ids;String[] names=new String[compareIds.size()];for(int i=0;i<compareIds.size();i++){Sensor s=sensors.get(compareIds.get(i));names[i]=s==null?compareIds.get(i):s.name;}new AlertDialog.Builder(this).setTitle("选择参考设备").setItems(names,(a,w)->{String ref=compareIds.get(w);String other=compareIds.get(w==0?1:0);Stats st=store.compare(activeSession,ref,other);if(st.n<3){toast("有效配对样本不足（至少3组）");return;}String msg="样本数："+st.n+"\nPearson 相关系数 r："+String.format(Locale.US,"%.4f",st.r)+"\n平均差值（A-B）："+String.format(Locale.US,"%.2f",st.bias)+" BPM\n95% 一致性界限："+String.format(Locale.US,"%.2f",st.low)+" 至 "+String.format(Locale.US,"%.2f",st.high)+" BPM\n\nPearson 衡量线性相关；Bland-Altman 衡量一致性。结果受采样频率和时间对齐影响。";new AlertDialog.Builder(this).setTitle("设备对比分析").setMessage(msg).setPositiveButton("确定",null).show();}).setNegativeButton("取消",null).show();}
    static class Stats{int n;double r,bias,low,high;}
    void export(boolean xlsx){String mime=xlsx?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv";String name="swimhr_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+(xlsx?".xlsx":".csv");Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);i.putExtra(Intent.EXTRA_TITLE,name);exportXlsx=xlsx;startActivityForResult(i,91);}
    boolean exportXlsx=false;
    @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req==91&&res==RESULT_OK&&data!=null){try{OutputStream out=getContentResolver().openOutputStream(data.getData());if(exportXlsx)writeXlsx(out);else writeCsv(out);out.close();toast("导出完成");}catch(Exception e){toast("导出失败："+e.getMessage());}}}
    void writeCsv(OutputStream out)throws Exception{StringBuilder b=new StringBuilder("session_id,timestamp,device_id,device_name,bpm\n");Cursor c=store.getReadableDatabase().rawQuery("SELECT session_id,ts,device_id,name,bpm FROM samples ORDER BY ts",null);while(c.moveToNext())b.append(csv(c.getString(0))).append(',').append(c.getLong(1)).append(',').append(csv(c.getString(2))).append(',').append(csv(c.getString(3))).append(',').append(c.getInt(4)).append('\n');c.close();out.write(b.toString().getBytes("UTF-8"));}
    String csv(String s){return "\""+(s==null?"":s.replace("\"","\"\""))+"\"";}
    void writeXlsx(OutputStream out)throws Exception{
        ZipOutputStream z=new ZipOutputStream(out);
        put(z,"[Content_Types].xml","<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
        put(z,"_rels/.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        put(z,"xl/workbook.xml","<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"HeartRate\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        put(z,"xl/_rels/workbook.xml.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
        StringBuilder sh=new StringBuilder("<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        String[] headers={"session_id","timestamp","device_id","device_name","bpm"};
        sh.append("<row r=\"1\">");for(int i=0;i<headers.length;i++)xlsxCell(sh,i+1,1,headers[i]);sh.append("</row>");
        Cursor cur=store.getReadableDatabase().rawQuery("SELECT session_id,ts,device_id,name,bpm FROM samples ORDER BY ts",null);
        int r=2;while(cur.moveToNext()){sh.append("<row r=\"").append(r).append("\">");xlsxCell(sh,1,r,cur.getString(0));xlsxCell(sh,2,r,String.valueOf(cur.getLong(1)));xlsxCell(sh,3,r,cur.getString(2));xlsxCell(sh,4,r,cur.getString(3));xlsxCell(sh,5,r,String.valueOf(cur.getInt(4)));sh.append("</row>");r++;}cur.close();
        sh.append("</sheetData></worksheet>");put(z,"xl/worksheets/sheet1.xml",sh.toString());z.finish();
    }
    void xlsxCell(StringBuilder sh,int col,int row,String value){sh.append("<c r=\"").append(colName(col)).append(row).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(value==null?"":value)).append("</t></is></c>");}
    void put(ZipOutputStream z,String n,String v)throws Exception{z.putNextEntry(new ZipEntry(n));z.write(v.getBytes("UTF-8"));z.closeEntry();}
    String xml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    String colName(int n){String s="";while(n>0){int r=(n-1)%26;s=(char)('A'+r)+s;n=(n-1)/26;}return s;}
    void refresh(){int active=0,ready=0;for(Sensor s:sensors.values()){if(s.bpm>0&&System.currentTimeMillis()-s.last<15000)active++;if("实时接收".equals(s.state)||"已连接，等待心率数据".equals(s.state))ready++;}summary.setText("设备 "+sensors.size()+"  ·  通知就绪 "+ready+"  ·  正在更新 "+active+(aligned?"  ·  ● 训练记录中":""));}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override protected void onDestroy(){stopScan();for(Runnable r:reconnectTasks.values())h.removeCallbacks(r);for(Runnable r:connectTimeouts.values())h.removeCallbacks(r);reconnectTasks.clear();connectTimeouts.clear();for(BluetoothGatt g:new ArrayList<>(gatts.values())){try{g.disconnect();}catch(Exception ignored){}try{g.close();}catch(Exception ignored){}}gatts.clear();super.onDestroy();}
    class Store extends SQLiteOpenHelper{
        Store(Context c){super(c,"swimhr.db",null,2);}
        public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,title TEXT,start INTEGER,end INTEGER DEFAULT 0)");db.execSQL("CREATE TABLE samples(_id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT,ts INTEGER,device_id TEXT,name TEXT,bpm INTEGER)");db.execSQL("CREATE INDEX sample_session_time ON samples(session_id,ts)");db.execSQL("CREATE TABLE names(device_id TEXT PRIMARY KEY,name TEXT)");}
        public void onUpgrade(SQLiteDatabase db,int a,int b){}
        String newSession(String title,long t){String id=UUID.randomUUID().toString();ContentValues v=new ContentValues();v.put("id",id);v.put("title",title);v.put("start",t);getWritableDatabase().insert("sessions",null,v);return id;}
        void endSession(String id,long t){ContentValues v=new ContentValues();v.put("end",t);getWritableDatabase().update("sessions",v,"id=?",new String[]{id});}
        void sample(String sid,String did,String name,long t,int bpm){if(sid==null||sid.isEmpty())return;ContentValues v=new ContentValues();v.put("session_id",sid);v.put("ts",t);v.put("device_id",did);v.put("name",name);v.put("bpm",bpm);getWritableDatabase().insert("samples",null,v);}
        String savedName(String id){Cursor c=getReadableDatabase().rawQuery("SELECT name FROM names WHERE device_id=?",new String[]{id});String n=c.moveToFirst()?c.getString(0):null;c.close();return n;}
        void rename(String id,String n){ContentValues v=new ContentValues();v.put("device_id",id);v.put("name",n);getWritableDatabase().insertWithOnConflict("names",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
        ArrayList<String> sessions(){ArrayList<String>a=new ArrayList<>();Cursor c=getReadableDatabase().rawQuery("SELECT id,title,start FROM sessions ORDER BY start DESC",null);while(c.moveToNext())a.add(c.getString(0)+"  "+c.getString(1)+"  "+new SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(new Date(c.getLong(2))));c.close();return a;}
        ArrayList<String> deviceIds(String sid){ArrayList<String>a=new ArrayList<>();Cursor c=getReadableDatabase().rawQuery("SELECT DISTINCT device_id FROM samples WHERE session_id=? ORDER BY device_id",new String[]{sid==null?"":sid});while(c.moveToNext())a.add(c.getString(0));c.close();return a;}
        Stats compare(String sid,String a,String b){Stats s=new Stats();ArrayList<Integer>x=new ArrayList<>(),y=new ArrayList<>();Cursor c=getReadableDatabase().rawQuery("SELECT a.bpm,b.bpm FROM samples a JOIN samples b ON ABS(a.ts-b.ts)<=2000 WHERE a.session_id=? AND b.session_id=? AND a.device_id=? AND b.device_id=? GROUP BY a._id ORDER BY a.ts",new String[]{sid,sid,a,b});while(c.moveToNext()){x.add(c.getInt(0));y.add(c.getInt(1));}c.close();s.n=x.size();if(s.n<2)return s;double mx=0,my=0;for(int i=0;i<s.n;i++){mx+=x.get(i);my+=y.get(i);}mx/=s.n;my/=s.n;double cov=0,vx=0,vy=0,diff=0,ds=0;for(int i=0;i<s.n;i++){double dx=x.get(i)-mx,dy=y.get(i)-my,dd=x.get(i)-y.get(i);cov+=dx*dy;vx+=dx*dx;vy+=dy*dy;diff+=dd;ds+=dd*dd;}s.r=vx*vy==0?0:cov/Math.sqrt(vx*vy);s.bias=diff/s.n;double sd=Math.sqrt(Math.max(0,(ds-diff*diff/s.n)/(s.n-1)));s.low=s.bias-1.96*sd;s.high=s.bias+1.96*sd;return s;}
    }
}