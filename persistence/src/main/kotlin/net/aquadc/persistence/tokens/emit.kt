@file:Suppress("NOTHING_TO_INLINE")
@file:JvmName("EmitTokens")
package net.aquadc.persistence.tokens

import net.aquadc.collections.get
import net.aquadc.persistence.fatAsList
import net.aquadc.persistence.struct.FieldDef
import net.aquadc.persistence.struct.Schema
import net.aquadc.persistence.struct.Struct
import net.aquadc.persistence.struct.forEachIndexed
import net.aquadc.persistence.struct.single
import net.aquadc.persistence.struct.size
import net.aquadc.persistence.type.DataType


fun <T> DataType<T>.tokensFrom(value: T): TokenStream = tokens {
    yield(this@tokensFrom, value)
}

inline fun <SCH : Schema<SCH>> Struct<SCH>.tokens(): TokenStream =
        schema.tokensFrom(this)

@Suppress("UPPER_BOUND_VIOLATED", "UNCHECKED_CAST")
private suspend fun <T> TokenStreamScope.yield(type: DataType<T>, value: T) {
    val type = if (type is DataType.Nullable<*, *>) {
        if (value == null) yieldNull().also { return }
        type.actualType
    } else type

    when (type) {
        is DataType.Nullable<*, *> -> throw AssertionError()
        is DataType.NotNull.Simple -> {
            val token = if (type.hasStringRepresentation) Token.Str else kindToToken[type.kind]!!
            offer(token).let { coerceTo -> // internal API, he-he
                if (coerceTo != false) {
                    val stored = (type as DataType.NotNull.Simple<Any?>)
                        .let { if (it.hasStringRepresentation) it.storeAsString(value) else it.store(value) }
                    yield((coerceTo as Token?).coerce(stored))
                }
            }
        }
        is DataType.NotNull.Collect<*, *, *> -> {
            yieldSequence {
                val elT = type.elementType
                (type as DataType.NotNull.Collect<Any?, *, *>).store(value).fatAsList().forEach {
                    yield(elT as DataType<Any?>, it)
                }
            }
        }
        is DataType.NotNull.Partial<*, *> -> {
            yieldDictionary {
                type as DataType.NotNull.Partial<Any?, Schema<*>>
                val fields = type.fields(value)
                val values = type.store(value)
                val schema: Schema<Schema<*>> = type.schema as Schema<Schema<*>>
                when (fields.size) {
                    0 -> { } // nothing to do here
                    1 -> {
                        val field = schema.single<Schema<*>, FieldDef<Schema<*>, *, *>>(fields) as FieldDef<Schema<*>, Any?, DataType<Any?>>
                        yieldString { schema.run { field.name } }
                        yield(schema.run { field.type }, values)
                    }
                    else -> {
                        values as Array<*>
                        schema.forEachIndexed<Schema<*>, FieldDef<Schema<*>, *, *>>(fields) { idx, field ->
                            field as FieldDef<Schema<*>, Any?, DataType<Any?>>
                            yieldString { schema.run { field.name } }
                            yield(schema.run { field.type }, values[idx])
                        }
                    }
                }
            }
        }
    }
}
