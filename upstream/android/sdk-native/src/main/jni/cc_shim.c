// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mqvpn contributors

#include <jni.h>
#include <stdint.h>
#include "libmqvpn.h"

_Static_assert(sizeof(void *) == sizeof(jlong), "arm64 config pointer must fit jlong");
_Static_assert(MQVPN_CC_BBR2 == 0 && MQVPN_CC_BBR == 1 &&
               MQVPN_CC_CUBIC == 2 && MQVPN_CC_NONE == 3,
               "JNI congestion-control values must match libmqvpn v0.17.0");

/* The official v0.17.0 libmqvpn_jni.so exports mqvpn_config_set_cc, but its
 * JNI wrapper does not expose it. Link this small shim to that pinned library. */
JNIEXPORT jint JNICALL
Java_com_mqvpn_sdk_native_1_NativeBridge_configSetCc(
    JNIEnv *env, jobject bridge, jlong config, jint cc)
{
    (void)env;
    (void)bridge;
    return (jint)mqvpn_config_set_cc(
        (mqvpn_config_t *)(uintptr_t)config, (mqvpn_cc_t)cc);
}

/* Android keeps the virtual TUN MTU at or below 1280 regardless of the
 * server's advertised value. The official JNI wrapper omits this setter. */
JNIEXPORT jint JNICALL
Java_com_mqvpn_sdk_native_1_NativeBridge_configSetTunMtu(
    JNIEnv *env, jobject bridge, jlong config, jint mtu)
{
    (void)env;
    (void)bridge;
    return (jint)mqvpn_config_set_tun_mtu(
        (mqvpn_config_t *)(uintptr_t)config, (int)mtu);
}
