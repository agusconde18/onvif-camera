package com.adasplus.onvif.http;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.adasplus.onvif.Utilities;

import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.RequestLine;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.ContentProducer;
import org.apache.http.entity.EntityTemplate;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.util.EntityUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URLDecoder;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 同{@link ModAssetServer}的工作方式一样。
 * 只是这里针对的是ONVIF协议的处理.
 */
public class ModOnvifServer implements HttpRequestHandler {
    private static final String TAG = "ModOnvifServer";

    /**
     * 参考{@link ModAssetServer#PATTERN}.
     */
    public static final String PATTERN = "/onvif/*";

    /**
     * ONVIF协议支持的数据格式
     */
    public static final String[] MIME_MEDIA_TYPES = {
            "xml", "onvif/xml",
            "wsdl", "onvif/wsdl",
            "xsd", "onvif/xsd"
    };

    private final TinyHttpServer mServer;

    private final Context mContext;

    private int mRtspServerPort;

    private static final int DEFAULT_RTSP_PORT = 8086;
    private static final String DEFAULT_RTSP_ADDRESS = "192.168.100.110";

    public ModOnvifServer(TinyHttpServer server) {
        this.mServer = server;
        mContext = server.getContext();
        mRtspServerPort = DEFAULT_RTSP_PORT;
    }

    @Override
    public void handle(HttpRequest httpRequest, HttpResponse httpResponse, HttpContext httpContext) throws HttpException, IOException {
        AbstractHttpEntity body;

        Log.d(TAG, "Handle the request in ModOnvifServer");
        RequestLine requestLine = httpRequest.getRequestLine();
        String method = requestLine.getMethod().toUpperCase(Locale.ENGLISH);
        Log.i(TAG, "requestMethod are " + method);
        if (!method.equals("GET") && !method.equals("HEAD") && !method.equals("POST")) {
            Log.e(TAG, "the request method \"" + method + "\" are not GET HEAD or POST, we cannot process such request");
            throw new MethodNotSupportedException(method + " method not supported");
        }
        Calendar calendar = Calendar.getInstance();
        final int year = calendar.get(Calendar.YEAR);
        final int month = calendar.get(Calendar.MONTH) + 1;
        final int day = calendar.get(Calendar.DAY_OF_MONTH);
        final int hour = calendar.get(Calendar.HOUR_OF_DAY);
        final int minutes = calendar.get(Calendar.MINUTE);
        final int seconds = calendar.get(Calendar.SECOND);

        String serverIp = Utilities.getLocalDevIp(mContext);

        final String url = URLDecoder.decode(httpRequest.getRequestLine().getUri());
        Log.d(TAG, "the request URL are " + url);
        if (httpRequest instanceof HttpEntityEnclosingRequest) {
            HttpEntity entity = ((HttpEntityEnclosingRequest) httpRequest).getEntity();
            byte[] entityContent = EntityUtils.toByteArray(entity);

            String requestContent = new String(entityContent);
            Log.d(TAG, "the request content are " + requestContent);
            // 关于不同的请求内容对应的不同的含义，直接参考位于项目根目录当中的ONVIF_Protocol.md
            // 以下的请求结果返回都是/onvif/device_service接口的返回数据
            httpResponse.setStatusCode(HttpStatus.SC_OK);
            if (requestContent.contains("GetSystemDateAndTime")) {
                Log.d(TAG, "Handle the response of GetSystemDateAndTime request");
                TimeZone timeZone = TimeZone.getDefault();
                String timeZoneName = timeZone.getDisplayName() + timeZone.getID();

                String getSystemDateNTimeResponse = constructOnvifGetSystemDateNTimeResponse(timeZoneName,
                        year, month, day, hour, minutes, seconds);
                byte[] responseContentByteArr = getSystemDateNTimeResponse.getBytes("UTF-8");
                InputStream servicesResponseInputStream = new ByteArrayInputStream(responseContentByteArr);
                body = new InputStreamEntity(servicesResponseInputStream, responseContentByteArr.length);
            } else if (requestContent.contains("GetCapabilities")) {
                Log.d(TAG, "Handle the response of GetCapabilities request");
                String getCapabilitiesResponse = constructOnvifGetCapabilitiesResponse(serverIp);
                byte[] responseContentByteArr = getCapabilitiesResponse.getBytes("UTF-8");
                InputStream servicesResponseInputStream = new ByteArrayInputStream(responseContentByteArr);
                body = new InputStreamEntity(servicesResponseInputStream, responseContentByteArr.length);
            } else if (requestContent.contains("GetServices")) {
                Log.d(TAG, "is GetServices interface");
                Log.d(TAG, "return the device base info");
                String getServicesResponse = constructOnvifDeviceServiceResponse(serverIp);
                byte[] responseContentByteArr = getServicesResponse.getBytes("UTF-8");
                InputStream servicesResponseInputStream = new ByteArrayInputStream(responseContentByteArr);
                body = new InputStreamEntity(servicesResponseInputStream, responseContentByteArr.length);
            } else if (requestContent.contains("GetDeviceInformation")) {
                Log.d(TAG, "is GetDeviceInformation interface");
                String getDevInfoResponse = constructOnvifDevInfoResponse(DEV_MANUFACTURE, DEV_MODEL,
                        DEV_FIRMWARE_VERSION, DEV_SERIAL_NUM, Utilities.getDevId(mContext));
                byte[] responseContentByteArr = getDevInfoResponse.getBytes("UTF-8");
                InputStream devInfoResponseInputStream = new ByteArrayInputStream(responseContentByteArr);
                body = new InputStreamEntity(devInfoResponseInputStream, responseContentByteArr.length);
            } else if (requestContent.contains("GetProfiles")) {
                Log.d(TAG, "is GetProfiles interface");
                // TODO: the following video attributes value should be provided by the native IPCamera
                final int videoResWidth = 320;
                final int videoResHeight = 240;
                final int videoBitRate = 135000;
                String getProfilesResponse = constructOnvifGetProfilesResponse(videoResWidth, videoResHeight, videoBitRate);
                byte[] responseContentByteArr = getProfilesResponse.getBytes("UTF-8");
                InputStream profileResponseInputStream = new ByteArrayInputStream(responseContentByteArr);
                body = new InputStreamEntity(profileResponseInputStream, responseContentByteArr.length);
            } else if (requestContent.contains("GetStreamUri")) {
                Log.d(TAG, "is GetStreamUri interface");
                String rtspServerUrl = "rtsp://" + serverIp + ":" + mRtspServerPort + "/";
                String getStreamUriResponse = constructOnvifStreamUriResponse(rtspServerUrl);
                byte[] responseContentByteArr = getStreamUriResponse.getBytes("UTF-8");
                InputStream streamUriContentInputStream = new ByteArrayInputStream(responseContentByteArr);
                body = new InputStreamEntity(streamUriContentInputStream, responseContentByteArr.length);
            } else {
                Log.e(TAG, "not known interface");
                httpResponse.setStatusCode(HttpStatus.SC_NOT_FOUND);
                body = new EntityTemplate(new ContentProducer() {
                    public void writeTo(final OutputStream outstream) throws IOException {
                        OutputStreamWriter writer = new OutputStreamWriter(outstream, "UTF-8");
                        writer.write("<html><body><h1>");
                        writer.write("File ");
                        writer.write("www" + url);
                        writer.write(" not found");
                        writer.write("</h1></body></html>");
                        writer.flush();
                    }
                });
                httpResponse.setEntity(body);
                return;
            }
            // body.setContentType("onvif/xml; charset=UTF-8");
            httpResponse.setEntity(body);
        }
    }

