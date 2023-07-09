package com.blackghost56.alink.tools

import java.util.LinkedList


class COBS {

    companion object {
        const val EOP: Byte = 0

        fun encode(data: List<Byte>): List<Byte> {
            val result: MutableList<Byte> = LinkedList()

            val dataCopy = LinkedList(data)
            dataCopy.add(EOP)
            var counter = 1
            val subList = LinkedList<Byte>()

            for (i in dataCopy.indices) {
                if (counter == 255 && i != dataCopy.size - 1) {
                    subList.addFirst(counter.toByte())
                    result.addAll(subList)
                    subList.clear()
                    counter = 1
                }
                val element = dataCopy[i]
                if (element == EOP) {
                    subList.addFirst(counter.toByte())
                    result.addAll(subList)
                    subList.clear()
                    counter = 1
                }
                if (element != EOP) {
                    subList.add(element)
                    counter++
                }
            }

            return result
        }


        fun decode(data: List<Byte>): List<Byte> {
            val result: MutableList<Byte> = LinkedList()

            var lastEOP = 0
            var nextEOP = 0xff and data[0].toInt()
            for (i in 1 until data.size) {
                val value = data[i]
                if (i == nextEOP) {
                    if (nextEOP - lastEOP != 255)
                        result.add(EOP)
                    lastEOP = nextEOP
                    nextEOP += 0xff and value.toInt()
                } else {
                    result.add(value)
                }
            }

            return result
        }

    }


}