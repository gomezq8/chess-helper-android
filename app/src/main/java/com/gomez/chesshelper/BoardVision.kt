package com.gomez.chesshelper

import kotlin.math.abs
import kotlin.math.max

/** One screenshot: ARGB pixels, row by row. */
class Frame(val w: Int, val h: Int, val px: IntArray) {
    fun r(x: Int, y: Int) = (px[y * w + x] shr 16) and 0xFF
    fun g(x: Int, y: Int) = (px[y * w + x] shr 8) and 0xFF
    fun b(x: Int, y: Int) = px[y * w + x] and 0xFF
}

data class BoardRect(val left: Int, val top: Int, val size: Int) {
    val sq: Double get() = size / 8.0
}

object BoardVision {

    /**
     * Finds the 8x8 board on the screen. Looks for a square area whose 64 squares
     * alternate between two colors. Samples near the top-right and bottom-left corner
     * of every square, where pieces and coordinate letters don't reach.
     */
    fun detect(f: Frame): BoardRect? {
        var best = -1
        var bestRect: BoardRect? = null
        val maxMargin = f.w / 10
        var m = 0
        while (m <= maxMargin) {
            val size = f.w - 2 * m
            if (size < 200) break
            var top = 0
            while (top + size <= f.h) {
                val sc = score(f, m, top, size)
                if (sc > best) { best = sc; bestRect = BoardRect(m, top, size) }
                top += 2
            }
            m += 2
        }
        return if (best >= 118) bestRect else null   // out of 128 samples
    }

    private val SAMPLE = arrayOf(doubleArrayOf(0.92, 0.08), doubleArrayOf(0.08, 0.92))

    private fun score(f: Frame, left: Int, top: Int, size: Int): Int {
        val s = size / 8.0
        val sr = IntArray(128); val sg = IntArray(128); val sb = IntArray(128)
        var lr = 0; var lg = 0; var lb = 0; var dr = 0; var dg = 0; var db = 0
        var i = 0
        for (row in 0 until 8) for (col in 0 until 8) for (p in SAMPLE) {
            val x = (left + (col + p[0]) * s).toInt()
            val y = (top + (row + p[1]) * s).toInt()
            val r = f.r(x, y); val g = f.g(x, y); val b = f.b(x, y)
            sr[i] = r; sg[i] = g; sb[i] = b
            if ((row + col) % 2 == 0) { lr += r; lg += g; lb += b } else { dr += r; dg += g; db += b }
            i++
        }
        lr /= 64; lg /= 64; lb /= 64; dr /= 64; dg /= 64; db /= 64
        if (abs(lr - dr) + abs(lg - dg) + abs(lb - db) < 80) return 0
        var ok = 0
        i = 0
        for (row in 0 until 8) for (col in 0 until 8) for (p in SAMPLE) {
            val light = (row + col) % 2 == 0
            val d = if (light) abs(sr[i] - lr) + abs(sg[i] - lg) + abs(sb[i] - lb)
                    else abs(sr[i] - dr) + abs(sg[i] - dg) + abs(sb[i] - db)
            if (d < 45) ok++
            i++
        }
        return ok
    }

    /**
     * For each square (index = screenRow*8 + screenCol, row 0 = top of the screen):
     * fill = share of pixels that differ from the square's own background (there is a piece),
     * lum  = brightness of those pixels (piece color).
     */
    fun squareStats(f: Frame, b: BoardRect): Pair<DoubleArray, DoubleArray> {
        val fill = DoubleArray(64)
        val lum = DoubleArray(64)
        val s = b.sq
        val e = max(2, (s * 0.06).toInt())
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (row in 0 until 8) for (col in 0 until 8) {
            val x0 = (b.left + col * s).toInt(); val y0 = (b.top + row * s).toInt()
            val n = s.toInt()
            // background = median of a thin strip just inside the square's edge (includes highlight color)
            rs.clear(); gs.clear(); bs.clear()
            fun add(x: Int, y: Int) { rs.add(f.r(x, y)); gs.add(f.g(x, y)); bs.add(f.b(x, y)) }
            var k = e
            while (k < n - e) {
                for (t in e until 2 * e) {
                    add(x0 + k, y0 + t); add(x0 + k, y0 + n - 1 - t)
                    add(x0 + t, y0 + k); add(x0 + n - 1 - t, y0 + k)
                }
                k += 2
            }
            rs.sort(); gs.sort(); bs.sort()
            val br = rs[rs.size / 2]; val bg = gs[gs.size / 2]; val bb = bs[bs.size / 2]
            // centre only (skip the coordinate letters)
            var cnt = 0; var hit = 0; var lsum = 0L
            var y = (n * 0.15).toInt()
            while (y < (n * 0.85).toInt()) {
                var x = (n * 0.15).toInt()
                while (x < (n * 0.85).toInt()) {
                    val r = f.r(x0 + x, y0 + y); val g = f.g(x0 + x, y0 + y); val bl = f.b(x0 + x, y0 + y)
                    cnt++
                    if (abs(r - br) + abs(g - bg) + abs(bl - bb) > 90) { hit++; lsum += (r + g + bl) / 3 }
                    x += 2
                }
                y += 2
            }
            val idx = row * 8 + col
            fill[idx] = hit.toDouble() / max(cnt, 1)
            lum[idx] = if (hit > 0) lsum.toDouble() / hit else 0.0
        }
        return fill to lum
    }
}

/** Reads which squares have a white piece, a black piece, or nothing. */
class BoardReader(val rect: BoardRect) {
    var whiteBottom = true
    private var fillThr = 0.0
    private var lumThr = 0.0

    /** Must be called with the board at the starting position. Returns an error text or null. */
    fun calibrate(f: Frame): String? {
        val (fill, lum) = BoardVision.squareStats(f, rect)
        val pieces = (0 until 64).filter { it / 8 <= 1 || it / 8 >= 6 }.map { fill[it] }.sorted()
        val empty = (0 until 64).filter { it / 8 in 2..5 }.map { fill[it] }.sorted()
        val p10 = pieces[(pieces.size * 0.1).toInt()]
        val e90 = empty[(empty.size * 0.9).toInt()]
        if (p10 <= e90) return "ما قدرت أميز القطع. تأكد إن الرقعة على وضع البداية."
        fillThr = (p10 + e90) / 2
        val top = median((0 until 16).map { lum[it] })
        val bottom = median((48 until 64).map { lum[it] })
        whiteBottom = bottom > top
        lumThr = (top + bottom) / 2
        return null
    }

    /** occ[square] : 0 empty, 1 white, 2 black. square = rank*8 + file (a1 = 0). */
    fun read(f: Frame): IntArray {
        val (fill, lum) = BoardVision.squareStats(f, rect)
        val occ = IntArray(64)
        for (row in 0 until 8) for (col in 0 until 8) {
            val i = row * 8 + col
            if (fill[i] < fillThr) continue
            occ[toSquare(row, col)] = if (lum[i] > lumThr) 1 else 2
        }
        return occ
    }

    fun toSquare(row: Int, col: Int) =
        if (whiteBottom) (7 - row) * 8 + col else row * 8 + (7 - col)

    /** Screen point at the centre of a square. */
    fun center(square: Int): Pair<Float, Float> {
        val file = square % 8; val rank = square / 8
        val col = if (whiteBottom) file else 7 - file
        val row = if (whiteBottom) 7 - rank else rank
        val s = rect.sq
        return Pair((rect.left + (col + 0.5) * s).toFloat(), (rect.top + (row + 0.5) * s).toFloat())
    }

    private fun median(v: List<Double>) = v.sorted()[v.size / 2]
}
