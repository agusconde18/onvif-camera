package com.adasplus.onvif;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * 在SpyDroid的基础上实现ONVIF协议
 * <p>
 * 最好是将{@link SimpleONVIFManager}实现为一个{@link android.app.Service},并保证其运行在一个单独的进程当中.
 * <p>
 * 在使用ONVIF Device Test Tool做协议测试时，首先要确保ONVIF Device Test Tool与我们当前的设备可以互相ping通.
 * 然后就是确保组播地址是可行的.
 * <p>
 * ONVIF当中的Device Discovery Protocol的简要描述(以下的内容是直接)
 * 作为一个兼容ONVIF协议的IPCamera会处于两种状态:Discoverable, UnDiscoverable.
 * 如果处于Discoverable状态下的时候，IPCamera会向外发送hello消息以及自己的状态消息(以组播的方式)
 * <p>
 * 当IPCamera想要从Discoverable状态切换到UnDiscoverable状态的话，需要停止发送
 * Hello消息，同时也要不再处理Probe消息，以及发送Bye消息宣告自己告别当前网络.
 */
public class SimpleONVIFManager {
    private static final String TAG = "simpleOnVifManager";

    private static Handler sProbePacketPoster;

    static {
        HandlerThread workerThread = new HandlerThread("multicast_thread");
        workerThread.start();
        sProbePacketPoster = new Handler(workerThread.getLooper());
    }

    private Context context;
    private String serverIp;

    /**
     * ONVIF协议当中使用的组播的端口号
     */
    private static final int MULTICAST_PORT = 3702;
    /**
     * ONVIF协议当中使用的组播地址是固定的,即"239.255.255.250".
     * 是ONVIF协议的内部约定.
     */
    private static final String MULTICAST_HOST_IP = "239.255.255.250";

    private static final ExecutorService PROBE_PACKET_RECEIVE_EXECUTOR = Executors.newSingleThreadExecutor();

    private static final int PACKET_SO_TIMEOUT = 30000; // 30 seconds

    private static final int HELLO_PACKET_SEND_OUT_INTERVAL = 1000; // 1 second

    private WifiManager.MulticastLock multicastLock;
    private MulticastSocket multicastSocket;
    private final String devId;

    private static final boolean SEND_HELLO_PACKET = false;

    public SimpleONVIFManager(Context context) {
        this.context = context;
        devId = Utilities.getDevId(context);
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            Log.d(TAG, "we have acquired the wifi mulitcast lock");
            multicastLock = wifiManager.createMulticastLock("onvif_data_broadcast");
            multicastLock.acquire();
        }
        multicastSocket = createMulticastSocket();

        serverIp = Utilities.getLocalDevIp(context);
        Log.d(TAG, "the current device ip address we get are --> " + serverIp);
        initData();

        if (SEND_HELLO_PACKET) {
            startSendHelloPacket();
        }

