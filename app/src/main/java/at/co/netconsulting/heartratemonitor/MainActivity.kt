package at.co.netconsulting.heartratemonitor

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.provider.SyncStateContract.Constants
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import at.co.netconsulting.heartratemonitor.ui.theme.HeartRateMonitorTheme
import com.polidea.rxandroidble3.RxBleClient
import com.polidea.rxandroidble3.RxBleConnection
import com.polidea.rxandroidble3.exceptions.BleCharacteristicNotFoundException
import com.polidea.rxandroidble3.exceptions.BleConflictingNotificationAlreadySetException
import com.polidea.rxandroidble3.exceptions.BleGattException
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executor


class MainActivity : ComponentActivity() {
    private var mtu: Int = 23
    private lateinit var connection: RxBleConnection
    private val SELECT_DEVICE_REQUEST_CODE: Int = 1001
    private var bleDisposables = CompositeDisposable()

    companion object {
        val TAG = "MainActivity"

        // service 6217ff4b-fb31-1140-ad5a-a45545d7ecf3

        val readPortCharacteristic1: UUID = UUID.fromString("6217ff4c-c8ec-b1fb-1380-3ad986708e2d")
        val indicateWriteNoResponsePortCharacteristic1: UUID =
            UUID.fromString("6217ff4d-91bb-91d0-7e2a-7cd3bda8a1f3")

        // service fb005c80-02e7-f387-1cad-8acd2d8df0c8

        val indicateReadWritePortCharacteristic2: UUID =
            UUID.fromString("fb005c81-02e7-f387-1cad-8acd2d8df0c8")
        val notifyPortCharacteristic2: UUID =
            UUID.fromString("fb005c82-02e7-f387-1cad-8acd2d8df0c8")

        // service 0xfeee

        val serviceUuid3 = ParcelUuid(UUID.fromString("0000feee-0000-1000-8000-00805f9b34fb"))
        val notifyWriteWriteNoResponsePortCharacteristic3: UUID =
            UUID.fromString("fb005c51-02e7-f387-1cad-8acd2d8df0c8")
        val notifyPortCharacteristic3: UUID =
            UUID.fromString("fb005c52-02e7-f387-1cad-8acd2d8df0c8")
        val writeWriteNoResponsePortCharacteristic3: UUID =
            UUID.fromString("fb005c53-02e7-f387-1cad-8acd2d8df0c8")

        val batteryServiceUuid4 = ParcelUuid(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))

        val heartRateServiceUuid4 = ParcelUuid(UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB"))
        val heartRateNotifyPortCharacteristic4 = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")
    }

