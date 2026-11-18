package com.kidbuddy.smsforwarder

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun onlyExactFeishuHttpsEndpointIsAccepted() {
        assertTrue(Protocol.validWebhook("https://open.feishu.cn/open-apis/bot/v2/hook/test-only-token"))
        listOf("http://open.feishu.cn/open-apis/bot/v2/hook/test-only-token", "https://open.feishu.cn.evil.test/open-apis/bot/v2/hook/test-only-token", "https://user@open.feishu.cn/open-apis/bot/v2/hook/test-only-token", "https://open.feishu.cn:444/open-apis/bot/v2/hook/test-only-token", "https://open.feishu.cn/open-apis/bot/v2/hook/test-only-token?x=1", "https://open.feishu.cn/open-apis/bot/v2/hook/%2f12345678").forEach { assertFalse(it, Protocol.validWebhook(it)) }
    }
    @Test fun payloadEscapesSmsControlCharacters() {
        assertEquals("{\"msg_type\":\"text\",\"content\":{\"text\":\"短信\\n\\\"\\\\\\t\\u0001\"}}", Protocol.payload("短信\n\"\\\t\u0001", "", 0))
    }
    @Test fun signatureUsesTimestampAndSecretAsKeyAndEmptyMessage() {
        assertEquals("mbm4Y4oluIPQ00qlBIhX8vAZ0EKv3nw0LuTb91jPL84=", Protocol.signature(1700000000, "test-secret"))
    }
    @Test fun dedupIncludesPduAndSubscription() {
        assertEquals(Protocol.fingerprint(1, listOf(byteArrayOf(1, 2))), Protocol.fingerprint(1, listOf(byteArrayOf(1, 2))))
        assertNotEquals(Protocol.fingerprint(1, listOf(byteArrayOf(1, 2))), Protocol.fingerprint(2, listOf(byteArrayOf(1, 2))))
        assertNotEquals(Protocol.fingerprint(1, listOf(byteArrayOf(1, 2))), Protocol.fingerprint(1, listOf(byteArrayOf(1, 3))))
    }
}
