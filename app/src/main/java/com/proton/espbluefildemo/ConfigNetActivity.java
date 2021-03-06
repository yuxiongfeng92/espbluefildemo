package com.proton.espbluefildemo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.proton.espbluefildemo.adapter.DevicesAdapter;
import com.proton.espbluefildemo.utils.BluFiUtil;
import com.proton.espbluefildemo.view.LoadingDialog;
import com.tbruyelle.rxpermissions2.RxPermissions;
import com.wms.adapter.recyclerview.OnItemClickListener;
import com.wms.logger.Logger;
import com.wms.utils.NetUtils;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import blufi.espressif.BlufiCallback;
import blufi.espressif.BlufiClient;
import blufi.espressif.params.BlufiConfigureParams;
import blufi.espressif.params.BlufiParameter;
import blufi.espressif.response.BlufiStatusResponse;
import blufi.espressif.response.BlufiVersionResponse;
import io.reactivex.functions.Consumer;
import libs.espressif.app.SdkUtil;
import libs.espressif.ble.EspBleUtils;
import libs.espressif.ble.ScanListener;

public class ConfigNetActivity extends AppCompatActivity implements View.OnClickListener, SwipeRefreshLayout.OnRefreshListener {
    private static final long TIMEOUT_SCAN = 4000L;
    final RxPermissions rxPermissions = new RxPermissions(this);
    EditText etSsid, etPwd;
    Button btnConfig;

    /**
     * 扫描设备
     */
    RecyclerView recyclerview;
    private List<BluetoothDevice> mBleList;
    private Map<BluetoothDevice, Integer> mDeviceRssiMap;//为了根据信号强度排序
    private ExecutorService mThreadPool;//扫描设备时需要的线程池
    private ScanCallback mScanCallback;
    private Future mUpdateFuture;
    private long mScanStartTime;
    private DevicesAdapter adapter;
    SwipeRefreshLayout mRefreshLayout;
    private String mBlufiFilter = "BLUFI";
    private BluetoothDevice mDevice;

    /**
     * 设备连接
     */
    private BluetoothGatt mGatt;
    private boolean mConnected;
    private Context mContext;

    /**
     * 配网
     *
     * @param savedInstanceState
     */
    BlufiClient mBlufiClient;
    private String wifiName;
    LoadingDialog dialog;

