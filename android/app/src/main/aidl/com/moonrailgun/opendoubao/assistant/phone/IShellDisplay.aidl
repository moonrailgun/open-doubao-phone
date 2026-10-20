package com.moonrailgun.opendoubao.assistant.phone;

interface IShellDisplay {
    void attach(IBinder owner) = 0;
    int createDisplay() = 1;
    void launch(String packageName, String url) = 2;
    void key(int code) = 3;
    void releaseDisplay(boolean handOver) = 4;
    void destroy() = 16777114;
}