    private static final String DEV_MANUFACTURE = Build.MANUFACTURER;
    private static final String DEV_MODEL = Build.MODEL;
    private static final String DEV_FIRMWARE_VERSION = Build.VERSION.RELEASE;
    @SuppressLint("HardwareIds")
    private static final String DEV_SERIAL_NUM = Build.SERIAL;

    /**
     * 针对GetServices接口的返回数据
     * <p>
     * 返回数据是直接根据Ocular程序运行抓包获取到的(因为没有Ocular的源码，所以只能这样获取
     * 数据包)
     *
     * @param localIpAddress 当前设备的IP地址
     */
    private String constructOnvifDeviceServiceResponse(String localIpAddress) {
        String responseContent = "<?xml version='1.0' encoding='utf-8' ?>\n" +
                "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"\n" +
                "    xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"\n" +
                "    xmlns:tev=\"http://www.onvif.org/ver10/events/wsdl\"\n" +
                "    xmlns:timg=\"http://www.onvif.org/ver20/imaging/wsdl\"\n" +
                "    xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\"\n" +
                "    xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"\n" +
                "    xmlns:tt=\"http://www.onvif.org/ver10/schema\">\n" +
                "    <s:Body>\n" +
                "        <tds:GetServicesResponse>\n" +
                "            <tds:Service>\n" +
                "                <tds:Namespace>http://www.onvif.org/ver10/device/wsdl</tds:Namespace>\n" +
                "                <tds:XAddr>http://" + localIpAddress + ":8081:8081/onvif/device_service</tds:XAddr>\n" +
                "                <tds:Capabilities>\n" +
                "                    <tds:Capabilities>\n" +
                "                        <tds:Network\n" +
                "                            DHCPv6=\"false\"\n" +
                "                            Dot11Configuration=\"false\"\n" +
                "                            Dot1XConfigurations=\"0\"\n" +
                "                            DynDNS=\"false\"\n" +
                "                            HostnameFromDHCP=\"true\"\n" +
                "                            IPFilter=\"true\"\n" +
                "                            IPVersion6=\"false\"\n" +
                "                            NTP=\"1\"\n" +
                "                            ZeroConfiguration=\"true\" />\n" +
                "                        <tds:Security\n" +
                "                            AccessPolicyConfig=\"true\"\n" +
                "                            DefaultAccessPolicy=\"false\"\n" +
                "                            Dot1X=\"false\"\n" +
                "                            HttpDigest=\"false\"\n" +
                "                            KerberosToken=\"false\"\n" +
                "                            MaxUsers=\"10\"\n" +
                "                            OnboardKeyGeneration=\"false\"\n" +
                "                            RELToken=\"false\"\n" +
                "                            RemoteUserHandling=\"false\"\n" +
                "                            SAMLToken=\"false\"\n" +
                "                            TLS1.0=\"false\"\n" +
                "                            TLS1.1=\"false\"\n" +
                "                            TLS1.2=\"false\"\n" +
                "                            UsernameToken=\"true\"\n" +
                "                            X.509Token=\"false\" />\n" +
                "                        <tds:System\n" +
                "                            DiscoveryBye=\"true\"\n" +
                "                            DiscoveryResolve=\"true\"\n" +
                "                            FirmwareUpgrade=\"false\"\n" +
                "                            HttpFirmwareUpgrade=\"false\"\n" +
                "                            HttpSupportInformation=\"false\"\n" +
                "                            HttpSystemBackup=\"false\"\n" +
                "                            HttpSystemLogging=\"false\"\n" +
                "                            RemoteDiscovery=\"false\"\n" +
                "                            SystemBackup=\"false\"\n" +
                "                            SystemLogging=\"false\" />\n" +
                "                    </tds:Capabilities>\n" +
                "                </tds:Capabilities>\n" +
                "                <tds:Version>\n" +
                "                    <tt:Major>1</tt:Major>\n" +
                "                    <tt:Minor>70</tt:Minor>\n" +
                "                </tds:Version>\n" +
                "            </tds:Service>\n" +
                "            <tds:Service>\n" +
                "                <tds:Namespace>http://www.onvif.org/ver10/events/wsdl</tds:Namespace>\n" +
                "                <tds:XAddr>http://" + localIpAddress + ":8081:8081/event/evtservice</tds:XAddr>\n" +
                "                <tds:Capabilities>\n" +
                "                    <tev:Capabilities\n" +
                "                        MaxNotificationProducers=\"6\"\n" +
                "                        MaxPullPoints=\"2\"\n" +
                "                        PersistentNotificationStorage=\"false\"\n" +
                "                        WSPausableSubscriptionManagerInterfaceSupport=\"false\"\n" +
                "                        WSPullPointSupport=\"false\"\n" +
                "                        WSSubscriptionPolicySupport=\"false\" />\n" +
                "                </tds:Capabilities>\n" +
                "                <tds:Version>\n" +
                "                    <tt:Major>1</tt:Major>\n" +
                "                    <tt:Minor>70</tt:Minor>\n" +
                "                </tds:Version>\n" +
                "            </tds:Service>\n" +
                "            <tds:Service>\n" +
                "                <tds:Namespace>http://www.onvif.org/ver20/imaging/wsdl</tds:Namespace>\n" +
                "                <tds:XAddr>http://" + localIpAddress + ":8081/onvif/imaging</tds:XAddr>\n" +
                "                <tds:Capabilities>\n" +
                "                    <timg:Capabilities ImageStabilization=\"false\" />\n" +
                "                </tds:Capabilities>\n" +
                "                <tds:Version>\n" +
                "                    <tt:Major>2</tt:Major>\n" +
                "                    <tt:Minor>30</tt:Minor>\n" +
                "                </tds:Version>\n" +
                "            </tds:Service>\n" +
                "            <tds:Service>\n" +
                "                <tds:Namespace>http://www.onvif.org/ver10/media/wsdl</tds:Namespace>\n" +
                "                <tds:XAddr>http://" + localIpAddress + ":8081:8081/onvif/media</tds:XAddr>\n" +
                "                <tds:Capabilities>\n" +
                "                    <trt:Capabilities\n" +
                "                        OSD=\"false\"\n" +
                "                        Rotation=\"false\"\n" +
                "                        SnapshotUri=\"true\"\n" +
                "                        VideoSourceMode=\"false\">\n" +
                "                        <trt:ProfileCapabilities MaximumNumberOfProfiles=\"10\" />\n" +
                "                        <trt:StreamingCapabilities\n" +
                "                            NoRTSPStreaming=\"false\"\n" +
                "                            NonAggregateControl=\"false\"\n" +
                "                            RTPMulticast=\"false\"\n" +
                "                            RTP_RTSP_TCP=\"true\"\n" +
                "                            RTP_TCP=\"false\" />\n" +
                "                    </trt:Capabilities>\n" +
                "                </tds:Capabilities>\n" +
                "                <tds:Version>\n" +
                "                    <tt:Major>1</tt:Major>\n" +
                "                    <tt:Minor>70</tt:Minor>\n" +
                "                </tds:Version>\n" +
                "            </tds:Service>\n" +
                "            <tds:Service>\n" +
                "                <tds:Namespace>http://www.onvif.org/ver20/ptz/wsdl</tds:Namespace>\n" +
                "                <tds:XAddr>http://" + localIpAddress + ":8081:8081/onvif/ptz</tds:XAddr>\n" +
                "                <tds:Capabilities>\n" +
                "                    <tptz:Capabilities\n" +
                "                        EFlip=\"false\"\n" +
                "                        GetCompatibleConfigurations=\"false\"\n" +
                "                        Reverse=\"false\" />\n" +
                "                </tds:Capabilities>\n" +
                "                <tds:Version>\n" +
                "                    <tt:Major>2</tt:Major>\n" +
                "                    <tt:Minor>50</tt:Minor>\n" +
                "                </tds:Version>\n" +
                "            </tds:Service>\n" +
                "        </tds:GetServicesResponse>\n" +
                "    </s:Body>\n" +
                "</s:Envelope>\n";

        return responseContent;
    }


