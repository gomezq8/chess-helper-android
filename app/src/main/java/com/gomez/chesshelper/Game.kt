package com.gomez.chesshelper

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

/** Follows the game: works out which move was played from what the screen shows. */
class Game(val myWhite: Boolean) {
    val board = Board()

    companion object {
        val SQ: Array<Square> = Array(64) { Square.valueOf("${'A' + it % 8}${it / 8 + 1}") }
        fun name(i: Int) = "${'a' + i % 8}${i / 8 + 1}"
        fun index(name: String) = (name[1] - '1') * 8 + (name[0] - 'a')
    }

    val myTurn: Boolean get() = (board.sideToMove == Side.WHITE) == myWhite
    val fen: String get() = board.fen
    val over: Boolean get() = board.isMated || board.isDraw || board.isStaleMate

    fun occupancy(): IntArray {
        val o = IntArray(64)
        for (i in 0 until 64) {
            val p = board.getPiece(SQ[i])
            if (p != Piece.NONE) o[i] = if (p.pieceSide == Side.WHITE) 1 else 2
        }
        return o
    }

    fun pieceName(i: Int): String = when (board.getPiece(SQ[i]).pieceType) {
        PieceType.PAWN -> "بيدق"; PieceType.KNIGHT -> "حصان"; PieceType.BISHOP -> "فيل"
        PieceType.ROOK -> "قلعة"; PieceType.QUEEN -> "وزير"; PieceType.KING -> "ملك"
        else -> ""
    }

    private fun ordered(): List<Move> = board.legalMoves().sortedBy {
        if (it.promotion == Piece.NONE || it.promotion.pieceType == PieceType.QUEEN) 0 else 1
    }

    /** Finds one move, or two moves in a row, that lead to [occ]. Plays them and returns true. */
    fun follow(occ: IntArray): Boolean {
        for (m in ordered()) {
            board.doMove(m)
            if (occupancy().contentEquals(occ)) return true
            board.undoMove()
        }
        for (m in ordered()) {
            board.doMove(m)
            for (m2 in ordered()) {
                board.doMove(m2)
                if (occupancy().contentEquals(occ)) return true
                board.undoMove()
            }
            board.undoMove()
        }
        return false
    }
}
