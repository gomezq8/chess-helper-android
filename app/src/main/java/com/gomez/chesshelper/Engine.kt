package com.gomez.chesshelper

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.Writer

/** Talks to the Stockfish program (UCI protocol). */
class Engine(path: String) {
    data class Result(val move: String, val cp: Int?, val mate: Int?)

    private val proc: Process = ProcessBuilder(path).redirectErrorStream(true).start()
    private val out: Writer = proc.outputStream.bufferedWriter()
    private val inp: BufferedReader = BufferedReader(InputStreamReader(proc.inputStream))

    init {
        send("uci"); waitFor("uciok")
        send("setoption name Threads value 2")
        send("isready"); waitFor("readyok")
    }

    private fun send(cmd: String) { out.write(cmd + "\n"); out.flush() }

    private fun waitFor(prefix: String): String {
        while (true) {
            val line = inp.readLine() ?: throw IllegalStateException("Stockfish stopped")
            if (line.startsWith(prefix)) return line
        }
    }

    @Synchronized
    fun best(fen: String, ms: Int): Result {
        send("position fen $fen")
        send("go movetime $ms")
        var cp: Int? = null; var mate: Int? = null
        while (true) {
            val line = inp.readLine() ?: throw IllegalStateException("Stockfish stopped")
            if (line.startsWith("info") && " score " in line) {
                val t = line.split(" ")
                val i = t.indexOf("score")
                if (i >= 0 && i + 2 < t.size) {
                    if (t[i + 1] == "cp") { cp = t[i + 2].toIntOrNull(); mate = null }
                    if (t[i + 1] == "mate") { mate = t[i + 2].toIntOrNull(); cp = null }
                }
            }
            if (line.startsWith("bestmove")) return Result(line.split(" ")[1], cp, mate)
        }
    }

    fun quit() {
        try { send("quit") } catch (_: Exception) {}
        proc.destroy()
    }
}
