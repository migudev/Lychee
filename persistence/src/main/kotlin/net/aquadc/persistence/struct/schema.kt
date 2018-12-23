package net.aquadc.persistence.struct

import net.aquadc.persistence.type.DataType
import java.util.Collections.unmodifiableList
import java.util.Collections.unmodifiableMap

/**
 * Declares a struct (or DTO).
 * `struct`s in C, Rust, Swift, etc, or `Object`s in JS, are similar
 * to final classes with only public fields, no methods and no supertypes.
 * @see Struct
 * @see FieldDef
 */
abstract class Schema<SELF : Schema<SELF>> {

    /**
     * A temporary list of [FieldDef]s used while [Schema] is getting constructed.
     */
    @JvmField @JvmSynthetic internal var tmpFields: ArrayList<FieldDef<SELF, *>>? = ArrayList()

    /**
     * A list of fields of this struct.
     *
     * {@implNote
     *   on concurrent access, we might null out [tmpFields] while it's getting accessed,
     *   so let it be synchronized (it's default [lazy] mode).
     * }
     */
    val fields: List<FieldDef<SELF, *>>
        get() = _fields.value
    private val _fields =
            lazy(LazyFields(0) as () -> List<FieldDef<SELF, *>>)

    val fieldsByName: Map<String, FieldDef<SELF, *>>
        get() = _byName.value
    private val _byName =
            lazy(LazyFields(1) as () -> Map<String, FieldDef<SELF, *>>)

    val mutableFields: List<FieldDef.Mutable<SELF, *>>
        get() = _mutableFields.value
    private val _mutableFields =
            lazy(LazyFields(2) as () -> List<FieldDef.Mutable<SELF, *>>)

    /**
     * Gets called before this fully initialized structDef gets used for the first time.
     */
    protected open fun beforeFreeze(nameSet: Set<String>, fields: List<FieldDef<SELF, *>>) { }

    @JvmSynthetic internal fun tmpFields() =
            tmpFields ?: throw IllegalStateException("schema `${javaClass.simpleName}` is already initialized")

    /**
     * Creates, remembers and returns a new mutable field definition without default value.
     */
    @Suppress("UNCHECKED_CAST")
    protected infix fun <T> String.mut(type: DataType<T>): FieldDef.Mutable<SELF, T> =
            this.mut(type, Unset as T)

    /**
     * Creates, remembers and returns a new mutable field definition with default value.
     */
    protected fun <T> String.mut(dataType: DataType<T>, default: T): FieldDef.Mutable<SELF, T> {
        val fields = tmpFields()
        val converter = dataType
        val col = FieldDef.Mutable(this@Schema, this, converter, fields.size.toByte(), default)
        fields.add(col)
        return col
    }

    /**
     * Creates, remembers and returns a new immutable field definition.
     */
    protected infix fun <T> String.let(dataType: DataType<T>): FieldDef.Immutable<SELF, T> {
        val fields = tmpFields()
        val converter = dataType
        val col = FieldDef.Immutable(this@Schema, this, converter, fields.size.toByte())
        fields.add(col)
        return col
    }

    private inner class LazyFields(
            private val mode: Int
    ) : () -> Any? {

        override fun invoke(): Any? = when (mode) {
            0 -> {
                val fields = tmpFields()
                check(fields.isNotEmpty()) { "Struct must have at least one field." } // fixme: is it necessary?

                val nameSet = HashSet<String>()
                for (i in fields.indices) {
                    val col = fields[i]
                    if (!nameSet.add(col.name)) {
                        throw IllegalStateException("duplicate column: `${this@Schema.javaClass.simpleName}`.`${col.name}`")
                    }
                }

                val frozen = unmodifiableList(fields)
                beforeFreeze(nameSet, frozen)
                tmpFields = null
                frozen
            }

            1 ->
                unmodifiableMap<String, FieldDef<SELF, *>>(
                        fields.associateByTo(HashMap(), FieldDef<SELF, *>::name)
                )

            2 ->
                unmodifiableList(fields.filterIsInstance<FieldDef.Mutable<SELF, *>>())

            else ->
                throw AssertionError()
        }

    }

}

/**
 * Struct field is a single key-value mapping. FieldDef represents a key with name and type.
 * Note: constructors are internal to guarantee correct [ordinal] values.
 * @see StructDef
 * @see Struct
 * @see Mutable
 * @see Immutable
 */
sealed class FieldDef<SCH : Schema<SCH>, T>(
        @JvmField val schema: Schema<SCH>,
        @JvmField val name: String,
        @JvmField val type: DataType<T>,
        @JvmField val ordinal: Byte,
        default: T
) {

    init {
        check(ordinal < 64) { "Ordinal must be in [0..63], $ordinal given" }
    }

    private val _default = default

    val default: T
        get() = if (_default === Unset) throw NoSuchElementException("no default value for $this") else _default

    val hasDefault: Boolean
        @JvmName("hasDefault") get() = _default !== Unset

    override fun toString(): String = schema.javaClass.simpleName + '.' + name

    /**
     * Represents a mutable field of a [Struct]: its value can be changed.
     */
    class Mutable<SCH : Schema<SCH>, T> internal constructor(
            schema: Schema<SCH>,
            name: String,
            converter: DataType<T>,
            ordinal: Byte,
            default: T
    ) : FieldDef<SCH, T>(schema, name, converter, ordinal, default)

    /**
     * Represents an immutable field of a [Struct]: its value must be set during construction and cannot be changed.
     */
    @Suppress("UNCHECKED_CAST")
    class Immutable<SCH : Schema<SCH>, T> internal constructor(
            schema: Schema<SCH>,
            name: String,
            converter: DataType<T>,
            ordinal: Byte
    ) : FieldDef<SCH, T>(schema, name, converter, ordinal, Unset as T)

}

@JvmField @JvmSynthetic internal val Unset = Any()
