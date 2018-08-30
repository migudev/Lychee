@file:Suppress("UNCHECKED_CAST") // this file is for unchecked casts :)
package net.aquadc.properties.sql



internal typealias UpdatesHashMap = HashMap<Col<*, *>, HashMap<Long, Any?>>

internal fun <REC : Record<REC, *>, T> UpdatesHashMap.getFor(col: Col<REC, T>): HashMap<Long, T>? =
        get(col) as HashMap<Long, T>?

internal fun <REC : Record<REC, *>, T> UpdatesHashMap.put(cv: ColValue<REC, T>, localId: Long) {
    (this as HashMap<Col<REC, T>, HashMap<Long, Any?>>).getOrPut(cv.col, ::HashMap)[localId] = cv.value
}

@Suppress("UPPER_BOUND_VIOLATED")
internal inline val Table<*, *>.erased
    get() = this as Table<Any, IdBound>

@Suppress("UPPER_BOUND_VIOLATED")
internal inline val Col<*, *>.erased
    get() = this as Col<Any, Any?>

internal inline val Converter<*>.erased
    get() = this as Converter<Any?>