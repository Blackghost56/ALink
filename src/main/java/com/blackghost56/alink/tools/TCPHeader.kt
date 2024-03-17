package com.blackghost56.alink.tools

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder



class TCPHeader(
){
    enum class TCPMessageType(val value: Int) {
        USER_DATA(0),
        PING(1);

        companion object {
            fun fromValue(value: Int): TCPMessageType? {
                return entries.firstOrNull { it.value == value }
            }
        }
    }

    companion object {
        private val TAG = TCPHeader::class.java.simpleName

        fun toRaw(type: TCPMessageType, data: ByteArray): ByteArray {
            val type_ = Tools.intToArray(type.value, ByteOrder.LITTLE_ENDIAN)

            return type_ + data
        }

        @Throws(Exception::class)
        fun fromRaw(data: ByteArray): Pair<TCPMessageType, ByteArray> {
            var index = 0
            val type = TCPMessageType.fromValue(ByteBuffer.wrap(data, index, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int)
            index += Int.SIZE_BYTES

            if (type == null) {
                Log.e(TAG, "fromRaw: unknown message type")
                throw Exception("Unknown message type")
            }

            return type to data.copyOfRange(index, data.size)
        }

    }


}