    private fun hasPermissions(context: Context, permissions: Set<String>): Boolean = permissions.all {
        ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    private suspend fun requestPermissions(permissions: Set<String>): Map<String, Boolean> {
        val requestPermissionActivityContract = ActivityResultContracts.RequestMultiplePermissions()
        if (hasPermissions(this, permissions)) {
            return permissions.associateWith { true }
        }
        return suspendCancellableCoroutine { continuation ->
            val launcher = registerForActivityResult(requestPermissionActivityContract) { granted ->
                continuation.resume(
                    //val shouldShowRationale = currentActivity.shouldShowRequestPermissionRationale(it)
                    //PermissionDenied(shouldShowRationale)
                    granted // .containsAll(permissions)
                ) {
                    // FIXME cancellation cleanup
                }
            }
            launcher.launch(permissions.toTypedArray())
            continuation.invokeOnCancellation {
                launcher.unregister()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HeartRateMonitorTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Greeting("Android")
                }
            }
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "No support for Bluetooth LE", Toast.LENGTH_LONG).show()
        }
        //registerServiceUuidReceiver()
        lifecycleScope.launch {
            if (requestPermissions(setOf(BLUETOOTH_CONNECT)).values.all { it }) {
                pairDevice()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            SELECT_DEVICE_REQUEST_CODE -> when (resultCode) {
                Activity.RESULT_OK -> {
                    val scanResult = data?.getParcelableExtra(
                        android.companion.CompanionDeviceManager.EXTRA_DEVICE,
                        android.bluetooth.le.ScanResult::class.java
                    )
                    scanResult?.let {
                        start(it.device.address)
                    }
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun setupNotifications(characteristicUuid: UUID, callback: (input: ByteArray) -> Unit) {
        try {
            val notificationObservable: Observable<ByteArray> =
                connection!!.setupNotification(characteristicUuid).flatMap { it }

            // FIXME also discoverServices ("andThen")

            val disposable = notificationObservable.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread()).subscribe(callback) { throwable ->
                run {
                    Log.e(TAG, "Notification error: $throwable")
                    notifyListenersOfException(throwable)
                }
            }

            bleDisposables.add(disposable)
        } catch (e: BleCharacteristicNotFoundException) {
            Log.e(TAG, "Characteristic not found: $characteristicUuid")
            notifyListenersOfException(e)
            throw e
        } catch (e: BleConflictingNotificationAlreadySetException) {
            Log.e(
                TAG, "Conflicting notification already set for characteristic: $characteristicUuid"
            )
            notifyListenersOfException(e)
            throw e
        } catch (e: BleGattException) {
            Log.e(TAG, "Gatt error: $e")/*if (e.type == BleGattOperationType.NOTIFICATION) {
                Log.e(TAG, "Notification setup error for characteristic: $characteristicUuid")
            }*/
            notifyListenersOfException(e)
            throw e
        }
    }

    private fun setupIndications(characteristicUuid: UUID, callback: (input: ByteArray) -> Unit) {
        try {
            val indicationObservable: Observable<ByteArray> =
                connection!!.setupIndication(characteristicUuid).flatMap { it }

            // FIXME also discoverServices ("andThen")

            val disposable = indicationObservable.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread()).subscribe(callback) { throwable ->
                run {
                    Log.e(TAG, "Indication error: $throwable")
                    notifyListenersOfException(throwable)
                }
            }

            bleDisposables.add(disposable)
        } catch (e: BleCharacteristicNotFoundException) {
            Log.e(TAG, "Characteristic not found: $characteristicUuid")
            notifyListenersOfException(e)
            throw e
        } catch (e: BleConflictingNotificationAlreadySetException) {
            Log.e(
                TAG, "Conflicting indication already set for characteristic: $characteristicUuid"
            )
            notifyListenersOfException(e)
            throw e
        } catch (e: BleGattException) {
            Log.e(TAG, "Gatt error: $e")/*if (e.type == BleGattOperationType.NOTIFICATION) {
                Log.e(TAG, "Notification setup error for characteristic: $characteristicUuid")
            }*/
            notifyListenersOfException(e)
            throw e
        }
    }

    /** Take the given client message and send it, if necessary splitting it into different bluetooth packets */
    private fun sendInternal(
        writingPortCharacteristic: UUID,
        rawPacket: ByteArray,
    ) {
        bleDisposables.add(connection!!.writeCharacteristic(
            writingPortCharacteristic, rawPacket
        ).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
            .subscribe({ _: ByteArray? ->
                Log.d(
                    TAG, "Write characteristic successful"
                )
            }) { throwable: Throwable ->
                Log.e(
                    TAG, "Write characteristic error: $throwable"
                )
                notifyListenersOfException(throwable)
            })
    }

    private val commandQueue = PublishSubject.create<ByteArray>().toSerialized()

    private fun setupSender(characteristicUuid: UUID) {
        // TODO .onBackpressureBuffer().flatMap(bytesAndFilter -> {}, 1/*serialized communication*/)
        bleDisposables.add(commandQueue.subscribe({
            sendInternal(characteristicUuid, it)
        }, {
            Log.e(TAG, "setupSender")
        }))
    }

    private fun requestMtu(connection: RxBleConnection) {
        val disposable = connection.requestMtu(256).subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread()).subscribe({ mtu ->
            run {
                // mtu: 251
                this.mtu = mtu

//                listeners.forEach {
//                    it.onMtuResponse(mtu)
//                }
            }
        }, { throwable ->
            run {
                Log.e(TAG, "MTU request failed: $throwable")
                notifyListenersOfException(throwable)
            }
        })
        bleDisposables.add(disposable)
    }

    private fun sendMessage(message: ByteArray) {
        commandQueue.onNext(message)
    }

    private fun start(address: String) {
        Toast.makeText(this, address, Toast.LENGTH_LONG).show()
        val rxBleClient = RxBleClient.create(this)
        val bleDevice = rxBleClient.getBleDevice(address)
        bleDisposables.clear()
        bleDisposables.add(
            bleDevice.establishConnection(false) // TODO timeout less than 30 s
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe({ connection ->
                    run {
                        Log.d(TAG, "Connection established")
                        this.connection = connection
//                        setupIndications(indicateWriteNoResponsePortCharacteristic1) { input ->
//                            Log.d(TAG, "indication received 1: ${input.contentToString()}")
//                            val buf = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN)
//                            try {
//                                onIndicationReceived(buf)
//                            } catch (e: BufferUnderflowException) {
//                                notifyListenersOfException(e)
//                                Log.e(TAG, "Exception: $e")
//                            }
//                        }
//                        setupIndications(indicateReadWritePortCharacteristic2) { input ->
//                            Log.d(TAG, "indication received 2a: ${input.contentToString()}")
//                            val buf = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN)
//                            try {
//                                onIndicationReceived(buf)
//                            } catch (e: BufferUnderflowException) {
//                                notifyListenersOfException(e)
//                                Log.e(TAG, "Exception: $e")
//                            }
//                        }
//                        setupNotifications(notifyPortCharacteristic2) { input ->
//                            Log.d(TAG, "Notification received 2b: ${input.contentToString()}")
//                            input?.let {
//                                onNotificationReceived(it)
//                            }
//                        }
//                        setupNotifications(notifyWriteWriteNoResponsePortCharacteristic3) { input ->
//                            Log.d(TAG, "Notification received 3a: ${input.contentToString()}")
//                            input?.let {
//                                onNotificationReceived(it)
//                            }
//                        }
//                        setupNotifications(notifyPortCharacteristic3) { input ->
//                            Log.d(TAG, "Notification received 3b: ${input.contentToString()}")
//                            input?.let {
//                                onNotificationReceived(it)
//                            }
//                        }
                        setupNotifications(heartRateNotifyPortCharacteristic4) { input ->
                            Log.d(TAG, "Notification received 4: ${input.contentToString()}")
                            input?.let {
                                onNotificationReceived(it)
                            }
                        }
                        setupSender(indicateWriteNoResponsePortCharacteristic1)
                        setupSender(indicateReadWritePortCharacteristic2)
                        setupSender(notifyWriteWriteNoResponsePortCharacteristic3)
                        setupSender(writeWriteNoResponsePortCharacteristic3)
                        requestMtu(connection)
                    }
                }, { throwable ->
                    run {
                        Log.e(TAG, "Connection error: $throwable")
                        notifyListenersOfException(throwable)
                    }
                })
        )
    }

    private fun toDec(a: ByteArray): String =
        a.joinToString(",") {
            "%d".format(it)
        }

    private fun onIndicationReceived(buf: ByteBuffer) {
        Toast.makeText(this, buf.toString(), Toast.LENGTH_LONG).show()

    }

    private fun onNotificationReceived(buf: ByteArray) {
        Toast.makeText(this, toDec(buf), Toast.LENGTH_SHORT).show()

    }

    private fun notifyListenersOfException(e: Throwable) {
        Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        bleDisposables.clear()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        //createPipes()

    }

    @SuppressLint("MissingPermission")
    private fun pairDevice() {
        val bluetoothManager = this.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        for (connectedDevice in connectedDevices) {
            val address = connectedDevice.address
            Toast.makeText(this, address, Toast.LENGTH_LONG).show()
            //connectedDevice.sdpSearch(serviceUuid3)
            // Reacted to see registerServiceUuidReceiver
            //connectedDevice.fetchUuidsWithSdp() // TODO: handle return type
            // FIXME check whether it's the correct device
            return start(connectedDevice.address)
        }

        val deviceManager =
            this.getSystemService(COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

        val scanFilter =
            ScanFilter.Builder()
                .setServiceUuid(
                    serviceUuid3,
                    null
                )
                .build()
        val deviceFilter = BluetoothLeDeviceFilter.Builder()
            // Match only Bluetooth devices whose name matches the pattern.
            //.setNamePattern(Pattern.compile("My device"))
            // Match only Bluetooth devices whose service UUID matches this pattern.
            //.addServiceUuid()
            .setScanFilter(scanFilter)
            //.setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
            .build()

        val pairingRequest: AssociationRequest = AssociationRequest.Builder()
            // Find only devices that match this request filter.
            //.setDeviceProfile(AssociationRequest.)
            // bad: .setSelfManaged(true)
            .addDeviceFilter(deviceFilter)
            // Stop scanning as soon as one device matching the filter is found.
            //.setSingleDevice(true)
            .build()

        val executor: Executor = Executor { it.run() }
        deviceManager.associate(pairingRequest,
            executor,
            object : CompanionDeviceManager.Callback() {
                // Called when a device is found. Launch the IntentSender so the user
                // can select the device they want to pair with.
                override fun onAssociationPending(intentSender: IntentSender) {
                    intentSender?.let {
                        startIntentSenderForResult(it, SELECT_DEVICE_REQUEST_CODE, null, 0, 0, 0)
                    }
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    // The association is created.
                    associationInfo.deviceMacAddress
                }

                override fun onFailure(errorMessage: CharSequence?) {
                    // Handle the failure.
                    Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()

                }
            })
    }

    // Side effect: Calls start().
    private fun registerServiceUuidReceiver() {
        Log.d(TAG, "Registering receiver")
        val filter = IntentFilter()
        filter.addAction(BluetoothDevice.ACTION_UUID)
        val mReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let { intent ->
                    val d: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val uuidExtra = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                    if (uuidExtra != null && d != null) {
                        for (uuid in uuidExtra) {
                            if (uuid == serviceUuid3) {
                                Toast.makeText(
                                    context,
                                    uuid.toString(),
                                    Toast.LENGTH_LONG
                                ).show()
                                return start(d.address)
                            }
                        }
                    }
                }
            }
        }
        registerReceiver(mReceiver, filter)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(TextFieldValue("")) }
    TextField(
        value = text,
        onValueChange = { newText ->
            text = newText
        })
    // TODO: call sendMessage(text.hexdecode())
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    HeartRateMonitorTheme {
        Greeting("Android")
    }
}