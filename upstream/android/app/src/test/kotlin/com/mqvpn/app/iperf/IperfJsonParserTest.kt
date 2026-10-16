// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.iperf

import org.junit.Assert.*
import org.junit.Test

class IperfJsonParserTest {
    @Test fun tcpReceiverSummary() {
        val result = IperfJsonParser.parse(
            """{"end":{"sum_sent":{"bytes":12500000},"sum_received":{"bytes":12000000,"bits_per_second":9600000}}}""",
            IperfProtocol.TCP, 0)
        assertTrue(result.valid)
        assertEquals(9.6, result.receiverMbps!!, 0.0001)
        assertEquals(12500000L, result.txBytes)
        assertEquals(12000000L, result.rxBytes)
    }

    @Test fun udpReceiverMetrics() {
        val result = IperfJsonParser.parse(
            """{"end":{"sum_sent":{"bytes":1400000},"sum_received":{"bytes":1200000,"bits_per_second":960000,"lost_percent":2.5,"jitter_ms":1.2}}}""",
            IperfProtocol.UDP, 0)
        assertTrue(result.valid)
        assertEquals(0.96, result.receiverMbps!!, 0.0001)
        assertEquals(2.5, result.udpLossPercent!!, 0.0001)
        assertEquals(1.2, result.udpJitterMs!!, 0.0001)
    }

    @Test fun errorOverridesExistingSummary() {
        val result = IperfJsonParser.parse(
            """{"error":"server is busy","end":{"sum_received":{"bits_per_second":9000000}}}""",
            IperfProtocol.TCP, 0)
        assertFalse(result.valid)
        assertEquals("server is busy", result.error)
        assertNull(result.receiverMbps)
    }

    @Test fun nonzeroExitOverridesExistingSummary() {
        val result = IperfJsonParser.parse(
            """{"end":{"sum_received":{"bits_per_second":9000000}}}""",
            IperfProtocol.TCP, 1)
        assertFalse(result.valid)
        assertNull(result.receiverMbps)
    }

    @Test fun missingFinalSummaryIsFailure() {
        val result = IperfJsonParser.parse(
            """{"intervals":[{"sum":{"bits_per_second":9000000}}]}""",
            IperfProtocol.TCP, 0)
        assertFalse(result.valid)
        assertNotNull(result.error)
    }

    @Test fun malformedJsonIsFailure() {
        val result = IperfJsonParser.parse("connection refused", IperfProtocol.TCP, 1)
        assertFalse(result.valid)
        assertNotNull(result.error)
    }

    @Test fun udpSenderOnlyIsNotAReceiverResult() {
        val result = IperfJsonParser.parse(
            """{"end":{"sum":{"sender":true,"bytes":1000,"bits_per_second":8000}}}""",
            IperfProtocol.UDP, 0)
        assertFalse(result.valid)
        assertNull(result.receiverMbps)
    }

    @Test fun udpExplicitReceiverFallback() {
        val result = IperfJsonParser.parse(
            """{"end":{"sum":{"sender":false,"bytes":1000,"bits_per_second":8000,"lost_percent":0}}}""",
            IperfProtocol.UDP, 0)
        assertTrue(result.valid)
        assertEquals(0.008, result.receiverMbps!!, 0.0001)
    }
    @Test fun unsafeAndUnsupportedOptionsAreRejected() {
        assertNotNull(IperfOptions(host = "-danger").validationError())
        assertNotNull(IperfOptions(host = "name;cmd").validationError())
        assertNotNull(IperfOptions(durationSeconds = 15).validationError())
        assertNotNull(IperfOptions(streams = 99).validationError())
        assertNull(IperfOptions().validationError())
    }

    @Test fun streamedParallelIntervalUsesAggregateOnce() {
        val event = IperfJsonParser.streamEvent(
            """{"event":"interval","data":{"sum":{"end":2,"bytes":3000000,"bits_per_second":24000000,"omitted":false,"sender":false},"streams":[{"end":2,"bytes":1000000,"bits_per_second":8000000,"sender":false},{"end":2,"bytes":2000000,"bits_per_second":16000000,"sender":false}]}}""",
            IperfDirection.DOWN) as IperfJsonParser.StreamEvent.Interval
        assertEquals(24.0, event.sample.mbps, 0.0001)
        assertEquals(3000000L, event.sample.bytes)
        assertFalse(event.sample.sender)
    }

    @Test fun streamedIntervalCanSumStreamsWhenAggregateMissing() {
        val event = IperfJsonParser.streamEvent(
            """{"event":"interval","data":{"streams":[{"end":3,"bytes":1000,"bits_per_second":8000,"sender":true},{"end":3,"bytes":2000,"bits_per_second":16000,"sender":true}]}}""",
            IperfDirection.UP) as IperfJsonParser.StreamEvent.Interval
        assertEquals(0.024, event.sample.mbps, 0.0001)
        assertEquals(3000L, event.sample.bytes)
    }

    @Test fun omittedIntervalIsMarkedAndNotACompletedResult() {
        val event = IperfJsonParser.streamEvent(
            """{"event":"interval","data":{"sum":{"end":1,"bytes":1000,"bits_per_second":8000,"omitted":true,"sender":false}}}""",
            IperfDirection.DOWN) as IperfJsonParser.StreamEvent.Interval
        assertTrue(event.sample.omitted)
        assertFalse(IperfJsonParser.parseStreamFinal(null, null, IperfProtocol.TCP, 0, "").valid)
    }

    @Test fun stringErrorAndEmptyEndWithZeroExitStayFailure() {
        val error = IperfJsonParser.streamEvent(
            """{"event":"error","data":"unable to create a new stream: Permission denied"}""",
            IperfDirection.DOWN) as IperfJsonParser.StreamEvent.Error
        val end = IperfJsonParser.streamEvent("""{"event":"end","data":{}}""",
            IperfDirection.DOWN) as IperfJsonParser.StreamEvent.End
        val result = IperfJsonParser.parseStreamFinal(end.data, error.message, IperfProtocol.TCP, 0, "")
        assertFalse(result.valid)
        assertEquals("unable to create a new stream: Permission denied", result.error)
        assertNull(result.receiverMbps)
    }

    @Test fun streamedEndNeedsReceiverEvenAfterValidIntervals() {
        val end = IperfJsonParser.streamEvent(
            """{"event":"end","data":{"sum_sent":{"bits_per_second":8000000}}}""",
            IperfDirection.DOWN) as IperfJsonParser.StreamEvent.End
        assertFalse(IperfJsonParser.parseStreamFinal(end.data, null, IperfProtocol.TCP, 0, "").valid)
    }
    @Test fun streamedEndProducesAuthoritativeReceiverResult() {
        val end = IperfJsonParser.streamEvent(
            """{"event":"end","data":{"sum_sent":{"bytes":1300000,"bits_per_second":1040000},"sum_received":{"bytes":1200000,"bits_per_second":960000}}}""",
            IperfDirection.DOWN) as IperfJsonParser.StreamEvent.End
        val result = IperfJsonParser.parseStreamFinal(end.data, null, IperfProtocol.TCP, 0, "raw stream")
        assertTrue(result.valid)
        assertEquals(0.96, result.receiverMbps!!, 0.0001)
        assertEquals("raw stream", result.rawJson)
    }
}
