package com.sipedas.ponorogo.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class SipedasParserTest {

    @Test
    fun testParseLaporanWithUserFormat() {
        val reportText = """
            Kepada. 
            Yth. Kepala Bidang SDA dan LINMAS Satpol PP Kabupaten Ponorogo
            di -
                 Ponorogo

            *Mohon ijin Melaporkan Hasil  PelaksanaanKegiatan :*
            Patroli Linmas Pedestrian di Jl. Jenderal Sudirman, Jl. Diponegoro, Jl Urip Sumoharjo, Jl. HOS Cokro Aminoto.

            *Sebagai Berikut :*

            *Hari Pelaksanaan*
            Hari         : Jumat
            Tanggal  : 3 April 2026

            *Identitas / Nama Pelanggaran*
             
            NIHIL

            *Personil yang terlibat :* ( Agus , Aziz, Imam Tauchid, Fajar ) 

            *Keterangan:*
            Pelaksanaan berjalan aman dan lancar dengan kondisi cuaca terang / tidak hujan, kondisi lalu lintas rame lancar. Pusat keramaian terpusat di lingkup alun-alun kota ponorogo. 

            Demikian Yang Dapat Kami Laporkan Untuk  Menjadikan Periksa.

            *Danru 2*

            ( basItH )
        """.trimIndent()

        val parsed = SipedasParser.parseLaporan(reportText)

        assertEquals("Basith", parsed.namaDanru)
        assertEquals("Basith", parsed.danru)
        assertEquals("Jumat", parsed.hari)
        assertEquals("3 April 2026", parsed.tanggal)
        assertEquals("NIHIL", parsed.identitas)
        assertEquals("Agus , Aziz, Imam Tauchid, Fajar", parsed.personil)
        assertEquals("Patroli Linmas Pedestrian di Jl. Jenderal Sudirman, Jl. Diponegoro, Jl Urip Sumoharjo, Jl. HOS Cokro Aminoto.", parsed.lokasi)
        assertEquals("Pelaksanaan berjalan aman dan lancar dengan kondisi cuaca terang / tidak hujan, kondisi lalu lintas rame lancar. Pusat keramaian terpusat di lingkup alun-alun kota ponorogo.", parsed.keterangan)
    }

    @Test
    fun testParseAlternativeFormats() {
        val format1 = """
            Danru : basith
            Hari : Senin
            Tanggal: 10 Mei 2026
        """.trimIndent()
        val parsed1 = SipedasParser.parseLaporan(format1)
        assertEquals("Basith", parsed1.namaDanru)
        assertEquals("Senin", parsed1.hari)
        assertEquals("10 Mei 2026", parsed1.tanggal)

        val format2 = """
            danru 2 (basith)
            Hari         : Selasa
        """.trimIndent()
        val parsed2 = SipedasParser.parseLaporan(format2)
        assertEquals("Basith", parsed2.namaDanru)
        assertEquals("Selasa", parsed2.hari)

        val format3 = """
            danru basith
        """.trimIndent()
        val parsed3 = SipedasParser.parseLaporan(format3)
        assertEquals("Basith", parsed3.namaDanru)
    }
}
