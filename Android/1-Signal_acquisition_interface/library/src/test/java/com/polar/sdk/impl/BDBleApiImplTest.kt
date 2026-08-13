import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.content.IntentFilter
import android.os.ParcelUuid
import com.polar.androidcommunications.api.ble.model.BleDeviceSession
import com.polar.androidcommunications.api.ble.model.advertisement.BleAdvertisementContent
import com.polar.androidcommunications.api.ble.model.gatt.client.BlePfcClient
import com.polar.androidcommunications.api.ble.model.gatt.client.BlePfcClient.Companion.PFC_SERVICE
import com.polar.androidcommunications.api.ble.model.gatt.client.psftp.BlePsFtpClient
import com.polar.androidcommunications.api.ble.model.gatt.client.psftp.BlePsFtpUtils
import com.polar.androidcommunications.enpoints.ble.bluedroid.host.BDScanCallback
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.errors.PolarBleSdkInstanceException
import com.polar.sdk.api.errors.PolarOperationNotSupported
import com.polar.sdk.impl.BDBleApiImpl
import com.polar.sdk.impl.utils.PolarServiceClientUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import protocol.PftpNotification
import protocol.PftpRequest
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

class BDBleApiImplTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        BDBleApiImpl.clearInstance()

        mockkStatic(ParcelUuid::class)
        every { ParcelUuid.fromString(any()) } returns mockk(relaxed = true)

        mockkConstructor(IntentFilter::class)
        every { anyConstructed<IntentFilter>().addAction(any()) } just runs

        mockkConstructor(ScanFilter.Builder::class)
        every { anyConstructed<ScanFilter.Builder>().setServiceUuid(any()) } answers { self as ScanFilter.Builder }
        every { anyConstructed<ScanFilter.Builder>().setServiceUuid(null) } answers { self as ScanFilter.Builder }
        every { anyConstructed<ScanFilter.Builder>().setManufacturerData(any(), any()) } answers { self as ScanFilter.Builder }
        every { anyConstructed<ScanFilter.Builder>().build() } returns mockk(relaxed = true)

        mockkConstructor(BDScanCallback::class)
        every { anyConstructed<BDScanCallback>().powerOn() } just runs
        every { anyConstructed<BDScanCallback>().powerOff() } just runs

        val bluetoothAdapter = mockk<BluetoothAdapter>(relaxed = true)
        val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
        every { bluetoothManager.adapter } returns bluetoothAdapter

        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { context.registerReceiver(any(), any<IntentFilter>()) } returns null
    }

    @After
    fun tearDown() {
        BDBleApiImpl.clearInstance()
        unmockkStatic(ParcelUuid::class)
        unmockkConstructor(IntentFilter::class)
        unmockkConstructor(ScanFilter.Builder::class)
        unmockkConstructor(BDScanCallback::class)
    }

    @Test
    fun singletonInstanceForPolarBleSDK() {
        // Arrange
        val polarBleApiDefaultInstance =
            BDBleApiImpl.getInstance(
                context,
                setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO)
            )

        // Act
        val polarBleApiSecondInstance =
            BDBleApiImpl.getInstance(
                context,
                setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO)
            )

        // Assert
        Assert.assertEquals(polarBleApiDefaultInstance, polarBleApiSecondInstance)
    }

    @Test
    fun singletonInstanceNotPossibleIfDifferentFeaturesRequired() {

        // Arrange
        BDBleApiImpl.getInstance(
            context,
            setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO)
        )

        // Act && Assert
        Assert.assertThrows(PolarBleSdkInstanceException::class.java) {
            BDBleApiImpl.getInstance(
                context,
                setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_FILE_TRANSFER)
            )
        }
    }

    @Test
    fun `setLocalTime sends different UTC and local time values for non-UTC timezone`() = runTest {
        // Arrange
        val deviceId = "E123456F"
        val localDateTime = LocalDateTime.of(2024, 3, 15, 12, 0, 0)

        val api = BDBleApiImpl.getInstance(context, setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_FILE_TRANSFER))
        val (client, session) = mockPsFtpConnection(deviceId)

        val originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+02:00"))

        val capturedQueryIds = mutableListOf<Int>()
        val capturedQueryParams = mutableListOf<ByteArray?>()
        coEvery { client.query(capture(capturedQueryIds), captureNullable(capturedQueryParams)) } returns ByteArrayOutputStream()

        try {
            // Act
            api.setLocalTime(deviceId, localDateTime)
        } finally {
            TimeZone.setDefault(originalTz)
            unmockkObject(PolarServiceClientUtils)
        }

        // Assert – two queries were sent: SET_SYSTEM_TIME and SET_LOCAL_TIME
        val systemTimeIndex = capturedQueryIds.indexOf(PftpRequest.PbPFtpQuery.SET_SYSTEM_TIME_VALUE)
        val localTimeIndex = capturedQueryIds.indexOf(PftpRequest.PbPFtpQuery.SET_LOCAL_TIME_VALUE)
        Assert.assertTrue("SET_SYSTEM_TIME_VALUE query was not sent", systemTimeIndex >= 0)
        Assert.assertTrue("SET_LOCAL_TIME_VALUE query was not sent", localTimeIndex >= 0)

        val systemTimeParams = PftpRequest.PbPFtpSetSystemTimeParams.parseFrom(capturedQueryParams[systemTimeIndex])
        val localTimeParams = PftpRequest.PbPFtpSetLocalTimeParams.parseFrom(capturedQueryParams[localTimeIndex])

        // Local time stays at 12:00, UTC is 10:00 (GMT+2 offset)
        Assert.assertEquals("Local time hour should be preserved", 12, localTimeParams.time.hour)
        Assert.assertEquals("System/UTC time hour should be 2 hours behind local (GMT+2)", 10, systemTimeParams.time.hour)
        Assert.assertNotEquals(
            "Local time and UTC system time must differ for a non-UTC timezone",
            localTimeParams.time.hour,
            systemTimeParams.time.hour
        )
        Assert.assertTrue("System time must be marked as trusted", systemTimeParams.trusted)
    }

    @Test
    fun `getSensorInitiatedSecurityMode returns true when device payload byte is 1`() = runTest {
        // Arrange
        val deviceId = "E123456F"
        val api = BDBleApiImpl.getInstance(context, setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_FEATURES_CONFIGURATION_SERVICE))
        val (pfcClient, _) = mockPfcConnection(deviceId)

        // data[0]=responseCode, data[1]=opCode, data[2]=status, data[3]=payload[0]=1 (enabled)
        val response = BlePfcClient.PfcResponse(
            byteArrayOf(0x02, BlePfcClient.PfcMessage.PFC_REQUEST_SENSOR_INITIATED_SECURITY_MODE.numVal.toByte(), 0x01, 0x01)
        )
        coEvery { pfcClient.sendControlPointCommand(BlePfcClient.PfcMessage.PFC_REQUEST_SENSOR_INITIATED_SECURITY_MODE, 0) } returns response

        try {
            // Act
            val result = api.getSensorInitiatedSecurityMode(deviceId)

            // Assert
            Assert.assertTrue("Expected getSensorInitiatedSecurityMode to return true when payload byte is 1", result)
        } finally {
            unmockkObject(PolarServiceClientUtils)
        }
    }

    @Test
    fun `getSensorInitiatedSecurityMode returns false when device payload byte is 0`() = runTest {
        // Arrange
        val deviceId = "E123456F"
        val api = BDBleApiImpl.getInstance(context, setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_FEATURES_CONFIGURATION_SERVICE))
        val (pfcClient, _) = mockPfcConnection(deviceId)

        // data[3]=payload[0]=0 (disabled)
        val response = BlePfcClient.PfcResponse(
            byteArrayOf(0x02, BlePfcClient.PfcMessage.PFC_REQUEST_SENSOR_INITIATED_SECURITY_MODE.numVal.toByte(), 0x01, 0x00)
        )
        coEvery { pfcClient.sendControlPointCommand(BlePfcClient.PfcMessage.PFC_REQUEST_SENSOR_INITIATED_SECURITY_MODE, 0) } returns response

        try {
            // Act
            val result = api.getSensorInitiatedSecurityMode(deviceId)

            // Assert
            Assert.assertFalse("Expected getSensorInitiatedSecurityMode to return false when payload byte is 0", result)
        } finally {
            unmockkObject(PolarServiceClientUtils)
        }
    }

    @Test
    fun `getSensorInitiatedSecurityMode returns false when device response has no payload`() = runTest {
        // Arrange
        val deviceId = "E123456F"
        val api = BDBleApiImpl.getInstance(context, setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_FEATURES_CONFIGURATION_SERVICE))
        val (pfcClient, _) = mockPfcConnection(deviceId)

        // Only 3 bytes → no payload (payload remains null)
        val response = BlePfcClient.PfcResponse(
            byteArrayOf(0x02, BlePfcClient.PfcMessage.PFC_REQUEST_SENSOR_INITIATED_SECURITY_MODE.numVal.toByte(), 0x01)
        )
        coEvery { pfcClient.sendControlPointCommand(BlePfcClient.PfcMessage.PFC_REQUEST_SENSOR_INITIATED_SECURITY_MODE, 0) } returns response

        try {
            // Act
            val result = api.getSensorInitiatedSecurityMode(deviceId)

            // Assert
            Assert.assertFalse("Expected getSensorInitiatedSecurityMode to return false when payload is absent", result)
        } finally {
            unmockkObject(PolarServiceClientUtils)
        }
    }

    @Test
    fun `setSensorInitiatedSecurityMode enable=true completes successfully when device responds with status 1`() = runTest {
        // Arrange
        val deviceId = "E123456F"
        val api = BDBleApiImpl.getInstance(context, setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_FEATURES_CONFIGURATION_SERVICE))
        val (pfcClient, _) = mockPfcConnection(deviceId)

        // data[0]=responseCode, data[1]=opCode, data[2]=status(1=success)
        val successResponse = BlePfcClient.PfcResponse(
            byteArrayOf(0x02, BlePfcClient.PfcMessage.PFC_CONFIGURE_SENSOR_INITIATED_SECURITY_MODE.numVal.toByte(), 0x01)
        )
        coEvery { pfcClient.sendControlPointCommand(BlePfcClient.PfcMessage.PFC_CONFIGURE_SENSOR_INITIATED_SECURITY_MODE, 1) } returns successResponse

        try {
            // Act & Assert – should complete without throwing
            api.setSensorInitiatedSecurityMode(deviceId, enable = true)
        } finally {
            unmockkObject(PolarServiceClientUtils)
        }
    }

    @Test
    fun `setSensorInitiatedSecurityMode enable=false completes successfully when device responds with status 1`() = runTest {
        // Arrange
        val deviceId = "E123456F"
        val api = BDBleApiImpl.getInstance(context, setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_FEATURES_CONFIGURATION_SERVICE))
        val (pfcClient, _) = mockPfcConnection(deviceId)

        val successResponse = BlePfcClient.PfcResponse(
            byteArrayOf(0x02, BlePfcClient.PfcMessage.PFC_CONFIGURE_SENSOR_INITIATED_SECURITY_MODE.numVal.toByte(), 0x01)
        )
        coEvery { pfcClient.sendControlPointCommand(BlePfcClient.PfcMessage.PFC_CONFIGURE_SENSOR_INITIATED_SECURITY_MODE, 0) } returns successResponse

        try {
            // Act & Assert – should complete without throwing
            api.setSensorInitiatedSecurityMode(deviceId, enable = false)
        } finally {
            unmockkObject(PolarServiceClientUtils)
        }
    }

    @Test
    fun `setSensorInitiatedSecurityMode throws PolarOperationNotSupported when device responds with status other than 1`() = runTest {
        // Arrange
        val deviceId = "E123456F"
        val api = BDBleApiImpl.getInstance(context, setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_FEATURES_CONFIGURATION_SERVICE))
        val (pfcClient, _) = mockPfcConnection(deviceId)

        // status = 0x00 means not supported
        val failResponse = BlePfcClient.PfcResponse(
            byteArrayOf(0x02, BlePfcClient.PfcMessage.PFC_CONFIGURE_SENSOR_INITIATED_SECURITY_MODE.numVal.toByte(), 0x00)
        )
        coEvery { pfcClient.sendControlPointCommand(BlePfcClient.PfcMessage.PFC_CONFIGURE_SENSOR_INITIATED_SECURITY_MODE, any<Int>()) } returns failResponse

        try {
            // Act & Assert
            Assert.assertThrows(PolarOperationNotSupported::class.java) {
                kotlinx.coroutines.runBlocking {
                    api.setSensorInitiatedSecurityMode(deviceId, enable = true)
                }
            }
        } finally {
            unmockkObject(PolarServiceClientUtils)
        }
    }

    @Test
    fun `setHibernateMode sends RESET notification with hibernate=true sleep=true doFactoryDefaults=false`() = runTest {
        // Arrange
        val deviceId = "E123456F"
        val api = BDBleApiImpl.getInstance(context, setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_CONTROL))
        val (client, _) = mockPsFtpConnection(deviceId)

        val capturedNotificationIds = mutableListOf<Int>()
        val capturedNotificationParams = mutableListOf<ByteArray?>()
        coEvery {
            client.sendNotification(capture(capturedNotificationIds), captureNullable(capturedNotificationParams))
        } just runs

        try {
            // Act
            api.setHibernateMode(deviceId)
        } finally {
            unmockkObject(PolarServiceClientUtils)
        }

        // Assert
        Assert.assertEquals("Expected exactly one notification", 1, capturedNotificationIds.size)
        Assert.assertEquals(
            "Expected RESET notification",
            PftpNotification.PbPFtpHostToDevNotification.RESET.ordinal,
            capturedNotificationIds[0]
        )

        val params = PftpNotification.PbPFtpFactoryResetParams.parseFrom(capturedNotificationParams[0])
        Assert.assertTrue("hibernate should be true", params.hibernate)
        Assert.assertTrue("sleep should be true to initiate low-power mode", params.sleep)
        Assert.assertFalse("doFactoryDefaults should be false", params.doFactoryDefaults)
    }

    @Test
    fun `setHibernateMode does not trigger factory defaults and sets hibernate flag`() = runTest {
        // Arrange
        val deviceId = "E123456F"
        val api = BDBleApiImpl.getInstance(context, setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_CONTROL))
        val (client, _) = mockPsFtpConnection(deviceId)

        val capturedParams = mutableListOf<ByteArray?>()
        coEvery {
            client.sendNotification(any(), captureNullable(capturedParams))
        } just runs

        try {
            // Act
            api.setHibernateMode(deviceId)
        } finally {
            unmockkObject(PolarServiceClientUtils)
        }

        val params = PftpNotification.PbPFtpFactoryResetParams.parseFrom(capturedParams[0])
        Assert.assertFalse("Hibernate mode must not trigger factory defaults", params.doFactoryDefaults)
        Assert.assertTrue("Hibernate flag must be set to true", params.hibernate)
    }

    private fun mockPfcConnection(deviceId: String): Pair<BlePfcClient, BleDeviceSession> {
        val pfcClient = mockk<BlePfcClient>()
        val session = mockk<BleDeviceSession>()
        val advContent = mockk<BleAdvertisementContent>()

        every { session.advertisementContent } returns advContent
        every { session.advertisementContent.polarDeviceId } returns deviceId
        every { session.sessionState } returns BleDeviceSession.DeviceSessionState.SESSION_OPEN
        every { session.fetchClient(PFC_SERVICE) } returns pfcClient

        mockkObject(PolarServiceClientUtils)
        every { PolarServiceClientUtils.sessionPsPfcClientReady(deviceId, any()) } returns session

        return Pair(pfcClient, session)
    }

    private fun mockPsFtpConnection(deviceId: String): Pair<BlePsFtpClient, BleDeviceSession> {
        val client = mockk<BlePsFtpClient>()
        val session = mockk<BleDeviceSession>()
        val advContent = mockk<BleAdvertisementContent>()

        every { session.advertisementContent } returns advContent
        every { session.advertisementContent.polarDeviceId } returns deviceId
        every { session.sessionState } returns BleDeviceSession.DeviceSessionState.SESSION_OPEN
        every { session.fetchClient(BlePsFtpUtils.RFC77_PFTP_SERVICE) } returns client
        every { client.isServiceDiscovered } returns true
        every { client.getNotificationAtomicInteger(any()) } returns AtomicInteger(0)

        mockkObject(PolarServiceClientUtils)
        every { PolarServiceClientUtils.sessionPsFtpClientReady(deviceId, any()) } returns session

        return Pair(client, session)
    }
}