    /**
     * 用于响应IPCamera-Viewer发送的查看设备信息的请求
     *
     * @param manufacture     设备的生厂商,对于Ocular设备来说，这个值是: Emiliano Schmid
     * @param model           设备的型号名称,对于Ocular设备来说，这个值是: Ocular
     * @param firmwareVersion 设备的固件版本号,对于Ocular设备来说，这个值是: none
     * @param serialNum       设备的序列号,对于Ocular设备来说，这个值是:000001
     * @param hardwareID      设备的硬件ID(例如IMEI号码),对于Ocular设备来说，这个值是: Android
     * @return 响应用户的获取设备信息的请求
     */
    private String constructOnvifDevInfoResponse(String manufacture,
                                                 String model,
                                                 String firmwareVersion,
                                                 String serialNum,
                                                 String hardwareID) {
        Log.d(TAG, String.format("device manufacture : %s, device model: %s, device firmware version: %s," +
                        "device serial number: %s, device hardware ID: %s", manufacture, model, firmwareVersion,
                serialNum, hardwareID));

        String response = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\"\n" +
                "    xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\">\n" +
                "    <env:Body>\n" +
                "        <tds:GetDeviceInformationResponse>\n" +
                "            <tds:Manufacturer>" + manufacture + "</tds:Manufacturer>\n" +
                "            <tds:Model>" + model + "</tds:Model>\n" +
                "            <tds:FirmwareVersion>" + firmwareVersion + "</tds:FirmwareVersion>\n" +
                "            <tds:SerialNumber>" + serialNum + "</tds:SerialNumber>\n" +
                "            <tds:HardwareId>" + hardwareID + "</tds:HardwareId>\n" +
                "        </tds:GetDeviceInformationResponse>\n" +
                "    </env:Body>\n" +
                "</env:Envelope>";

        return response;
    }

