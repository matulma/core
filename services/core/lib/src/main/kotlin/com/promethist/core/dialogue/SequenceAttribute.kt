package com.promethist.core.dialogue

import com.promethist.core.type.DateTime
import com.promethist.core.type.Memory
import com.promethist.core.type.MemoryMutableSet
import kotlin.random.Random

abstract class SequenceAttribute<E, V : Any>(
        val list: List<E>,
        private val memories: MemoryMutableSet<V>,
        val nextBlock: (SequenceAttribute<E, V>.() -> E?)) {

    val next get() = nextBlock(this)
    val last get() = last() ?: error("no item in sequence (access next property first)")

    fun last() = memories.maxBy { it.time }?.let { memory ->
        list.find { toMemoryValue(it) == memory.value }
    }

    fun nextRandom(minDuration: DateTimeUnit = 1.day, maxCount: Int = Int.MAX_VALUE, resetDuration: DateTimeUnit? = null): E? {
        val now = DateTime.now()
        memories.filter { resetDuration != null && it.time + resetDuration <= now }.forEach { it.count = 0 }
        val values = mutableListOf<V>()
        list.forEach { e ->
            val value = toMemoryValue(e)
            val lastMemory = memories.find {
                (it.value == value) && (it.count >= maxCount - 1 || it.time + minDuration > now)
            }
            if (lastMemory == null)
                values.add(value)
        }
        if (values.isNotEmpty()) {
            val value = values[Random.nextInt(values.size)]
            memories.add(Memory(value))
            return list.find { toMemoryValue(it) == value }
        }
        return null
    }

    fun nextInLine(minDuration: DateTimeUnit = 1.day, maxCount: Int = Int.MAX_VALUE, resetDuration: DateTimeUnit? = null): E? {
        val now = DateTime.now()
        memories.filter { resetDuration != null && it.time + resetDuration <= now }.forEach { it.count = 0 }
        val last = last()
        val i = if (last == null)
            0
        else list.indexOf(last).let {
            if (it + 1 < list.size) it + 1 else 0
        }
        val e = list[i]
        val value = toMemoryValue(e)
        val lastMemory = memories.find { it.value == value }
        return if ((lastMemory == null) || (lastMemory.count < maxCount && lastMemory.time + minDuration < now)) {
            memories.add(Memory(value))
            e
        } else {
            null
        }
    }

    abstract fun toMemoryValue(e: E): V
}