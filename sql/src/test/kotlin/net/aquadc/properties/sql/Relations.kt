package net.aquadc.properties.sql

import net.aquadc.persistence.struct.build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test


class Relations {

    /*@Test*/ fun embed() {
        val record = session.withTransaction {
            insert(TableWithEmbed, WithNested.build {
                it[OwnField] = "qwe"
                it[Embedded] = SchWithId.build {
                    it[Id] = 100500
                    it[Value] = "200700"
                }
                it[OtherOwnField] = 16_000_000_000
            })
        }

        assertEquals("qwe", record[WithNested.OwnField])
        assertEquals(100500, record[WithNested.Embedded][SchWithId.Id])
        assertEquals("200700", record[WithNested.Embedded][SchWithId.Value])
        assertEquals("", record[WithNested.Embedded][SchWithId.MutValue])
        assertEquals(16_000_000_000, record[WithNested.OtherOwnField])

        val emb = record[WithNested.Embedded]
        session.withTransaction {
            record[WithNested.Embedded] = SchWithId.build {
                it[Id] = 100500
                it[Value] = "200700"
                it[MutValue] = "mutated"
            }
        }

        val newEmb = record[WithNested.Embedded]
        assertNotSame(newEmb, emb)
        assertEquals(100500, record[WithNested.Embedded][SchWithId.Id])
        assertEquals("200700", record[WithNested.Embedded][SchWithId.Value])
        assertEquals("mutated", emb[SchWithId.MutValue])

        session.withTransaction {
            record[WithNested.Embedded] = SchWithId.build {
                it[Id] = 200700
                it[Value] = "300900"
                it[MutValue] = "changed"
            }
        }

        assertNotSame(record[WithNested.Embedded], newEmb)
        val newerEemb = record[WithNested.Embedded]
        assertEquals(200700, newerEemb[SchWithId.Id])
        assertEquals("300900", newerEemb[SchWithId.Value])
        assertEquals("changed", newerEemb[SchWithId.MutValue])
    }

}