    private String constructOnvifGetProfilesResponse(final int videoWidth, final int videoHeight, final int bitRate) {
        Log.d(TAG, String.format("the profile : video width : %s, video height : %s, video bitrate : %s", videoWidth,
                videoHeight, bitRate));
        // TODO: 这里的信息需要更加准确的控制
        // 关于分辨率的信息，应该从RtspServer当中动态获取
        String response = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\"\n" +
                "    xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"\n" +
                "    xmlns:tt=\"http://www.onvif.org/ver10/schema\">\n" +
                "    <env:Body>\n" +
                "        <trt:GetProfilesResponse>\n" +
                "            <trt:Profiles\n" +
                "                fixed=\"false\"\n" +
                "                token=\"Profile1\">\n" +
                "                <tt:Name>Profile1</tt:Name>\n" +
                "                <tt:VideoSourceConfiguration token=\"VideoSourceConfiguration0_0\">\n" +
                "                    <tt:Name>VideoSourceConfiguration0_0</tt:Name>\n" +
                "                    <tt:UseCount>1</tt:UseCount>\n" +
                "                    <tt:SourceToken>VideoSource0</tt:SourceToken>\n" +
                "                    <tt:Bounds\n" +
                "                        height=\"" + videoHeight + "\"\n" +
                "                        width=\"" + videoWidth + "\"\n" +
                "                        x=\"0\"\n" +
                "                        y=\"0\" />\n" +
                "                </tt:VideoSourceConfiguration>\n" +
                "                <tt:VideoEncoderConfiguration token=\"VideoEncoderConfiguration0_0\">\n" +
                "                    <tt:Name>VideoEncoderConfiguration0_0</tt:Name>\n" +
                "                    <tt:UseCount>3683892</tt:UseCount>\n" +
                "                    <tt:Encoding>H264</tt:Encoding>\n" +
                "                    <tt:Resolution>\n" +
                "                        <tt:Width>" + videoWidth + "</tt:Width>\n" +
                "                        <tt:Height>" + videoHeight + "</tt:Height>\n" +
                "                    </tt:Resolution>\n" +
                "                    <tt:Quality>44.0</tt:Quality>\n" +
                "                    <tt:RateControl>\n" +
                "                        <tt:FrameRateLimit>5</tt:FrameRateLimit>\n" +
                "                        <tt:EncodingInterval>1</tt:EncodingInterval>\n" +
                "                        <tt:BitrateLimit>" + bitRate + "</tt:BitrateLimit>\n" +
                "                    </tt:RateControl>\n" +
                "                    <tt:Multicast>\n" +
                "                        <tt:Address>\n" +
                "                            <tt:Type>IPv4</tt:Type>\n" +
                "                            <tt:IPv4Address>0.0.0.0</tt:IPv4Address>\n" +
                "                            <tt:IPv6Address />\n" +
                "                        </tt:Address>\n" +
                "                        <tt:Port>0</tt:Port>\n" +
                "                        <tt:TTL>0</tt:TTL>\n" +
                "                        <tt:AutoStart>false</tt:AutoStart>\n" +
                "                    </tt:Multicast>\n" +
                "                    <tt:SessionTimeout>PT30S</tt:SessionTimeout>\n" +
                "                </tt:VideoEncoderConfiguration>\n" +
                "            </trt:Profiles>\n" +
                "        </trt:GetProfilesResponse>\n" +
                "    </env:Body>\n" +
                "</env:Envelope>";

        return response;
    }

