package net.majorkernelpanic.onvif;

public interface DeviceStaticInfo {
    String USER_NAME = "ky_lab";
    String USER_PSW = "123456";
    String GET_MEDIA = "/onvif/Media";
    String GET_PTZ = "/onvif/PTZ";
    String GET_ANALYTICS = "/onvif/Analytics";
    String GET_DEVICE_SERVICES = "/onvif/device_services";
    String GET_EVENTS = "/onvif/Events";
    String GET_IMAGING = "/onvif/Imaging";
    /**
     * 获取推流视频的地址的URI
     */
    String GET_STREAM_URI = "onvif/GetStreamUri";
}
