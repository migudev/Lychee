package net.aquadc.persistence.sql.blocking

import net.aquadc.persistence.array
import net.aquadc.persistence.fatAsList
import net.aquadc.persistence.fatMapTo
import net.aquadc.persistence.sql.Dao
import net.aquadc.persistence.sql.ExperimentalSql
import net.aquadc.persistence.sql.Fetch
import net.aquadc.persistence.sql.IdBound
import net.aquadc.persistence.sql.Order
import net.aquadc.persistence.sql.RealDao
import net.aquadc.persistence.sql.RealTransaction
import net.aquadc.persistence.sql.Session
import net.aquadc.persistence.sql.Table
import net.aquadc.persistence.sql.Transaction
import net.aquadc.persistence.sql.VarFunc
import net.aquadc.persistence.sql.WhereCondition
import net.aquadc.persistence.sql.bindInsertionParams
import net.aquadc.persistence.sql.bindQueryParams
import net.aquadc.persistence.sql.bindValues
import net.aquadc.persistence.sql.dialect.Dialect
import net.aquadc.persistence.sql.dialect.foldArrayType
import net.aquadc.persistence.sql.mapIndexedToArray
import net.aquadc.persistence.sql.noOrder
import net.aquadc.persistence.struct.Schema
import net.aquadc.persistence.struct.Struct
import net.aquadc.persistence.type.AnyCollection
import net.aquadc.persistence.type.DataType
import net.aquadc.persistence.type.Ilk
import net.aquadc.persistence.type.i64
import net.aquadc.persistence.type.serialized
import org.intellij.lang.annotations.Language
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.getOrSet

/**
 * Represents a database connection through JDBC.
 */
