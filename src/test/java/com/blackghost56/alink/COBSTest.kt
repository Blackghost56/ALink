package com.blackghost56.alink

import com.blackghost56.alink.tools.COBS
import junit.framework.TestCase
import org.junit.Test
import kotlin.random.Random

class COBSTest {

    @Test
    fun main() {
        val data = genData(10)

        println("data")
        data.forEach { print("$it ") }
        println("")

        TestCase.assertTrue(test1(data.toList()))
    }

    private fun test1(data: List<Byte>): Boolean {
        println("\nTest1")

        val encodedData = COBS.encode(data)
        println("encodedData")
        encodedData.forEach { print("$it ") }
        println()

        val decodedData = COBS.decode(encodedData)
        println("decodedData")
        decodedData.forEach { print("$it ") }
        println()

        if (data == decodedData)
            return true

        return false
    }


    private fun genData(size: Int) : ByteArray {
        val result = ByteArray(size)
        for (i in 0 until size) {
            result[i] = Random.nextInt(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
        }

        return result
    }
}