package com.mqvpn.app.simcontrol;

interface ISimSwitchService {
    int getDefaultDataSubId() = 0;
    int setDefaultDataSubId(int targetSubId) = 1;
    // Reserved Shizuku UserService lifecycle transaction.
    void destroy() = 16777114;
}