    @SuppressLint("CheckResult")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config_net_layout);
        initView();
        mThreadPool = Executors.newSingleThreadExecutor();
        mDeviceRssiMap = new HashMap<>();
        mContext=this;

        rxPermissions
                .request(Manifest.permission.ACCESS_COARSE_LOCATION)
                .subscribe(new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean granted) {
                        if (granted) {
//                            scanDevice();
                        } else {
                            Toast.makeText(ConfigNetActivity.this, "没有权限", Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    }
                });
    }

    private void initView() {
        dialog=new LoadingDialog(this);
        etSsid = findViewById(R.id.et_wifi_ssid);
        etPwd = findViewById(R.id.et_wifi_password);
        btnConfig = findViewById(R.id.btn_config);
        recyclerview = findViewById(R.id.recyclerview);
        mRefreshLayout = findViewById(R.id.swipefreshview);
        btnConfig.setOnClickListener(this);
        mRefreshLayout.setOnRefreshListener(this);

        mBleList = new LinkedList<>();
        adapter = new DevicesAdapter(this, mBleList, android.R.layout.simple_list_item_2);
        recyclerview.setAdapter(adapter);
        recyclerview.setLayoutManager(new LinearLayoutManager(this));
        String connectWifiSsid = NetUtils.getConnectWifiSsid(this);

        wifiName = connectWifiSsid.replace('"', ' ').replace('"', ' ').trim();
        etSsid.setText(wifiName);


    }

    private void onIntervalScanUpdate(final boolean over) {

        final List<BluetoothDevice> devices = new LinkedList<>(mDeviceRssiMap.keySet());
        Collections.sort(devices, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice dev1, BluetoothDevice dev2) {
                Integer rssi1 = mDeviceRssiMap.get(dev1);
                Integer rssi2 = mDeviceRssiMap.get(dev2);
                return rssi2.compareTo(rssi1);
            }
        });

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBleList.clear();
                mBleList.addAll(devices);
                adapter.notifyDataSetChanged();
                if (over) {
                    mRefreshLayout.setRefreshing(false);
                }
            }
        });
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_config:
//                configWifi();
                BluFiUtil.getInstance().startConfigWifi(mContext, wifiName, etPwd.getText().toString().trim(), new BluFiUtil.OnBluFiSetNetworkListener() {
                    @Override
                    public void onStart() {
                        if (dialog==null) {
                            dialog=new LoadingDialog(ConfigNetActivity.this);
                        }
                        dialog.show();
                    }

                    @Override
                    public void onSuccess(String macaddress, String bssid) {
                        dialog.dismiss();
                    }

                    @Override
                    public void onFail() {
                        dialog.dismiss();
                    }

                    @Override
                    public void onNotFound() {
                        dialog.dismiss();
                    }
                });
                break;
        }
    }

    @Override
    public void onRefresh() {
//        scanDevice();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();


        if (mBlufiClient != null) {
            mBlufiClient.requestCloseConnection();
        }

        if (mBlufiClient != null) {
            mBlufiClient.close(); //释放资源
            mBlufiClient = null;
        }
        if (mGatt != null) {
            mGatt.close();
        }


    }

    /**
     * 扫描设备
     */
    private void scanDevice() {
        if (!BluetoothAdapter.getDefaultAdapter().isEnabled()) {
            Toast.makeText(this, "bluetooot is unEnabled", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locationManager != null) {
                boolean locationGPS = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                boolean locationNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                if (!locationGPS && !locationNetwork) {
                    Toast.makeText(this, "location is disable", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }

        mScanCallback = new ScanCallback();
        EspBleUtils.startScanBle(mScanCallback);
//        Logger.v("开始扫描设备。。。");
        Log.d("yxf","开始扫描。。。");

        mDeviceRssiMap.clear();
        mBleList.clear();
        adapter.notifyDataSetChanged();

        mScanStartTime = SystemClock.elapsedRealtime();
        mUpdateFuture = mThreadPool.submit(new Runnable() {
            @Override
            public void run() {
                Logger.v("current thread is :  id:", Thread.currentThread().getId(), "   name : ", Thread.currentThread().getName()
                        , "  current thread interrupted :", Thread.currentThread().isInterrupted());

                while (!Thread.currentThread().isInterrupted()) {

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        break;
                    }

                    long scanCost = SystemClock.elapsedRealtime() - mScanStartTime;
                    if (scanCost > TIMEOUT_SCAN) {
                        stopScan();
                        break;
                    }
                    onIntervalScanUpdate(false);
                }
                onIntervalScanUpdate(true);

            }
        });

    }

    /**
     * 停止扫描设备
     */
    private void stopScan() {
        EspBleUtils.stopScanBle(mScanCallback);
        if (mUpdateFuture != null) {
            mUpdateFuture.cancel(true);
        }
        Logger.v("stop ble scan");
    }

    /**
     * 连接设备
     */
    private void connect() {
        if (mBlufiClient != null) {
            mBlufiClient.close();
            mBlufiClient = null;
        }
        if (mGatt != null) {
            mGatt.close();
        }

        GattCallback callback = new GattCallback();
        if (SdkUtil.isAtLeastM_23()) {
            mGatt = mDevice.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
        } else {
            mGatt = mDevice.connectGatt(this, false, callback);
        }
    }

    /**
     * 配置网络
     */
    private void configWifi() {

        Logger.v("开始配置wifi。。。");

        if (!mConnected) {
            return;
        }
        String ssid = etSsid.getText().toString();
        String wifiName = ssid.replace('"', ' ').replace('"', ' ').trim();
        String password = etPwd.getText().toString();

        if (TextUtils.isEmpty(ssid)) {
            Toast.makeText(this, "wifi ssid 不能为空", Toast.LENGTH_SHORT).show();
           return;
        }

        if (TextUtils.isEmpty(password)) {
            Toast.makeText(this, "wifi 密码不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        BlufiConfigureParams params = new BlufiConfigureParams();
        params.setOpMode( BlufiParameter.OP_MODE_STA);
        params.setStaSSIDBytes( wifiName.getBytes());
        params.setStaPassword(password);
        if (mBlufiClient==null) {
            Logger.e("blufiClient 为 null");
            return;
        }
        mBlufiClient.configure(params);
    }

    private class ScanCallback implements ScanListener {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {

            String name = device.getName();
            if (!TextUtils.isEmpty(mBlufiFilter)) {
                if (name == null || !name.startsWith(mBlufiFilter)) {
                    return;
                }
            }
            Logger.v("扫描到的设备: name = "+device.getName() ,"address = "+device.getAddress());
            mDeviceRssiMap.put(device, rssi);
        }
    }

    private class GattCallback extends BluetoothGattCallback {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String devAddr = gatt.getDevice().getAddress();
            if (status == BluetoothGatt.GATT_SUCCESS) {
                switch (newState) {
                    case BluetoothProfile.STATE_CONNECTED:
                        Logger.v("设备连接成功。。。");
                        gatt.discoverServices();//获取BlutoothGattService
                        mConnected = true;
                        break;
                    case BluetoothProfile.STATE_DISCONNECTED:
                        gatt.close();
                        Logger.v("设备连接失败。。。");
                        mConnected = false;
                        break;
                }
            } else {
                gatt.close();
                Logger.v("设备连接失败。。。");
            }
        }

        /**
         * 连接成功后获取BluetoothGattService   gatt.discoverServices();的回调
         *
         * @param gatt
         * @param status
         */
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(BlufiConstants.UUID_SERVICE);
                if (service == null) {
                    Logger.v("discover services fail ");
                    gatt.disconnect();
                }
                //获得 BluetoothGattCharacteristic  用于app向设备写数据
                BluetoothGattCharacteristic writeCharact = service.getCharacteristic(BlufiConstants.UUID_WRITE_CHARACTERISTIC);
                if (writeCharact == null) {
                    Logger.v("get writeCharact fail");
                    gatt.disconnect();
                }

                //接收device向app推送的消息
                BluetoothGattCharacteristic notifyCharact = service.getCharacteristic(BlufiConstants.UUID_NOTIFICATION_CHARACTERISTIC);
                if (notifyCharact == null) {
                    Logger.v("get notification characteristic fail");
                    gatt.disconnect();
                }


                /**
                 * 配网客户端
                 */
                if (mBlufiClient != null) {
                    mBlufiClient.close();
                }
                mBlufiClient = new BlufiClient(gatt, writeCharact, notifyCharact, new BlufiCallbackMain());
                gatt.setCharacteristicNotification(notifyCharact, true);

                if (SdkUtil.isAtLeastL_21()) {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    int mtu = (int) BlufiApp.getInstance().settingsGet(
                            SettingsConstants.PREF_SETTINGS_KEY_MTU_LENGTH, BlufiConstants.DEFAULT_MTU_LENGTH);
                    boolean requestMtu = gatt.requestMtu(mtu);
                    if (!requestMtu) {
                        Logger.w("Request mtu failed");
                    }
                }

            } else {
                gatt.disconnect();
            }
        }


        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            mBlufiClient.onCharacteristicWrite(gatt, characteristic, status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            mBlufiClient.onCharacteristicChanged(gatt, characteristic);
        }


        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (mBlufiClient != null) {
                    mBlufiClient.setPostPackageLengthLimit(mtu - 3);//设置 Blufi 发送数据时每包数据的最大长度
                }
            }
        }
    }

    private class BlufiCallbackMain extends BlufiCallback {

        // 收到 Device 的通知数据
        // 返回 false 表示处理尚未结束，交给 BlufiClient 继续后续处理
        // 返回 true 表示处理结束，后续将不再解析该数据，也不会调用回调方法
        public boolean onGattNotification(BlufiClient client, int pkgType, int subType, byte[] data) {
            return false;
        }

        // BluetoothGatt 关闭时调用
        public void onGattClose(BlufiClient client) {
            Logger.v("client close");
        }

        // 收到 Device 发出的错误代码
        public void onError(BlufiClient client, int errCode) {

        }

        // 发送配置信息的结果
        public void onConfigureResult(BlufiClient client, int status) {
            switch (status) {
                case STATUS_SUCCESS:
                    Logger.v("设备配网成功");
                    Toast.makeText(ConfigNetActivity.this, "配网成功", Toast.LENGTH_SHORT).show();
                    break;
                default:
                    Logger.v("设备配网失败");
                    break;
            }
        }

        // 收到 Device 的版本信息
        public void onDeviceVersionResponse(BlufiClient client, int status, BlufiVersionResponse response) {

        }

        // 收到 Device 的状态信息
        public void onDeviceStatusResponse(BlufiClient client, int status, BlufiStatusResponse response) {

        }
    }



}