    /**
     * @param rtspUrl 用于观看视频直播的rtsp地址，例如对于Ocular应用来说，他返回给IPCamera-Viewer
     *                的地址就是:rtsp://172.16.0.50:8081:8081/h264
     *                当然不同的应用可以定义不同格式的地址.
     * @return 返回给客户端用于播放rtsp直播视频流的url
     */
    private String constructOnvifStreamUriResponse(String rtspUrl) {
        Log.d(TAG, "the stream uri return to client are : " + rtspUrl);

        String response = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\"\n" +
                "    xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"\n" +
                "    xmlns:tt=\"http://www.onvif.org/ver10/schema\">\n" +
                "    <env:Body>\n" +
                "        <trt:GetStreamUriResponse>\n" +
                "            <trt:MediaUri>\n" +
                "                <tt:Uri>" + rtspUrl + "</tt:Uri>\n" +
                "                <tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>\n" +
                "                <tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>\n" +
                "                <tt:Timeout>P1Y</tt:Timeout>\n" +
                "            </trt:MediaUri>\n" +
                "        </trt:GetStreamUriResponse>\n" +
                "    </env:Body>\n" +
                "</env:Envelope>";

        return response;
    }

    /**
     * Using to respond the request of /onvif/device_service "GetSystemDateAndTime"
     *
     * @return the constructed response content
     */
    private String constructOnvifGetSystemDateNTimeResponse(String timezone, int hour, int minute, int second,
                                                            int year, int month, int day) {
        Log.d(TAG, "handle the GetSystemDateAndTime response with timezone of " + timezone);

        String response = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" xmlns:tt=\"http://www.onvif.org/ver10/schema\">\n" +
                "    <env:Body>\n" +
                "        <tds:GetSystemDateAndTimeResponse>\n" +
                "            <tds:SystemDateAndTime>\n" +
                "                <tt:DateTimeType>Manual</tt:DateTimeType>\n" +
                "                <tt:DaylightSavings>false</tt:DaylightSavings>\n" +
                "                <tt:TimeZone>\n" +
                "                    <tt:TZ>" + timezone + "</tt:TZ>\n" +
                "                </tt:TimeZone>\n" +
                "                <tt:UTCDateTime>\n" +
                "                    <tt:Time>\n" +
                "                        <tt:Hour>" + hour + "</tt:Hour>\n" +
                "                        <tt:Minute>" + minute + "</tt:Minute>\n" +
                "                        <tt:Second>" + second + "</tt:Second>\n" +
                "                    </tt:Time>\n" +
                "                    <tt:Date>\n" +
                "                        <tt:Year>" + year + "</tt:Year>\n" +
                "                        <tt:Month>" + month + "</tt:Month>\n" +
                "                        <tt:Day>" + day + "</tt:Day>\n" +
                "                    </tt:Date>\n" +
                "                </tt:UTCDateTime>\n" +
                "            </tds:SystemDateAndTime>\n" +
                "        </tds:GetSystemDateAndTimeResponse>\n" +
                "    </env:Body>\n" +
                "</env:Envelope>";

        return response;
    }


