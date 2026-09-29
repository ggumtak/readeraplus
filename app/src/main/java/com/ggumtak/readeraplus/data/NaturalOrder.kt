package com.ggumtak.readeraplus.data

/**
 * "Natural" string order for titles: digit runs compare by numeric value ("2권" < "10권", "001화" == "1화"
 * by value, shorter zero padding first), other chars case-insensitively (Hangul keeps its code point order,
 * i.e. 가나다 order). Allocation-free.
 */
internal object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        val na = a.length
        val nb = b.length
        var zeroTie = 0
        while (i < na && j < nb) {
            val ca = a[i]
            val cb = b[j]
            if (isDigit(ca) && isDigit(cb)) {
                // Skip leading zeros, remembering the padding difference as a late tie-breaker.
                var si = i
                while (si < na && a[si] == '0') si++
                var sj = j
                while (sj < nb && b[sj] == '0') sj++
                var ei = si
                while (ei < na && isDigit(a[ei])) ei++
                var ej = sj
                while (ej < nb && isDigit(b[ej])) ej++
                val lenA = ei - si
                val lenB = ej - sj
                if (lenA != lenB) return if (lenA < lenB) -1 else 1
                for (k in 0 until lenA) {
                    val da = a[si + k]
                    val db = b[sj + k]
                    if (da != db) return if (da < db) -1 else 1
                }
                if (zeroTie == 0) {
                    val za = si - i
                    val zb = sj - j
                    if (za != zb) zeroTie = if (za < zb) -1 else 1
                }
                i = ei
                j = ej
                continue
            }
            if (ca != cb) {
                val fa = fold(ca)
                val fb = fold(cb)
                if (fa != fb) return if (fa < fb) -1 else 1
            }
            i++
            j++
        }
        val restA = na - i
        val restB = nb - j
        if (restA != restB) return if (restA < restB) -1 else 1
        if (zeroTie != 0) return zeroTie
        return a.compareTo(b)
    }

    private fun isDigit(c: Char): Boolean = c in '0'..'9'

    private fun fold(c: Char): Char = if (c < '\u0080') {
        if (c in 'A'..'Z') c + 32 else c
    } else {
        Character.toLowerCase(Character.toUpperCase(c))
    }
}
