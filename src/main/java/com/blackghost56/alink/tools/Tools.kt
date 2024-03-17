package com.blackghost56.alink.tools

import java.nio.ByteOrder

class Tools {


    companion object{

        fun intToArray(value: Int, order: ByteOrder): ByteArray {
            val data = ByteArray(4)
            if (ByteOrder.LITTLE_ENDIAN == order) {
                data[0] = (value and 0xff).toByte()
                data[1] = (value shr 8 and 0xff).toByte()
                data[2] = (value shr 16 and 0xff).toByte()
                data[3] = (value shr 24 and 0xff).toByte()
            } else if (ByteOrder.BIG_ENDIAN == order) {
                data[3] = (value and 0xff).toByte()
                data[2] = (value shr 8 and 0xff).toByte()
                data[1] = (value shr 16 and 0xff).toByte()
                data[0] = (value shr 24 and 0xff).toByte()
            }

            return data
        }
    }
}