        receiveProbePacket();
    }

    /**
     * 当IPCamera处于Discoverable状态下时，需要向外发送"hello"的组播包.
     */
    private void startSendHelloPacket() {
        sProbePacketPoster.post(new Runnable() {
            @Override
            public void run() {
                String messageId = UUID.randomUUID().toString();
                String appSequenceId = UUID.randomUUID().toString();
                String helloPacket = Utilities.generateHelloPacket(messageId, appSequenceId, devId);

                sendPacket(helloPacket.getBytes());

                sProbePacketPoster.postDelayed(this, HELLO_PACKET_SEND_OUT_INTERVAL);
            }
        });
    }

    private MulticastSocket createMulticastSocket() {
        try {
            if (multicastLock.isHeld()) {
                Log.d(TAG, "we have held the WifiMulticastLock");
            } else {
                Log.d(TAG, "fail to held the WifiMulticastLock, then user may fail to receive the Multicast message");
            }
            InetAddress groupAddress = InetAddress.getByName(MULTICAST_HOST_IP);
            MulticastSocket multicastSocket = new MulticastSocket(MULTICAST_PORT);
            multicastSocket.setReuseAddress(true);
            multicastSocket.setTimeToLive(255);
            multicastSocket.setSoTimeout(PACKET_SO_TIMEOUT);
            // 以下的设置会将组播强制设定为为某个固定网口,这样会出现错误,很严重的错误.
            // 因为在正式使用时,我们同部标机进行对接时,网口的名称就是"eth0",在使用4g联网时,网口就是"ccmni0"
            // 所以不能强制指定,除非确定最终用途
            // multicastSocket.setNetworkInterface(NetworkInterface.getByName("wlan0"));
            // 在具体生产环境当中,Adas设备本身是通过网线连接到部标机当中的,然后通过部标机进行联网
            // 然后我们这里指定网口为网线连接的网口(即连接到部标机的网口)
            // multicastSocket.setNetworkInterface(NetworkInterface.getByName("eth0"));
            multicastSocket.setBroadcast(true);
            multicastSocket.joinGroup(groupAddress);
            return multicastSocket;
        } catch (UnknownHostException e) {
            Log.e(TAG, "the given host of " + MULTICAST_HOST_IP + " are unknown", e);
        } catch (IOException e) {
            Log.e(TAG, "fail to create the mulitcast socket of " + MULTICAST_PORT, e);
        }
        return null;
    }

    private void initData() {
        Log.d(TAG, "init the ONVIF data");
        DeviceBackBean deviceBackBean = DeviceBackBean.getDeviceBackBean();
        deviceBackBean.setIpAddress(serverIp);
        deviceBackBean.setUserName(DeviceStaticInfo.USER_NAME);
        deviceBackBean.setPsw(DeviceStaticInfo.USER_PSW);
        deviceBackBean.setServiceUrl("http://" + serverIp + ":8080/onvif/device_service");
        DeviceBackBean.setDeviceBackBean(deviceBackBean);
    }

    private void sendPacket(byte[] data) {
        try {
            InetAddress groupAddress = InetAddress.getByName(MULTICAST_HOST_IP);
            DatagramPacket packet = new DatagramPacket(data,
                    data.length,
                    groupAddress,
                    MULTICAST_PORT);
            if (multicastSocket != null) {
                multicastSocket.send(packet);
            }
        } catch (UnknownHostException e) {
            Log.e(TAG, "fail to get the group address of " + MULTICAST_HOST_IP, e);
        } catch (IOException e) {
            Log.e(TAG, "fail send packet out", e);
        }
    }

    private ReentrantLock probePacketHandleLock = new ReentrantLock();

    /**
     * 接收来自IPCameraViewer的探测packet
     */
    private void receiveProbePacket() {
        Log.d(TAG, "start receive the Probe packet");
        if (multicastSocket == null) {
            Log.e(TAG, "the multicast socket are null");
            return;
        }
        PROBE_PACKET_RECEIVE_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "start receive probe packet");
                    InetAddress groupAddress = InetAddress.getByName(MULTICAST_HOST_IP);

                    // 我们可能同时会收到来自于很多设备的packet
                    final byte[] receiveDataBuffer = new byte[1024 * 4];
                    for (; ; ) {
                        probePacketHandleLock.lock();
                        DatagramPacket packet = new DatagramPacket(
                                receiveDataBuffer,
                                receiveDataBuffer.length,
                                groupAddress,
                                MULTICAST_PORT);
                        multicastSocket.receive(packet);
                        probePacketHandleLock.unlock();

                        Log.e(TAG, "we have received the probe packet");
                        handleReceivedProbePacket(packet);
                    }
                } catch (UnknownHostException e) {
                    Log.e(TAG, "Exception happened while get the InetAddress of " + MULTICAST_HOST_IP, e);
                } catch (IOException e) {
                    if (e instanceof SocketTimeoutException) {
                        // 接收probe消息超时，重新准备接收.
                        Log.w(TAG, "start monitor probe message again");
                    } else {
                        Log.e(TAG, "Exception happened while receive the packet", e);
                    }
                    // 重新进行尝试
                    PROBE_PACKET_RECEIVE_EXECUTOR.execute(this);
                }
            }
        });
    }

    /**
     * 处理我们接收到的探测packet
     */
    private void handleReceivedProbePacket(DatagramPacket probePacket) {
        Log.d(TAG, "--> the raw packet we received are \n" + Utilities.flattenDatagramPacket(probePacket));
        final int packetDataOffset = probePacket.getOffset();
        final int packetDataLen = probePacket.getLength();
        byte[] receivedData = new byte[packetDataLen];
        System.arraycopy(probePacket.getData(), packetDataOffset, receivedData, 0, packetDataLen);

        String receivedRawMsg = new String(receivedData, packetDataOffset, packetDataLen);
        Log.d(TAG, "the raw received message are " + receivedRawMsg);

        // 我们此时接收到了由IPCameraViewer发送过来的探测数据之后，
        // 然后就是返回响应包给对应的IPCameraViewer
        // 首先解析我们接收到的数据
        SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
        SAXParser saxParser = null;
        try {
            saxParser = saxParserFactory.newSAXParser();
        } catch (ParserConfigurationException e) {
            Log.e(TAG, "SAX Parse Configuration Exception happened", e);
        } catch (SAXException e) {
            Log.e(TAG, "SAX Exception happened", e);
        }
        if (saxParser == null) {
            Log.e(TAG, "fail to get the SAX Parser, neglect this discovery packet");
            return;
        }

        ONVIFPacketHandler handler = new ONVIFPacketHandler();
        try {
            saxParser.parse(new ByteArrayInputStream(receivedData), handler);
        } catch (SAXException e) {
            Log.e(TAG, "SAXException happened while processing the received message", e);
        } catch (IOException e) {
            Log.e(TAG, "IOException happened while processing the received message", e);
        }
        ONVIFReqPacketHeader reqHeader = handler.getReqHeader();
        String reqMessageId = reqHeader.getMessageId();
        String reqAction = reqHeader.getAction();

        Log.d(TAG, String.format("req message Id are %s, req action are %s", reqMessageId, reqAction));

        // 返回响应的probeMatch packet
        String devId = Utilities.getDevId(context);
        Log.v(TAG, "current device ID are " + devId);

        // 我们在返回的probeMatch packet当中放入了当前设备(即IPCamera)的IP地址
        // 这样客户端(IPCamera-Viewer)就可以借助这个IP地址，直接向这个IP地址发起
        // ONVIF请求(ONVIF底层是基于HTTP协议的),然后我们自己(IPCamera)就可以处理
        // 这些请求，然后做出对应的操作,例如返回StreamUri等.
        String sendBack = Utilities.generateDeviceProbeMatchPacket1(
                devId,
                reqMessageId,
                serverIp);
        byte[] sendBuf = sendBack.getBytes();

        try {
            InetAddress probePacketAddress = probePacket.getAddress();
            int probePacketPort = probePacket.getPort();
            Log.d(TAG, "the probe packet address are " + probePacketAddress + ":" + probePacketPort);

            DatagramPacket packet = new DatagramPacket(sendBuf,
                    sendBuf.length, probePacketAddress, probePacketPort);

            Log.v(TAG, "send the ProbeMatch packet back");
            multicastSocket.send(packet);
        } catch (final Exception e) {
            Log.e(TAG, "Exception happened while we send the response packet", e);
        }
    }
}