@ExperimentalSql
class JdbcSession(
        @JvmField @JvmSynthetic internal val connection: Connection,
        @JvmField @JvmSynthetic internal val dialect: Dialect
) : Session<Blocking<ResultSet>> {

    init {
        connection.autoCommit = false
    }

    @JvmField @JvmSynthetic internal val lock = ReentrantReadWriteLock()

    @Suppress("UNCHECKED_CAST")
    override fun <SCH : Schema<SCH>, ID : IdBound> get(
            table: Table<SCH, ID>
    ): Dao<SCH, ID> =
            getDao(table) as Dao<SCH, ID>

    @JvmSynthetic internal fun <SCH : Schema<SCH>, ID : IdBound> getDao(table: Table<SCH, ID>): RealDao<SCH, ID, PreparedStatement> =
        lowLevel.daos.getOrPut(table) { RealDao(this, lowLevel, table, dialect) } as RealDao<SCH, ID, PreparedStatement>

    // region transactions and modifying statements

    // transactional things, guarded by write-lock
    @JvmField @JvmSynthetic internal var transaction: RealTransaction? = null

    @JvmField @JvmSynthetic internal val selectStatements = ThreadLocal<MutableMap<String, PreparedStatement>>()

    private val lowLevel: LowLevelSession<PreparedStatement, ResultSet> = object : LowLevelSession<PreparedStatement, ResultSet>() {

        override fun <SCH : Schema<SCH>, ID : IdBound> insert(table: Table<SCH, ID>, data: Struct<SCH>): ID {
            val dao = getDao(table)
            val statement = dao.insertStatement ?: connection.prepareStatement(dialect.insert(table), Statement.RETURN_GENERATED_KEYS).also { dao.insertStatement = it }

            bindInsertionParams(table, data) { type, idx, value ->
                type.bind(statement, idx, value)
            }
            try {
                check(statement.executeUpdate() == 1)
            } catch (e: SQLException) {
                statement.close() // poisoned statement
                dao.insertStatement = null

                throw e
            }
            return statement.generatedKeys.fetchSingle(table.idColType)
        }

        private fun <SCH : Schema<SCH>> updateStatementWLocked(table: Table<SCH, *>, cols: Any): PreparedStatement =
                getDao(table)
                        .updateStatements
                        .getOrPut(cols) {
                            val colArray =
                                    if (cols is Array<*>) cols as Array<out CharSequence>
                                    else arrayOf(cols as CharSequence)
                            connection.prepareStatement(dialect.updateQuery(table, colArray))
                        }

        override fun <SCH : Schema<SCH>, ID : IdBound> update(
                table: Table<SCH, ID>, id: ID, columnNames: Any, columnTypes: Any, values: Any?
        ) {
            val statement = updateStatementWLocked(table, columnNames)
            val colCount = bindValues(columnTypes, values) { type, idx, value ->
                type.bind(statement, idx, value)
            }
            table.idColType.bind(statement, colCount, id)
            check(statement.executeUpdate() == 1)
        }

        override fun <SCH : Schema<SCH>, ID : IdBound> delete(table: Table<SCH, ID>, primaryKey: ID) {
            val dao = getDao(table)
            val statement = dao.deleteStatement ?: connection.prepareStatement(dialect.deleteRecordQuery(table)).also { dao.deleteStatement = it }
            table.idColType.bind(statement, 0, primaryKey)
            check(statement.executeUpdate() == 1)
        }

        override fun truncate(table: Table<*, *>) {
            val stmt = connection.createStatement()
            try {
                stmt.execute(dialect.truncate(table))
            } finally {
                stmt.close()
            }
        }

        override fun onTransactionEnd(successful: Boolean) {
            val transaction = transaction ?: throw AssertionError()
            try {
                if (successful) {
                    connection.commit()
                } else {
                    connection.rollback()
                }
                this@JdbcSession.transaction = null

                if (successful) {
                    transaction.deliverChanges()
                }
            } finally {
                lock.writeLock().unlock()
            }
        }

        private fun <SCH : Schema<SCH>, ID : IdBound> select(
                table: Table<SCH, ID>,
                columns: Array<out CharSequence>?,
                condition: WhereCondition<SCH>,
                order: Array<out Order<SCH>>
        ): ResultSet {
            val query =
                    if (columns == null) dialect.selectCountQuery(table, condition)
                    else dialect.selectQuery(table, columns, condition, order)

            return selectStatements
                    .getOrSet(::HashMap)
                    .getOrPut(query) { connection.prepareStatement(query) }
                    .also { stmt ->
                        bindQueryParams(condition, table) { type, idx, value ->
                            type.bind(stmt, idx, value)
                        }
                    }
                    .executeQuery()
        }

        override fun <SCH : Schema<SCH>, ID : IdBound, T> fetchSingle(
            table: Table<SCH, ID>, colName: CharSequence, colType: Ilk<T, *>, id: ID
        ): T =
                select<SCH, ID>(table /* fixme allocation */, arrayOf(colName), pkCond<SCH, ID>(table, id), noOrder())
                        .fetchSingle(colType)

        override fun <SCH : Schema<SCH>, ID : IdBound> fetchPrimaryKeys(
                table: Table<SCH, ID>, condition: WhereCondition<SCH>, order: Array<out Order<SCH>>
        ): Array<ID> =
                select<SCH, ID>(table /* fixme allocation */, arrayOf(table.pkColumn.name(table.schema)), condition, order)
                        .fetchAllRows(table.idColType)
                        .array<Any>() as Array<ID>

        override fun <SCH : Schema<SCH>, ID : IdBound> fetchCount(
                table: Table<SCH, ID>, condition: WhereCondition<SCH>
        ): Long =
                select<SCH, ID>(table, null, condition, noOrder()).fetchSingle(i64)

        override fun <SCH : Schema<SCH>, ID : IdBound> fetch(
            table: Table<SCH, ID>, columnNames: Array<out CharSequence>, columnTypes: Array<out Ilk<*, *>>, id: ID
        ): Array<Any?> =
                select<SCH, ID>(table, columnNames, pkCond<SCH, ID>(table, id), noOrder()).fetchColumns(columnTypes)

        override val transaction: RealTransaction?
            get() = this@JdbcSession.transaction

        private fun <T> ResultSet.fetchAllRows(type: Ilk<T, *>): List<T> {
            // TODO pre-size collection && try not to box primitives
            val values = ArrayList<T>()
            while (next())
                values.add(type.get(this, 0))
            close()
            return values
        }

        private fun <T> ResultSet.fetchSingle(type: Ilk<T, *>): T =
                try {
                    check(next())
                    type.get(this, 0)
                } finally {
                    close()
                }

        private fun ResultSet.fetchColumns(types: Array<out Ilk<*, *>>): Array<Any?> =
                try {
                    check(next())
                    types.mapIndexedToArray { index, type ->
                        type.get(this, index)
                    }
                } finally {
                    close()
                }

        private fun <T> Ilk<T, *>.bind(statement: PreparedStatement, index: Int, value: T) {
            val i = 1 + index
            val custom = this.custom
            if (custom != null) {
                statement.setObject(i, custom.invoke(value))
            } else {
                val t = type as DataType<T>
                val type = if (t is DataType.Nullable<*, *>) {
                    if (value == null) {
                        statement.setNull(i, Types.NULL)
                        return
                    }
                    t.actualType as DataType.NotNull<T>
                } else type as DataType.NotNull<T>

                when (type) {
                    is DataType.NotNull.Simple -> {
                        val v = type.store(value)
                        when (type.kind) {
                            DataType.NotNull.Simple.Kind.Bool -> statement.setBoolean(i, v as Boolean)
                            DataType.NotNull.Simple.Kind.I32 -> statement.setInt(i, v as Int)
                            DataType.NotNull.Simple.Kind.I64 -> statement.setLong(i, v as Long)
                            DataType.NotNull.Simple.Kind.F32 -> statement.setFloat(i, v as Float)
                            DataType.NotNull.Simple.Kind.F64 -> statement.setDouble(i, v as Double)
                            DataType.NotNull.Simple.Kind.Str -> statement.setString(i, v as String)
                            // not sure whether setBlob should be used:
                            DataType.NotNull.Simple.Kind.Blob -> statement.setObject(i, v as ByteArray)
                        }//.also { }
                    }
                    is DataType.NotNull.Collect<T, *, *> -> {
                        foldArrayType(
                            dialect.hasArraySupport, type.elementType,
                            { nullable, elT ->
                                statement.setArray(i,
                                    connection.createArrayOf(
                                        jdbcElType(type.elementType),
                                        toArray(type.store(value), nullable, elT)
                                    )
                                )
                            },
                            {
                                statement.setObject(i, serialized(type).store(value))
                            }
                        )
                    }
                    is DataType.NotNull.Partial<T, *> -> {
                        throw AssertionError() // 🤔 btw, Oracle supports Struct type
                    }
                }
            }
        }
        private fun jdbcElType(t: DataType<*>): String = when (t) {
            is DataType.Nullable<*, *> -> jdbcElType(t.actualType)
            is DataType.NotNull.Simple -> dialect.nameOf(t.kind)
            is DataType.NotNull.Collect<*, *, *> -> jdbcElType(t.elementType)
            is DataType.NotNull.Partial<*, *> -> dialect.nameOf(DataType.NotNull.Simple.Kind.Blob)
        }
        private fun <T> toArray(value: AnyCollection, nullable: Boolean, elT: DataType.NotNull.Simple<T>): Array<out Any?> =
            (value.fatAsList() as List<T?>).let { value ->
                Array<Any?>(value.size) {
                    val el = value[it]
                    if (el == null) check(nullable).let { null }
                    else elT.store(el)
                }
            }

        @Suppress("IMPLICIT_CAST_TO_ANY", "UNCHECKED_CAST")
        private /*wannabe inline*/ fun <T> Ilk<T, *>.get(resultSet: ResultSet, index: Int): T {
            return get1indexed(resultSet, 1 + index)
        }

        private fun <T> Ilk<T, *>.get1indexed(resultSet: ResultSet, i: Int): T = custom.let { custom ->
            if (custom != null) {
                custom.back(resultSet.getObject(i))
            } else {
                val t = type as DataType<T>
                val nullable: Boolean
                val type =
                    if (t is DataType.Nullable<*, *>) { nullable = true; t.actualType as DataType.NotNull<T> }
                    else { nullable = false; type as DataType.NotNull<T> }
                when (type) {
                    is DataType.NotNull.Simple -> {
                        val v = when (type.kind) {
                            DataType.NotNull.Simple.Kind.Bool -> resultSet.getBoolean(i)
                            DataType.NotNull.Simple.Kind.I32 -> resultSet.getInt(i)
                            DataType.NotNull.Simple.Kind.I64 -> resultSet.getLong(i)
                            DataType.NotNull.Simple.Kind.F32 -> resultSet.getFloat(i)
                            DataType.NotNull.Simple.Kind.F64 -> resultSet.getDouble(i)
                            DataType.NotNull.Simple.Kind.Str -> resultSet.getString(i)
                            DataType.NotNull.Simple.Kind.Blob -> resultSet.getBytes(i)
                            else -> throw AssertionError()
                        }
                        // must check, will get zeroes otherwise
                        if (resultSet.wasNull()) check(nullable).let { null as T }
                        else type.load(v)
                    }
                    is DataType.NotNull.Collect<T, *, *> -> {
                        foldArrayType(dialect.hasArraySupport, type.elementType,
                            { nullable, elT ->
                                val arr = resultSet.getArray(i)
                                if (resultSet.wasNull()) { check(nullable); null as T }
                                else fromArray(type, arr.array as Array<out Any?>, nullable, elT)
                            },
                            {
                                val obj = resultSet.getObject(i)
                                if (resultSet.wasNull()) { check(nullable); null as T }
                                else serialized(type).load(obj)
                            }
                        )
                    }
                    is DataType.NotNull.Partial<T, *> -> {
                        throw AssertionError()
                    }
                }
            }
        }
        private fun <T> fromArray(type: DataType.NotNull.Collect<T, *, *>, value: AnyCollection, nullable: Boolean, elT: DataType.NotNull.Simple<*>): T =
            type.load(value.fatMapTo(ArrayList<Any?>()) { it: Any? ->
                if (it == null) { check(nullable); null } else elT.load(it)
            })


        override fun <T> cell(
            query: String, argumentTypes: Array<out Ilk<*, DataType.NotNull<*>>>, arguments: Array<out Any>, type: Ilk<T, *>, orElse: () -> T
        ): T {
            val rs = select(query, argumentTypes, arguments, 1)
            try {
                if (!rs.next()) return orElse()
                val value = type.get(rs, 0)
                check(!rs.next())
                return value
            } finally {
                rs.close()
            }
        }

        override fun select(
            query: String, argumentTypes: Array<out Ilk<*, DataType.NotNull<*>>>, arguments: Array<out Any>, expectedCols: Int
        ): ResultSet = selectStatements
                .getOrSet(::HashMap)
                .getOrPut(query) { connection.prepareStatement(query) }
                .also { stmt ->
                    for (idx in argumentTypes.indices) {
                        (argumentTypes[idx] as Ilk<Any?, *>).bind(stmt, idx, arguments[idx])
                    }
                }
                .executeQuery()
                .also {
                    val meta = it.metaData
                    val actualCols = meta.columnCount
                    if (actualCols != expectedCols) {
                        val cols = Array(actualCols) { meta.getColumnLabel(it + 1) }.contentToString()
                        it.close()
                        throw IllegalArgumentException("Expected $expectedCols cols, got $cols")
                    }
                }
        override fun sizeHint(cursor: ResultSet): Int = -1
        override fun next(cursor: ResultSet): Boolean = cursor.next()

        override fun <T> cellByName(cursor: ResultSet, name: CharSequence, type: Ilk<T, *>): T =
                type.get1indexed(cursor, cursor.findColumn(name.toString()))
        override fun <T> cellAt(cursor: ResultSet, col: Int, type: Ilk<T, *>): T =
                type.get(cursor, col)
        override fun rowByName(cursor: ResultSet, columnNames: Array<out CharSequence>, columnTypes: Array<out Ilk<*, *>>): Array<Any?> =
                Array(columnNames.size) { idx -> cellByName(cursor, columnNames[idx], columnTypes[idx]) }
        override fun rowByPosition(cursor: ResultSet, offset: Int, types: Array<out Ilk<*, *>>): Array<Any?> =
                Array(types.size) { idx -> types[idx].get(cursor, offset + idx) }

        override fun close(cursor: ResultSet) =
            cursor.close()
    }


    override fun beginTransaction(): Transaction =
        createTransaction(lock, lowLevel).also { transaction = it }

    // endregion transactions and modifying statements

    override fun toString(): String =
            "JdbcSession(connection=$connection, dialect=${dialect.javaClass.simpleName})"


    fun dump(sb: StringBuilder) {
        sb.append("DAOs\n")
        lowLevel.daos.forEach { (table: Table<*, *>, dao: Dao<*, *>) ->
            sb.append(" ").append(table.name).append("\n")
            dao.dump("  ", sb)

            sb.append("  select statements (for current thread)\n")
            selectStatements.get()?.keys?.forEach { sql ->
                sb.append(' ').append(sql).append("\n")
            }

            arrayOf(
                    "insert statements" to dao.insertStatement,
                    "update statements" to dao.updateStatements,
                    "delete statements" to dao.deleteStatement
            ).forEach { (text, stmts) ->
                sb.append("  ").append(text).append(": ").append(stmts)
            }
        }
    }

    override fun <R> rawQuery(
        @Language("SQL") query: String,
        argumentTypes: Array<out Ilk<*, DataType.NotNull<*>>>,
        fetch: Fetch<Blocking<ResultSet>, R>
    ): VarFunc<Any, R> =
            BlockingQuery(lowLevel, query, argumentTypes, fetch)

}