    /**
     * construct the GetCapabilities response
     *
     * @param serverIpAddress eg. 192.168.100.110
     * @return the GetCapabilitiesResponse
     */
    private String constructOnvifGetCapabilitiesResponse(String serverIpAddress) {
        Log.d(TAG, "respond the GetCapabilities request with server ip address of " + serverIpAddress);

        String response = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<env:Envelope xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\" xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:hikwsd=\"http://www.onvifext.com/onvif/ext/ver10/wsdl\" xmlns:hikxsd=\"http://www.onvifext.com/onvif/ext/ver10/schema\" xmlns:http=\"http://schemas.xmlsoap.org/wsdl/http\" xmlns:soapenc=\"http://www.w3.org/2003/05/soap-encoding\" xmlns:tan=\"http://www.onvif.org/ver20/analytics/wsdl\" xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" xmlns:ter=\"http://www.onvif.org/ver10/error\" xmlns:tev=\"http://www.onvif.org/ver10/events/wsdl\" xmlns:timg=\"http://www.onvif.org/ver20/imaging/wsdl\" xmlns:tmd=\"http://www.onvif.org/ver10/deviceIO/wsdl\" xmlns:tns1=\"http://www.onvif.org/ver10/topics\" xmlns:tnshik=\"http://www.hikvision.com/2011/event/topics\" xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\" xmlns:trc=\"http://www.onvif.org/ver10/recording/wsdl\" xmlns:trp=\"http://www.onvif.org/ver10/replay/wsdl\" xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" xmlns:tse=\"http://www.onvif.org/ver10/search/wsdl\" xmlns:tst=\"http://www.onvif.org/ver10/storage/wsdl\" xmlns:tt=\"http://www.onvif.org/ver10/schema\" xmlns:wsa=\"http://www.w3.org/2005/08/addressing\" xmlns:wsadis=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:wsaw=\"http://www.w3.org/2006/05/addressing/wsdl\" xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl\" xmlns:wsnt=\"http://docs.oasis-open.org/wsn/b-2\" xmlns:wsntw=\"http://docs.oasis-open.org/wsn/bw-2\" xmlns:wsoap12=\"http://schemas.xmlsoap.org/wsdl/soap12\" xmlns:wsrf-bf=\"http://docs.oasis-open.org/wsrf/bf-2\" xmlns:wsrf-r=\"http://docs.oasis-open.org/wsrf/r-2\" xmlns:wsrf-rw=\"http://docs.oasis-open.org/wsrf/rw-2\" xmlns:wstop=\"http://docs.oasis-open.org/wsn/t-1\" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n" +
                "    <env:Body>\n" +
                "        <tds:GetCapabilitiesResponse>\n" +
                "            <tds:Capabilities>\n" +
                "                <tt:Analytics>\n" +
                "                    <tt:XAddr>http://" + serverIpAddress + "/onvif/Analytics</tt:XAddr>\n" +
                "                    <tt:RuleSupport>true</tt:RuleSupport>\n" +
                "                    <tt:AnalyticsModuleSupport>true</tt:AnalyticsModuleSupport>\n" +
                "                </tt:Analytics>\n" +
                "                <tt:Device>\n" +
                "                    <tt:XAddr>http://" + serverIpAddress + "/onvif/device_service</tt:XAddr>\n" +
                "                    <tt:Network>\n" +
                "                        <tt:IPFilter>true</tt:IPFilter>\n" +
                "                        <tt:ZeroConfiguration>true</tt:ZeroConfiguration>\n" +
                "                        <tt:IPVersion6>false</tt:IPVersion6>\n" +
                "                        <tt:DynDNS>true</tt:DynDNS>\n" +
                "                        <tt:Extension>\n" +
                "                            <tt:Dot11Configuration>false</tt:Dot11Configuration>\n" +
                "                            <tt:Extension>\n" +
                "                                <tt:DHCPv6>true</tt:DHCPv6>\n" +
                "                                <tt:Dot1XConfigurations>0</tt:Dot1XConfigurations>\n" +
                "                            </tt:Extension>\n" +
                "                        </tt:Extension>\n" +
                "                    </tt:Network>\n" +
                "                    <tt:System>\n" +
                "                        <tt:DiscoveryResolve>false</tt:DiscoveryResolve>\n" +
                "                        <tt:DiscoveryBye>true</tt:DiscoveryBye>\n" +
                "                        <tt:RemoteDiscovery>true</tt:RemoteDiscovery>\n" +
                "                        <tt:SystemBackup>true</tt:SystemBackup>\n" +
                "                        <tt:SystemLogging>true</tt:SystemLogging>\n" +
                "                        <tt:FirmwareUpgrade>true</tt:FirmwareUpgrade>\n" +
                "                        <tt:SupportedVersions>\n" +
                "                            <tt:Major>2</tt:Major>\n" +
                "                            <tt:Minor>40</tt:Minor>\n" +
                "                        </tt:SupportedVersions>\n" +
                "                        <tt:SupportedVersions>\n" +
                "                            <tt:Major>2</tt:Major>\n" +
                "                            <tt:Minor>20</tt:Minor>\n" +
                "                        </tt:SupportedVersions>\n" +
                "                        <tt:SupportedVersions>\n" +
                "                            <tt:Major>2</tt:Major>\n" +
                "                            <tt:Minor>10</tt:Minor>\n" +
                "                        </tt:SupportedVersions>\n" +
                "                        <tt:SupportedVersions>\n" +
                "                            <tt:Major>2</tt:Major>\n" +
                "                            <tt:Minor>0</tt:Minor>\n" +
                "                        </tt:SupportedVersions>\n" +
                "                        <tt:Extension>\n" +
                "                            <tt:HttpFirmwareUpgrade>false</tt:HttpFirmwareUpgrade>\n" +
                "                            <tt:HttpSystemBackup>true</tt:HttpSystemBackup>\n" +
                "                            <tt:HttpSystemLogging>false</tt:HttpSystemLogging>\n" +
                "                            <tt:HttpSupportInformation>false</tt:HttpSupportInformation>\n" +
                "                        </tt:Extension>\n" +
                "                    </tt:System>\n" +
                "                    <tt:IO>\n" +
                "                        <tt:InputConnectors>1</tt:InputConnectors>\n" +
                "                        <tt:RelayOutputs>1</tt:RelayOutputs>\n" +
                "                        <tt:Extension>\n" +
                "                            <tt:Auxiliary>false</tt:Auxiliary>\n" +
                "                            <tt:AuxiliaryCommands>nothing</tt:AuxiliaryCommands>\n" +
                "                            <tt:Extension />\n" +
                "                        </tt:Extension>\n" +
                "                    </tt:IO>\n" +
                "                    <tt:Security>\n" +
                "                        <tt:TLS1.1>false</tt:TLS1.1>\n" +
                "                        <tt:TLS1.2>false</tt:TLS1.2>\n" +
                "                        <tt:OnboardKeyGeneration>false</tt:OnboardKeyGeneration>\n" +
                "                        <tt:AccessPolicyConfig>false</tt:AccessPolicyConfig>\n" +
                "                        <tt:X.509Token>false</tt:X.509Token>\n" +
                "                        <tt:SAMLToken>false</tt:SAMLToken>\n" +
                "                        <tt:KerberosToken>false</tt:KerberosToken>\n" +
                "                        <tt:RELToken>false</tt:RELToken>\n" +
                "                        <tt:Extension>\n" +
                "                            <tt:TLS1.0>false</tt:TLS1.0>\n" +
                "                            <tt:Extension>\n" +
                "                                <tt:Dot1X>false</tt:Dot1X>\n" +
                "                                <tt:SupportedEAPMethod>0</tt:SupportedEAPMethod>\n" +
                "                                <tt:RemoteUserHandling>false</tt:RemoteUserHandling>\n" +
                "                            </tt:Extension>\n" +
                "                        </tt:Extension>\n" +
                "                    </tt:Security>\n" +
                "                </tt:Device>\n" +
                "                <tt:Events>\n" +
                "                    <tt:XAddr>http://" + serverIpAddress + "/onvif/Events</tt:XAddr>\n" +
                "                    <tt:WSSubscriptionPolicySupport>true</tt:WSSubscriptionPolicySupport>\n" +
                "                    <tt:WSPullPointSupport>true</tt:WSPullPointSupport>\n" +
                "                    <tt:WSPausableSubscriptionManagerInterfaceSupport>false\n" +
                "                    </tt:WSPausableSubscriptionManagerInterfaceSupport>\n" +
                "                </tt:Events>\n" +
                "                <tt:Imaging>\n" +
                "                    <tt:XAddr>http://" + serverIpAddress + "/onvif/Imaging</tt:XAddr>\n" +
                "                </tt:Imaging>\n" +
                "                <tt:Media>\n" +
                "                    <tt:XAddr>http://" + serverIpAddress + "/onvif/Media</tt:XAddr>\n" +
                "                    <tt:StreamingCapabilities>\n" +
                "                        <tt:RTPMulticast>true</tt:RTPMulticast>\n" +
                "                        <tt:RTP_TCP>true</tt:RTP_TCP>\n" +
                "                        <tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP>\n" +
                "                    </tt:StreamingCapabilities>\n" +
                "                    <tt:Extension>\n" +
                "                        <tt:ProfileCapabilities>\n" +
                "                            <tt:MaximumNumberOfProfiles>10</tt:MaximumNumberOfProfiles>\n" +
                "                        </tt:ProfileCapabilities>\n" +
                "                    </tt:Extension>\n" +
                "                </tt:Media>\n" +
                "                <tt:Extension>\n" +
                "                    <hikxsd:hikCapabilities>\n" +
                "                        <hikxsd:XAddr>http://" + serverIpAddress + "/onvif/hik_ext</hikxsd:XAddr>\n" +
                "                        <hikxsd:IOInputSupport>true</hikxsd:IOInputSupport>\n" +
                "                        <hikxsd:PrivacyMaskSupport>true</hikxsd:PrivacyMaskSupport>\n" +
                "                        <hikxsd:PTZ3DZoomSupport>false</hikxsd:PTZ3DZoomSupport>\n" +
                "                        <hikxsd:PTZPatternSupport>true</hikxsd:PTZPatternSupport>\n" +
                "                    </hikxsd:hikCapabilities>\n" +
                "                    <tt:DeviceIO>\n" +
                "                        <tt:XAddr>http://" + serverIpAddress + "/onvif/DeviceIO</tt:XAddr>\n" +
                "                        <tt:VideoSources>1</tt:VideoSources>\n" +
                "                        <tt:VideoOutputs>0</tt:VideoOutputs>\n" +
                "                        <tt:AudioSources>1</tt:AudioSources>\n" +
                "                        <tt:AudioOutputs>1</tt:AudioOutputs>\n" +
                "                        <tt:RelayOutputs>1</tt:RelayOutputs>\n" +
                "                    </tt:DeviceIO>\n" +
                "                    <tt:Recording>\n" +
                "                        <tt:XAddr>http://" + serverIpAddress + "/onvif/Recording</tt:XAddr>\n" +
                "                        <tt:ReceiverSource>false</tt:ReceiverSource>\n" +
                "                        <tt:MediaProfileSource>true</tt:MediaProfileSource>\n" +
                "                        <tt:DynamicRecordings>false</tt:DynamicRecordings>\n" +
                "                        <tt:DynamicTracks>false</tt:DynamicTracks>\n" +
                "                        <tt:MaxStringLength>64</tt:MaxStringLength>\n" +
                "                    </tt:Recording>\n" +
                "                    <tt:Search>\n" +
                "                        <tt:XAddr>http://" + serverIpAddress + "/onvif/SearchRecording</tt:XAddr>\n" +
                "                        <tt:MetadataSearch>false</tt:MetadataSearch>\n" +
                "                    </tt:Search>\n" +
                "                    <tt:Replay>\n" +
                "                        <tt:XAddr>http://" + serverIpAddress + "/onvif/Replay</tt:XAddr>\n" +
                "                    </tt:Replay>\n" +
                "                </tt:Extension>\n" +
                "            </tds:Capabilities>\n" +
                "        </tds:GetCapabilitiesResponse>\n" +
                "    </env:Body>\n" +
                "</env:Envelope>";

        return response;
    }


}
